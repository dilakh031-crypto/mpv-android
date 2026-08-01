#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <algorithm>
#include <cmath>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <pthread.h>

#include <mpv/client.h>
#include <mpv/render_gl.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"
#include "render_bridge.h"

namespace {

constexpr double MAX_OFFSCREEN_PIXELS = 4096.0 * 4096.0;
constexpr int WINDOW_RETRY_DELAY_MS = 16;
constexpr int MAX_WINDOW_RETRIES = 6;

struct Geometry {
    int normal_w = 1;
    int normal_h = 1;
    int detail_w = 1;
    int detail_h = 1;
    bool use_detail = false;
    float left = 0.0f;
    float top = 0.0f;
    float right = 1.0f;
    float bottom = 1.0f;
};

struct RenderTarget {
    GLuint texture = 0;
    GLuint framebuffer = 0;
    int width = 0;
    int height = 0;
    int requested_width = 0;
    int requested_height = 0;
    int requested_minimum_width = 0;
    int requested_minimum_height = 0;
    bool has_frame = false;
    bool allocation_failed = false;
};

class RenderApiRenderer {
public:
    // Takes ownership of the ANativeWindow reference supplied by the caller.
    bool attach(ANativeWindow *window, int width, int height)
    {
        if (!window || width <= 0 || height <= 0) {
            if (window)
                ANativeWindow_release(window);
            return false;
        }

        std::unique_lock<std::mutex> lock(mutex_);
        ensure_thread_locked();

        if (pending_window_)
            ANativeWindow_release(pending_window_);
        pending_window_ = window;
        window_width_ = width;
        window_height_ = height;
        const uint64_t serial = ++attach_serial_;
        ++geometry_serial_;
        window_retry_count_ = 0;
        mpv_render_pending_ = true;
        surface_change_pending_ = true;
        composite_pending_ = true;
        condition_.notify_all();

        condition_.wait(lock, [&] {
            return stop_requested_ || completed_attach_serial_ >= serial;
        });
        return !stop_requested_ && completed_attach_serial_ >= serial && last_attach_ok_;
    }

    void resize(int width, int height)
    {
        if (width <= 0 || height <= 0)
            return;

        std::lock_guard<std::mutex> lock(mutex_);
        if (window_width_ > 0 && window_height_ > 0) {
            const float sx = width / static_cast<float>(window_width_);
            const float sy = height / static_cast<float>(window_height_);
            geometry_.left *= sx;
            geometry_.right *= sx;
            geometry_.top *= sy;
            geometry_.bottom *= sy;
        }
        window_width_ = width;
        window_height_ = height;
        ++geometry_serial_;
        window_retry_count_ = 0;
        composite_pending_ = true;
        condition_.notify_all();
    }

    void detach()
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!thread_started_)
            return;

        const uint64_t serial = ++detach_serial_;
        detach_pending_ = true;
        condition_.notify_all();
        condition_.wait(lock, [&] {
            return stop_requested_ || completed_detach_serial_ >= serial;
        });
    }

    uint64_t begin_new_media()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ++geometry_serial_;
        invalidate_targets_pending_ = true;
        // Do not redraw a retained frame from the previous playlist entry. The
        // render thread will wait for the next mpv frame update before it can
        // publish this new presentation serial.
        mpv_render_pending_ = false;
        composite_pending_ = false;
        condition_.notify_all();
        return geometry_serial_;
    }

    uint64_t set_geometry(const Geometry &geometry)
    {
        std::lock_guard<std::mutex> lock(mutex_);

        const bool entering_detail = !geometry_.use_detail && geometry.use_detail;
        const bool target_changed =
            geometry_.normal_w != geometry.normal_w ||
            geometry_.normal_h != geometry.normal_h ||
            geometry_.detail_w != geometry.detail_w ||
            geometry_.detail_h != geometry.detail_h ||
            geometry_.use_detail != geometry.use_detail;
        const bool destination_changed =
            geometry_.left != geometry.left ||
            geometry_.top != geometry.top ||
            geometry_.right != geometry.right ||
            geometry_.bottom != geometry.bottom;

        if (target_changed || destination_changed) {
            geometry_ = geometry;
            ++geometry_serial_;
            window_retry_count_ = 0;
            // A failed high-detail allocation is cached to protect gesture smoothness.
            // Permit exactly one fresh attempt when a later pinch enters detail mode;
            // never retry it on every move event of the same gesture.
            if (entering_detail)
                detail_target_.allocation_failed = false;
            if (target_changed)
                mpv_render_pending_ = true;
            composite_pending_ = true;
            condition_.notify_all();
        }
        return geometry_serial_;
    }

    uint64_t presented_geometry_serial()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return presented_geometry_serial_;
    }

    void signal_mpv_update()
    {
        std::lock_guard<std::mutex> lock(mutex_);
        mpv_update_pending_ = true;
        condition_.notify_all();
    }

    void shutdown()
    {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!thread_started_) {
            if (pending_window_) {
                ANativeWindow_release(pending_window_);
                pending_window_ = nullptr;
            }
            return;
        }

        stop_requested_ = true;
        condition_.notify_all();
        lock.unlock();
        if (thread_.joinable())
            thread_.join();
        lock.lock();

        thread_started_ = false;
        stop_requested_ = false;
        surface_ready_ = false;
        last_attach_ok_ = false;
        surface_change_pending_ = false;
        detach_pending_ = false;
        invalidate_targets_pending_ = false;
        waiting_for_new_media_frame_ = false;
        window_retry_count_ = 0;
        mpv_update_pending_ = false;
        mpv_render_pending_ = true;
        composite_pending_ = true;
        // Never expose a serial from a destroyed EGL surface to a newly attached
        // TextureView. The next successful swap will publish the current serial.
        presented_geometry_serial_ = 0;
        last_presented_target_ = -1;
        if (pending_window_) {
            ANativeWindow_release(pending_window_);
            pending_window_ = nullptr;
        }
    }

private:
    void ensure_thread_locked()
    {
        if (thread_started_)
            return;
        thread_started_ = true;
        thread_ = std::thread([this] { thread_main(); });
    }

    static void mpv_update_callback(void *context)
    {
        static_cast<RenderApiRenderer *>(context)->signal_mpv_update();
    }

    void schedule_window_retry(bool requeue_update = false, bool requeue_render = false)
    {
        // EGL surface loss is normally transient (rotation, compositor hand-off,
        // or a newly recreated TextureView). Retain the already rendered FBO and
        // retry on the next display interval. If failure happened before libmpv's
        // update was consumed, preserve that work as well (important for paused
        // images, where no later callback may arrive).
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stop_requested_ || window_retry_count_ >= MAX_WINDOW_RETRIES)
                return;
            ++window_retry_count_;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(WINDOW_RETRY_DELAY_MS));
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stop_requested_) {
            mpv_update_pending_ = mpv_update_pending_ || requeue_update;
            mpv_render_pending_ = mpv_render_pending_ || requeue_render;
            composite_pending_ = true;
            condition_.notify_all();
        }
    }

    static void *get_proc_address(void *, const char *name)
    {
        void *proc = reinterpret_cast<void *>(eglGetProcAddress(name));
        if (proc)
            return proc;
        return dlsym(RTLD_DEFAULT, name);
    }

    void thread_main()
    {
        pthread_setname_np(pthread_self(), "mpv_render_api");

        for (;;) {
            ANativeWindow *new_window = nullptr;
            bool detach = false;
            bool stop = false;
            bool update = false;
            bool invalidate_targets = false;
            bool render_requested = false;
            bool composite_requested = false;
            uint64_t attach_serial = 0;
            uint64_t detach_serial = 0;
            uint64_t geometry_serial = 0;
            Geometry geometry;
            int requested_window_w = 1;
            int requested_window_h = 1;

            {
                std::unique_lock<std::mutex> lock(mutex_);
                condition_.wait(lock, [&] {
                    return stop_requested_ || surface_change_pending_ || detach_pending_ ||
                        invalidate_targets_pending_ || mpv_update_pending_ ||
                        mpv_render_pending_ || composite_pending_;
                });

                stop = stop_requested_;
                if (surface_change_pending_) {
                    new_window = pending_window_;
                    pending_window_ = nullptr;
                    surface_change_pending_ = false;
                    attach_serial = attach_serial_;
                }
                if (detach_pending_) {
                    detach = true;
                    detach_pending_ = false;
                    detach_serial = detach_serial_;
                }
                invalidate_targets = invalidate_targets_pending_;
                update = mpv_update_pending_;
                render_requested = mpv_render_pending_;
                composite_requested = composite_pending_;
                invalidate_targets_pending_ = false;
                mpv_update_pending_ = false;
                mpv_render_pending_ = false;
                composite_pending_ = false;
                geometry = geometry_;
                geometry_serial = geometry_serial_;
                requested_window_w = window_width_;
                requested_window_h = window_height_;
            }

            if (stop) {
                if (new_window)
                    ANativeWindow_release(new_window);
                break;
            }

            if (detach)
                detach_window_surface();

            if (new_window) {
                const bool ok = attach_window_surface(new_window);
                // attach_window_surface() consumes the ANativeWindow reference.
                new_window = nullptr;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    surface_ready_ = ok;
                    last_attach_ok_ = ok;
                    if (ok)
                        window_retry_count_ = 0;
                    completed_attach_serial_ = std::max(completed_attach_serial_, attach_serial);
                    condition_.notify_all();
                }
                render_requested = true;
                composite_requested = true;
            }

            if (detach) {
                std::lock_guard<std::mutex> lock(mutex_);
                surface_ready_ = false;
                completed_detach_serial_ = std::max(completed_detach_serial_, detach_serial);
                condition_.notify_all();
            }

            if (invalidate_targets) {
                normal_target_.has_frame = false;
                detail_target_.has_frame = false;
                normal_target_.allocation_failed = false;
                detail_target_.allocation_failed = false;
                waiting_for_new_media_frame_ = true;
                last_presented_target_ = -1;
                render_requested = false;
                composite_requested = false;
            }

            if (!render_context_)
                continue;

            const bool has_window = window_ != nullptr;
            if (has_window) {
                if (!ensure_window_current()) {
                    ALOGE("failed to make Render API window current");
                    schedule_window_retry(update, render_requested);
                    continue;
                }
            } else if (!make_pbuffer_current()) {
                ALOGE("failed to make Render API pbuffer current");
                continue;
            }

            bool frame_update = false;
            if (update) {
                const uint64_t flags = mpv_render_context_update(render_context_);
                frame_update = (flags & MPV_RENDER_UPDATE_FRAME) != 0;
                if (frame_update) {
                    waiting_for_new_media_frame_ = false;
                    render_requested = true;
                }
            }

            // Keep libmpv's frame queue moving while the TextureView is detached,
            // but do not waste memory/bandwidth rendering an invisible FBO.
            if (!has_window) {
                if (frame_update && skip_mpv_frame())
                    mpv_render_context_report_swap(render_context_);
                continue;
            }

            // START_FILE invalidates retained FBO contents. Geometry changes may
            // arrive before decoding starts, but they must not redraw/publish the
            // previous file under a new serial.
            if (waiting_for_new_media_frame_)
                continue;

            int actual_window_w = requested_window_w;
            int actual_window_h = requested_window_h;
            EGLint queried_w = 0;
            EGLint queried_h = 0;
            if (eglQuerySurface(display_, window_surface_, EGL_WIDTH, &queried_w) == EGL_TRUE && queried_w > 0)
                actual_window_w = queried_w;
            if (eglQuerySurface(display_, window_surface_, EGL_HEIGHT, &queried_h) == EGL_TRUE && queried_h > 0)
                actual_window_h = queried_h;

            const int desired_target_index = geometry.use_detail ? 1 : 0;
            if (last_presented_target_ >= 0 &&
                last_presented_target_ != desired_target_index) {
                RenderTarget *bridge = last_presented_target_ == 1
                    ? &detail_target_ : &normal_target_;
                if (bridge->has_frame) {
                    // Present the new zoom rectangle immediately from the already
                    // available texture. Rendering the other FBO can be expensive
                    // for 4K/8K media, but it must never delay finger movement or
                    // expose an intermediate change in image dimensions.
                    bool bridge_swapped = composite_target(
                        *bridge, geometry, actual_window_w, actual_window_h);
                    if (!bridge_swapped && recover_window_surface())
                        bridge_swapped = composite_target(
                            *bridge, geometry, actual_window_w, actual_window_h);
                    if (bridge_swapped) {
                        std::lock_guard<std::mutex> lock(mutex_);
                        window_retry_count_ = 0;
                        presented_geometry_serial_ = std::max(
                            presented_geometry_serial_, geometry_serial);
                    }
                }
            }

            RenderTarget *active = geometry.use_detail ? &detail_target_ : &normal_target_;
            int active_target_index = desired_target_index;
            const int desired_w = geometry.use_detail ? geometry.detail_w : geometry.normal_w;
            const int desired_h = geometry.use_detail ? geometry.detail_h : geometry.normal_h;
            const int minimum_w = geometry.use_detail && normal_target_.width > 0
                ? normal_target_.width : 1;
            const int minimum_h = geometry.use_detail && normal_target_.height > 0
                ? normal_target_.height : 1;
            bool detail_fallback = false;

            if (!ensure_target(
                    *active, desired_w, desired_h, minimum_w, minimum_h, geometry.use_detail)) {
                if (!geometry.use_detail) {
                    ALOGE("failed to allocate normal Render API target %dx%d", desired_w, desired_h);
                    if (frame_update && skip_mpv_frame())
                        mpv_render_context_report_swap(render_context_);
                    continue;
                }

                // Memory/texture limits must never turn a pinch into a black or frozen
                // window. Fall back to the already display-sized target and preserve
                // exactly the same compositor geometry; only source detail is reduced.
                detail_fallback = true;
                active = &normal_target_;
                active_target_index = 0;
                if (!ensure_target(
                        *active, geometry.normal_w, geometry.normal_h, 1, 1, false)) {
                    ALOGE("failed to allocate Render API detail and fallback targets");
                    if (frame_update && skip_mpv_frame())
                        mpv_render_context_report_swap(render_context_);
                    continue;
                }
                if (active->has_frame && !frame_update)
                    render_requested = false;
            }

            bool rendered_frame = false;
            if (render_requested || !active->has_frame) {
                // A geometry-only redraw (not a newly timed video frame) should not
                // block the first pinch waiting for mpv's presentation timestamp.
                // Normal playback keeps mpv's target-time wait for A/V timing.
                // While zoomed, gesture recomposition must not be queued behind a
                // 24/30 fps timing wait; EGL swap still provides display pacing.
                rendered_frame = render_mpv(
                    *active, frame_update && !geometry.use_detail);
                if (rendered_frame) {
                    active->has_frame = true;
                    composite_requested = true;
                }
            }

            // Do not expose a frame using stale destination geometry, and especially
            // never publish a previous playlist entry after START_FILE invalidated it
            // while mpv_render_context_render() was in progress. A pan/resize already
            // queued its latest compositor state; a media change will discard the FBO.
            bool superseded = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                superseded = invalidate_targets_pending_ || geometry_serial_ != geometry_serial;
            }
            if (superseded) {
                if (rendered_frame)
                    mpv_render_context_report_swap(render_context_);
                continue;
            }

            bool swapped = false;
            if (active->has_frame && (composite_requested || rendered_frame || detail_fallback)) {
                swapped = composite_target(*active, geometry, actual_window_w, actual_window_h);
                if (!swapped && recover_window_surface())
                    swapped = composite_target(*active, geometry, actual_window_w, actual_window_h);
                if (!swapped)
                    schedule_window_retry();
            }
            if (swapped) {
                std::lock_guard<std::mutex> lock(mutex_);
                window_retry_count_ = 0;
                presented_geometry_serial_ = std::max(
                    presented_geometry_serial_, geometry_serial);
                last_presented_target_ = active_target_index;
            }

            // Only report a swap for a frame actually pulled through the mpv Render
            // API. Pan/zoom-only recomposites must not perturb mpv's video timing.
            if (rendered_frame)
                mpv_render_context_report_swap(render_context_);

            // Allocate the detail target after a normal frame is visible. Allocation
            // is bounded and can fall back, so a huge image cannot black-screen the
            // fixed window surface or exhaust the device with an unbounded texture.
            if (!geometry.use_detail && normal_target_.has_frame &&
                (detail_target_.requested_width != geometry.detail_w ||
                 detail_target_.requested_height != geometry.detail_h ||
                 detail_target_.requested_minimum_width != std::max(1, normal_target_.width) ||
                 detail_target_.requested_minimum_height != std::max(1, normal_target_.height))) {
                ensure_target(
                    detail_target_,
                    geometry.detail_w,
                    geometry.detail_h,
                    std::max(1, normal_target_.width),
                    std::max(1, normal_target_.height),
                    true);
            }
        }

        cleanup_gl();
    }

    bool initialize_egl()
    {
        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT &&
            pbuffer_surface_ != EGL_NO_SURFACE && render_context_) {
            return true;
        }

        cleanup_gl();

        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY) {
            ALOGE("eglGetDisplay failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }
        if (eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) {
            ALOGE("eglInitialize failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }
        if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
            ALOGE("eglBindAPI failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }

        const EGLint config_attributes[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE,
        };
        EGLint config_count = 0;
        if (eglChooseConfig(display_, config_attributes, &config_, 1, &config_count) != EGL_TRUE ||
            config_count < 1) {
            ALOGE("eglChooseConfig failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }
        if (eglGetConfigAttrib(display_, config_, EGL_NATIVE_VISUAL_ID, &native_visual_id_) != EGL_TRUE) {
            ALOGE("eglGetConfigAttrib(EGL_NATIVE_VISUAL_ID) failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }

        const EGLint context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE,
        };
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, context_attributes);
        if (context_ == EGL_NO_CONTEXT) {
            ALOGE("eglCreateContext failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }

        const EGLint pbuffer_attributes[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE,
        };
        pbuffer_surface_ = eglCreatePbufferSurface(display_, config_, pbuffer_attributes);
        if (pbuffer_surface_ == EGL_NO_SURFACE) {
            ALOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }
        if (!make_pbuffer_current()) {
            ALOGE("eglMakeCurrent(pbuffer) failed: 0x%x", eglGetError());
            cleanup_gl();
            return false;
        }

        if (!initialize_compositor()) {
            cleanup_gl();
            return false;
        }

        mpv_opengl_init_params gl_init = {
            get_proc_address,
            nullptr,
        };
        int advanced_control = 1;
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
            // This dedicated thread never calls normal libmpv APIs and never waits
            // on the UI/core while an mpv_render_* call is active, so advanced
            // control is safe and gives correct update/swap timing semantics.
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced_control},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        const int result = mpv_render_context_create(&render_context_, g_mpv, params);
        if (result < 0) {
            ALOGE("mpv_render_context_create failed: %s", mpv_error_string(result));
            render_context_ = nullptr;
            cleanup_gl();
            return false;
        }
        mpv_render_context_set_update_callback(render_context_, mpv_update_callback, this);

        GLint max_texture_size = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_texture_size);
        max_texture_size_ = std::max(1, static_cast<int>(max_texture_size));
        ALOGV("Render API initialized; GL_MAX_TEXTURE_SIZE=%d", max_texture_size_);
        return true;
    }

    bool attach_window_surface(ANativeWindow *window)
    {
        if (!initialize_egl()) {
            ANativeWindow_release(window);
            return false;
        }

        detach_window_surface();
        window_ = window;
        if (!create_window_surface()) {
            ANativeWindow_release(window_);
            window_ = nullptr;
            return false;
        }
        return true;
    }

    bool create_window_surface()
    {
        if (!window_ || display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT)
            return false;

        // Match the EGL config's native pixel format without changing the
        // SurfaceTexture dimensions (0x0 keeps Android's current window size).
        if (ANativeWindow_setBuffersGeometry(window_, 0, 0, native_visual_id_) != 0)
            ALOGV("ANativeWindow_setBuffersGeometry failed");

        window_surface_ = eglCreateWindowSurface(display_, config_, window_, nullptr);
        if (window_surface_ == EGL_NO_SURFACE) {
            ALOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
            return false;
        }
        if (!make_window_current()) {
            ALOGE("eglMakeCurrent(window) failed: 0x%x", eglGetError());
            eglDestroySurface(display_, window_surface_);
            window_surface_ = EGL_NO_SURFACE;
            return false;
        }
        eglSwapInterval(display_, 1);
        return true;
    }

    bool recover_window_surface()
    {
        if (!window_ || display_ == EGL_NO_DISPLAY)
            return false;

        make_pbuffer_current();
        if (window_surface_ != EGL_NO_SURFACE)
            eglDestroySurface(display_, window_surface_);
        window_surface_ = EGL_NO_SURFACE;
        return create_window_surface();
    }

    bool ensure_window_current()
    {
        if (make_window_current())
            return true;
        return recover_window_surface();
    }

    bool make_window_current()
    {
        return display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT &&
            window_surface_ != EGL_NO_SURFACE &&
            eglMakeCurrent(display_, window_surface_, window_surface_, context_) == EGL_TRUE;
    }

    bool make_pbuffer_current()
    {
        return display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT &&
            pbuffer_surface_ != EGL_NO_SURFACE &&
            eglMakeCurrent(display_, pbuffer_surface_, pbuffer_surface_, context_) == EGL_TRUE;
    }

    void detach_window_surface()
    {
        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT &&
            pbuffer_surface_ != EGL_NO_SURFACE) {
            make_pbuffer_current();
        }
        if (display_ != EGL_NO_DISPLAY && window_surface_ != EGL_NO_SURFACE)
            eglDestroySurface(display_, window_surface_);
        window_surface_ = EGL_NO_SURFACE;
        if (window_) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    static GLuint compile_shader(GLenum type, const char *source)
    {
        const GLuint shader = glCreateShader(type);
        if (!shader)
            return 0;
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled == GL_TRUE)
            return shader;

        GLchar log_buffer[1024] = {};
        glGetShaderInfoLog(shader, sizeof(log_buffer), nullptr, log_buffer);
        ALOGE("shader compilation failed: %s", log_buffer);
        glDeleteShader(shader);
        return 0;
    }

    bool initialize_compositor()
    {
        static const char *highp_vertex_source =
            "attribute vec2 a_position;\n"
            "attribute vec2 a_tex_coord;\n"
            "varying highp vec2 v_tex_coord;\n"
            "void main() {\n"
            "  gl_Position = vec4(a_position, 0.0, 1.0);\n"
            "  v_tex_coord = a_tex_coord;\n"
            "}\n";
        static const char *highp_fragment_source =
            "precision highp float;\n"
            "uniform sampler2D u_texture;\n"
            "varying highp vec2 v_tex_coord;\n"
            "void main() {\n"
            "  gl_FragColor = texture2D(u_texture, v_tex_coord);\n"
            "}\n";
        static const char *mediump_vertex_source =
            "attribute vec2 a_position;\n"
            "attribute vec2 a_tex_coord;\n"
            "varying mediump vec2 v_tex_coord;\n"
            "void main() {\n"
            "  gl_Position = vec4(a_position, 0.0, 1.0);\n"
            "  v_tex_coord = a_tex_coord;\n"
            "}\n";
        static const char *mediump_fragment_source =
            "precision mediump float;\n"
            "uniform sampler2D u_texture;\n"
            "varying mediump vec2 v_tex_coord;\n"
            "void main() {\n"
            "  gl_FragColor = texture2D(u_texture, v_tex_coord);\n"
            "}\n";

        GLint precision_range[2] = {0, 0};
        GLint precision_bits = 0;
        glGetShaderPrecisionFormat(
            GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, precision_range, &precision_bits);
        const bool fragment_highp = precision_bits > 0;
        const char *vertex_source = fragment_highp ? highp_vertex_source : mediump_vertex_source;
        const char *fragment_source = fragment_highp ? highp_fragment_source : mediump_fragment_source;

        const GLuint vertex_shader = compile_shader(GL_VERTEX_SHADER, vertex_source);
        const GLuint fragment_shader = compile_shader(GL_FRAGMENT_SHADER, fragment_source);
        if (!vertex_shader || !fragment_shader) {
            if (vertex_shader)
                glDeleteShader(vertex_shader);
            if (fragment_shader)
                glDeleteShader(fragment_shader);
            return false;
        }

        program_ = glCreateProgram();
        if (!program_) {
            glDeleteShader(vertex_shader);
            glDeleteShader(fragment_shader);
            return false;
        }
        glAttachShader(program_, vertex_shader);
        glAttachShader(program_, fragment_shader);
        glBindAttribLocation(program_, 0, "a_position");
        glBindAttribLocation(program_, 1, "a_tex_coord");
        glLinkProgram(program_);
        glDeleteShader(vertex_shader);
        glDeleteShader(fragment_shader);

        GLint linked = GL_FALSE;
        glGetProgramiv(program_, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) {
            GLchar log_buffer[1024] = {};
            glGetProgramInfoLog(program_, sizeof(log_buffer), nullptr, log_buffer);
            ALOGE("compositor program link failed: %s", log_buffer);
            glDeleteProgram(program_);
            program_ = 0;
            return false;
        }
        texture_uniform_ = glGetUniformLocation(program_, "u_texture");
        if (texture_uniform_ < 0) {
            ALOGE("compositor texture uniform was not found");
            glDeleteProgram(program_);
            program_ = 0;
            return false;
        }
        return true;
    }

    void destroy_target(RenderTarget &target)
    {
        if (target.framebuffer)
            glDeleteFramebuffers(1, &target.framebuffer);
        if (target.texture)
            glDeleteTextures(1, &target.texture);
        target = RenderTarget{};
    }

    bool allocate_target(RenderTarget &target, int width, int height)
    {
        RenderTarget candidate;
        glGenTextures(1, &candidate.texture);
        if (!candidate.texture)
            return false;
        glBindTexture(GL_TEXTURE_2D, candidate.texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        while (glGetError() != GL_NO_ERROR) {}
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        if (glGetError() != GL_NO_ERROR) {
            destroy_target(candidate);
            return false;
        }

        glGenFramebuffers(1, &candidate.framebuffer);
        if (!candidate.framebuffer) {
            destroy_target(candidate);
            return false;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, candidate.framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, candidate.texture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            destroy_target(candidate);
            return false;
        }

        candidate.width = width;
        candidate.height = height;
        destroy_target(target);
        target = candidate;
        return true;
    }

    bool ensure_target(
        RenderTarget &target,
        int desired_w,
        int desired_h,
        int minimum_w,
        int minimum_h,
        bool release_existing_before_allocate)
    {
        desired_w = std::max(1, desired_w);
        desired_h = std::max(1, desired_h);
        minimum_w = std::max(1, std::min(minimum_w, desired_w));
        minimum_h = std::max(1, std::min(minimum_h, desired_h));

        if (target.requested_width == desired_w &&
            target.requested_height == desired_h &&
            target.requested_minimum_width == minimum_w &&
            target.requested_minimum_height == minimum_h) {
            if (target.allocation_failed)
                return false;
            if (target.texture)
                return true;
        }

        // A source-detail target can be large. Release its obsolete allocation
        // before requesting a replacement so temporary double allocation cannot
        // cause a false OOM. The normal target is retained until replacement is
        // ready because it is the visible safety path.
        if (release_existing_before_allocate && target.texture)
            destroy_target(target);

        double hard_limit_scale = 1.0;
        if (desired_w > max_texture_size_)
            hard_limit_scale = std::min(
                hard_limit_scale, max_texture_size_ / static_cast<double>(desired_w));
        if (desired_h > max_texture_size_)
            hard_limit_scale = std::min(
                hard_limit_scale, max_texture_size_ / static_cast<double>(desired_h));
        const double desired_pixels = static_cast<double>(desired_w) * desired_h;
        if (desired_pixels > MAX_OFFSCREEN_PIXELS) {
            hard_limit_scale = std::min(
                hard_limit_scale, std::sqrt(MAX_OFFSCREEN_PIXELS / desired_pixels));
        }

        // Use one scale factor for both dimensions. Independent clamping would
        // change the video aspect ratio on very large/foldable displays. The
        // requested minimum is also hard-limited so an oversized window can still
        // receive the largest correctly proportioned target the GPU supports.
        const double requested_min_scale = std::min(
            1.0,
            std::max(
                minimum_w / static_cast<double>(desired_w),
                minimum_h / static_cast<double>(desired_h)));
        const double minimum_scale = std::min(requested_min_scale, hard_limit_scale);
        double allocation_scale = hard_limit_scale;

        for (;;) {
            const int width = std::max(
                1, static_cast<int>(std::floor(desired_w * allocation_scale)));
            const int height = std::max(
                1, static_cast<int>(std::floor(desired_h * allocation_scale)));
            if (allocate_target(target, width, height)) {
                target.requested_width = desired_w;
                target.requested_height = desired_h;
                target.requested_minimum_width = minimum_w;
                target.requested_minimum_height = minimum_h;
                if (width != desired_w || height != desired_h) {
                    ALOGV("Render API target reduced from %dx%d to %dx%d",
                          desired_w, desired_h, width, height);
                }
                return true;
            }

            if (allocation_scale <= minimum_scale + 0.000001)
                break;
            const double next_scale = std::max(minimum_scale, allocation_scale * 0.75);
            if (next_scale >= allocation_scale - 0.000001)
                break;
            allocation_scale = next_scale;
        }
        // Cache an impossible request. Repeating a large allocation on every
        // Choreographer tick would itself destroy zoom smoothness. A changed media
        // size or a recreated GL context naturally clears this failure state.
        target.requested_width = desired_w;
        target.requested_height = desired_h;
        target.requested_minimum_width = minimum_w;
        target.requested_minimum_height = minimum_h;
        target.allocation_failed = true;
        return false;
    }

    bool render_mpv(RenderTarget &target, bool block_for_target_time)
    {
        glUseProgram(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, target.width, target.height);

        mpv_opengl_fbo fbo = {
            static_cast<int>(target.framebuffer),
            target.width,
            target.height,
            0,
        };
        int flip_y = 0;
        int block = block_for_target_time ? 1 : 0;
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
            {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &block},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        const int result = mpv_render_context_render(render_context_, params);
        if (result < 0) {
            ALOGE("mpv_render_context_render failed: %s", mpv_error_string(result));
            return false;
        }
        return true;
    }

    bool skip_mpv_frame()
    {
        int skip = 1;
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_SKIP_RENDERING, &skip},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };
        const int result = mpv_render_context_render(render_context_, params);
        if (result < 0) {
            ALOGE("mpv skip-render failed: %s", mpv_error_string(result));
            return false;
        }
        return true;
    }

    bool composite_target(const RenderTarget &target, const Geometry &geometry, int window_w, int window_h)
    {
        if (!program_ || !target.texture || window_w <= 0 || window_h <= 0)
            return false;

        const float left = (2.0f * geometry.left / window_w) - 1.0f;
        const float right = (2.0f * geometry.right / window_w) - 1.0f;
        const float top = 1.0f - (2.0f * geometry.top / window_h);
        const float bottom = 1.0f - (2.0f * geometry.bottom / window_h);

        const GLfloat vertices[] = {
            left,  top,    0.0f, 1.0f,
            right, top,    1.0f, 1.0f,
            left,  bottom, 0.0f, 0.0f,
            right, bottom, 1.0f, 0.0f,
        };

        while (glGetError() != GL_NO_ERROR) {}
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glViewport(0, 0, window_w, window_h);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(program_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, target.texture);
        glUniform1i(texture_uniform_, 0);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), vertices + 2);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);

        const GLenum gl_error = glGetError();
        if (gl_error != GL_NO_ERROR) {
            ALOGE("OpenGL compositor failed: 0x%x", gl_error);
            return false;
        }
        if (eglSwapBuffers(display_, window_surface_) != EGL_TRUE) {
            ALOGE("eglSwapBuffers failed: 0x%x", eglGetError());
            return false;
        }
        return true;
    }

    void cleanup_gl()
    {
        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT &&
            pbuffer_surface_ != EGL_NO_SURFACE) {
            make_pbuffer_current();
        }

        if (render_context_) {
            mpv_render_context_set_update_callback(render_context_, nullptr, nullptr);
            mpv_render_context_free(render_context_);
            render_context_ = nullptr;
        }

        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT &&
            pbuffer_surface_ != EGL_NO_SURFACE) {
            destroy_target(normal_target_);
            destroy_target(detail_target_);
            if (program_)
                glDeleteProgram(program_);
        } else {
            normal_target_ = RenderTarget{};
            detail_target_ = RenderTarget{};
        }
        program_ = 0;
        texture_uniform_ = -1;

        detach_window_surface();
        if (display_ != EGL_NO_DISPLAY)
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (display_ != EGL_NO_DISPLAY && pbuffer_surface_ != EGL_NO_SURFACE)
            eglDestroySurface(display_, pbuffer_surface_);
        pbuffer_surface_ = EGL_NO_SURFACE;
        if (display_ != EGL_NO_DISPLAY && context_ != EGL_NO_CONTEXT)
            eglDestroyContext(display_, context_);
        context_ = EGL_NO_CONTEXT;
        if (display_ != EGL_NO_DISPLAY)
            eglTerminate(display_);
        eglReleaseThread();
        display_ = EGL_NO_DISPLAY;
        config_ = nullptr;
        native_visual_id_ = 0;
        max_texture_size_ = 4096;
        last_presented_target_ = -1;
    }

    std::mutex mutex_;
    std::condition_variable condition_;
    std::thread thread_;
    bool thread_started_ = false;
    bool stop_requested_ = false;

    ANativeWindow *pending_window_ = nullptr;
    bool surface_change_pending_ = false;
    bool detach_pending_ = false;
    bool invalidate_targets_pending_ = false;
    bool waiting_for_new_media_frame_ = false;
    bool surface_ready_ = false;
    bool last_attach_ok_ = false;
    uint64_t attach_serial_ = 0;
    uint64_t completed_attach_serial_ = 0;
    uint64_t detach_serial_ = 0;
    uint64_t completed_detach_serial_ = 0;

    int window_width_ = 1;
    int window_height_ = 1;
    Geometry geometry_;
    uint64_t geometry_serial_ = 1;
    uint64_t presented_geometry_serial_ = 0;
    // -1: none, 0: normal FBO, 1: detail FBO. Used to bridge target
    // switches with an immediate compositor-only frame.
    int last_presented_target_ = -1;
    int window_retry_count_ = 0;
    bool mpv_update_pending_ = false;
    bool mpv_render_pending_ = true;
    bool composite_pending_ = true;

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_surface_ = EGL_NO_SURFACE;
    EGLSurface window_surface_ = EGL_NO_SURFACE;
    EGLint native_visual_id_ = 0;
    ANativeWindow *window_ = nullptr;

    mpv_render_context *render_context_ = nullptr;
    RenderTarget normal_target_;
    RenderTarget detail_target_;
    int max_texture_size_ = 4096;

    GLuint program_ = 0;
    GLint texture_uniform_ = -1;
};

RenderApiRenderer g_renderer;

float finite_or(float value, float fallback)
{
    return std::isfinite(value) ? value : fallback;
}

} // namespace

extern "C" {
    jni_func(jboolean, attachSurface, jobject surface, jint width, jint height);
    jni_func(void, resizeSurface, jint width, jint height);
    jni_func(void, detachSurface);
    jni_func(jlong, beginNewMediaRenderState);
    jni_func(jlong, setRenderState,
             jint normal_width, jint normal_height,
             jint detail_width, jint detail_height,
             jboolean use_detail,
             jfloat left, jfloat top, jfloat right, jfloat bottom);
    jni_func(jlong, getPresentedRenderStateSerial);
}

jni_func(jboolean, attachSurface, jobject surface, jint width, jint height)
{
    CHECK_MPV_INIT();
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window)
        return JNI_FALSE;
    return g_renderer.attach(window, width, height) ? JNI_TRUE : JNI_FALSE;
}

jni_func(void, resizeSurface, jint width, jint height)
{
    CHECK_MPV_INIT();
    g_renderer.resize(width, height);
}

jni_func(void, detachSurface)
{
    if (!g_mpv)
        return;
    g_renderer.detach();
}

jni_func(jlong, beginNewMediaRenderState)
{
    if (!g_mpv)
        return 0;
    return static_cast<jlong>(g_renderer.begin_new_media());
}

jni_func(jlong, setRenderState,
         jint normal_width, jint normal_height,
         jint detail_width, jint detail_height,
         jboolean use_detail,
         jfloat left, jfloat top, jfloat right, jfloat bottom)
{
    if (!g_mpv)
        return 0;

    Geometry geometry;
    geometry.normal_w = std::max(1, static_cast<int>(normal_width));
    geometry.normal_h = std::max(1, static_cast<int>(normal_height));
    geometry.detail_w = std::max(1, static_cast<int>(detail_width));
    geometry.detail_h = std::max(1, static_cast<int>(detail_height));
    geometry.use_detail = use_detail == JNI_TRUE;
    geometry.left = finite_or(left, 0.0f);
    geometry.top = finite_or(top, 0.0f);
    geometry.right = finite_or(right, geometry.left + 1.0f);
    geometry.bottom = finite_or(bottom, geometry.top + 1.0f);
    if (geometry.right <= geometry.left)
        geometry.right = geometry.left + 1.0f;
    if (geometry.bottom <= geometry.top)
        geometry.bottom = geometry.top + 1.0f;
    return static_cast<jlong>(g_renderer.set_geometry(geometry));
}

jni_func(jlong, getPresentedRenderStateSerial)
{
    if (!g_mpv)
        return 0;
    return static_cast<jlong>(g_renderer.presented_geometry_serial());
}

void render_api_shutdown()
{
    g_renderer.shutdown();
}
