#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <string>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"

static const int64_t DOUBLE_BUFFER_PIXEL_LIMIT =
    INT64_C(12) * 1024 * 1024;

/*
 * Android owns the window Surface and the final presentation pass.
 *
 * mpv renders every frame into an off-screen FBO on a worker GL context, so
 * vf, GPU shaders, debanding, colorspace conversion and scale/dscale all run
 * before Android presents the image. A second shared GL context presents the
 * last complete FBO. Consequently, allocating or filtering a higher-detail
 * zoom target can never stall the first visible pinch frame.
 */

extern "C" {
    jni_func(jboolean, attachSurface, jobject surface_, jint width, jint height);
    jni_func(void, detachSurface);
    jni_func(void, resizeRenderSurface, jint width, jint height);
    jni_func(void, setRenderState,
             jint render_width, jint render_height,
             jint view_width, jint view_height,
             jfloat scale, jfloat translation_x, jfloat translation_y,
             jfloat fit_scale_x, jfloat fit_scale_y,
             jfloat fit_translation_x, jfloat fit_translation_y,
             jlong geometry_serial);
    jni_func(void, beginRenderTransaction);
    jni_func(void, endRenderTransaction);
}

struct PresentationState {
    int render_width;
    int render_height;
    int view_width;
    int view_height;
    float scale;
    float translation_x;
    float translation_y;
    float fit_scale_x;
    float fit_scale_y;
    float fit_translation_x;
    float fit_translation_y;
    uint64_t geometry_serial;
};

struct TargetSlot {
    GLuint framebuffer;
    GLuint texture;
    EGLSyncKHR presentation_fence;
    int width;
    int height;
    bool valid;
    bool has_frame;
};

struct TargetSet {
    TargetSlot slots[2];
    int front;
    int slot_count;
    int requested_width;
    int requested_height;
    PresentationState basis;
    uint64_t frame_serial;
    bool valid;
};

struct Renderer {
    pthread_mutex_t state_lock;
    pthread_cond_t state_changed;
    pthread_cond_t initialized;
    pthread_cond_t worker_initialized;
    pthread_mutex_t target_lock;

    pthread_t presenter_thread;
    pthread_t worker_thread;
    bool presenter_active;
    bool worker_active;
    bool ready;
    bool worker_ready;
    bool stop;
    int init_result;
    int worker_init_result;
    int hold_count;
    bool state_valid;
    bool mpv_update_pending;
    uint64_t pending_swap_reports;
    bool file_transition_hold;
    uint64_t state_serial;
    uint64_t present_serial;
    uint64_t previewed_state_serial;
    uint64_t produced_frame_serial;
    PresentationState state;

    ANativeWindow *window;
    EGLDisplay egl_display;
    EGLConfig egl_config;
    EGLContext presenter_context;
    EGLSurface window_surface;
    EGLContext worker_context;
    EGLSurface worker_surface;
    EGLint native_format;
    int applied_window_width;
    int applied_window_height;

    PFNEGLCREATESYNCKHRPROC create_sync;
    PFNEGLDESTROYSYNCKHRPROC destroy_sync;
    PFNEGLCLIENTWAITSYNCKHRPROC client_wait_sync;
    bool fence_sync_available;

    mpv_render_context *mpv_renderer;
    TargetSet current;

    GLuint compositor_program;
    GLint position_location;
    GLint texture_location;
    GLint output_size_location;
    GLint scale_location;
    GLint translation_location;

    Renderer()
        : presenter_active(false),
          worker_active(false),
          ready(false),
          worker_ready(false),
          stop(false),
          init_result(-1),
          worker_init_result(-1),
          hold_count(0),
          state_valid(false),
          mpv_update_pending(false),
          pending_swap_reports(0),
          file_transition_hold(false),
          state_serial(0),
          present_serial(0),
          previewed_state_serial(0),
          produced_frame_serial(0),
          window(NULL),
          egl_display(EGL_NO_DISPLAY),
          egl_config(NULL),
          presenter_context(EGL_NO_CONTEXT),
          window_surface(EGL_NO_SURFACE),
          worker_context(EGL_NO_CONTEXT),
          worker_surface(EGL_NO_SURFACE),
          native_format(0),
          applied_window_width(0),
          applied_window_height(0),
          create_sync(NULL),
          destroy_sync(NULL),
          client_wait_sync(NULL),
          fence_sync_available(false),
          mpv_renderer(NULL),
          compositor_program(0),
          position_location(-1),
          texture_location(-1),
          output_size_location(-1),
          scale_location(-1),
          translation_location(-1)
    {
        pthread_mutex_init(&state_lock, NULL);
        pthread_cond_init(&state_changed, NULL);
        pthread_cond_init(&initialized, NULL);
        pthread_cond_init(&worker_initialized, NULL);
        pthread_mutex_init(&target_lock, NULL);
        std::memset(&presenter_thread, 0, sizeof(presenter_thread));
        std::memset(&worker_thread, 0, sizeof(worker_thread));
        std::memset(&state, 0, sizeof(state));
        std::memset(&current, 0, sizeof(current));
    }
};

static Renderer g_renderer;
static void *g_gles_library;

void render_begin_file_transition()
{
    pthread_mutex_lock(&g_renderer.state_lock);
    if (!g_renderer.file_transition_hold) {
        g_renderer.file_transition_hold = true;
        g_renderer.hold_count++;
        g_renderer.state_serial++;
        pthread_cond_broadcast(&g_renderer.state_changed);
    }
    pthread_mutex_unlock(&g_renderer.state_lock);
}

void render_end_file_transition()
{
    pthread_mutex_lock(&g_renderer.state_lock);
    if (g_renderer.file_transition_hold) {
        g_renderer.file_transition_hold = false;
        if (g_renderer.hold_count > 0)
            g_renderer.hold_count--;

        // A new file always starts unzoomed on a view-sized target. mpv owns
        // the new file's aspect inside that target, so the first published
        // frame cannot inherit the previous file's producer geometry.
        const int view_width = std::max(g_renderer.state.view_width, 1);
        const int view_height = std::max(g_renderer.state.view_height, 1);
        g_renderer.state.render_width = view_width;
        g_renderer.state.render_height = view_height;
        g_renderer.state.scale = 1.0f;
        g_renderer.state.translation_x = 0.0f;
        g_renderer.state.translation_y = 0.0f;
        g_renderer.state.fit_scale_x = 1.0f;
        g_renderer.state.fit_scale_y = 1.0f;
        g_renderer.state.fit_translation_x = 0.0f;
        g_renderer.state.fit_translation_y = 0.0f;
        g_renderer.state.geometry_serial++;
        g_renderer.state_valid = true;
        g_renderer.state_serial++;
        g_renderer.present_serial++;
        pthread_cond_broadcast(&g_renderer.state_changed);
    }
    pthread_mutex_unlock(&g_renderer.state_lock);
}

static PresentationState base_state(int width, int height)
{
    PresentationState state = {};
    state.render_width = std::max(width, 1);
    state.render_height = std::max(height, 1);
    state.view_width = std::max(width, 1);
    state.view_height = std::max(height, 1);
    state.scale = 1.0f;
    state.fit_scale_x = 1.0f;
    state.fit_scale_y = 1.0f;
    state.geometry_serial = 1;
    return state;
}

static TargetSlot empty_slot()
{
    TargetSlot slot = {};
    slot.presentation_fence = EGL_NO_SYNC_KHR;
    return slot;
}

static TargetSet empty_target_set()
{
    TargetSet target = {};
    target.slots[0] = empty_slot();
    target.slots[1] = empty_slot();
    return target;
}

static bool almost_equal(float a, float b)
{
    return std::fabs(a - b) <= 0.00001f;
}

static bool states_equal(const PresentationState &a,
                         const PresentationState &b)
{
    return a.render_width == b.render_width &&
           a.render_height == b.render_height &&
           a.view_width == b.view_width &&
           a.view_height == b.view_height &&
           almost_equal(a.scale, b.scale) &&
           almost_equal(a.translation_x, b.translation_x) &&
           almost_equal(a.translation_y, b.translation_y) &&
           almost_equal(a.fit_scale_x, b.fit_scale_x) &&
           almost_equal(a.fit_scale_y, b.fit_scale_y) &&
           almost_equal(a.fit_translation_x, b.fit_translation_x) &&
           almost_equal(a.fit_translation_y, b.fit_translation_y) &&
           a.geometry_serial == b.geometry_serial;
}

static bool target_matches_state(const TargetSet &target,
                                 const PresentationState &state)
{
    return target.valid &&
           target.requested_width == state.render_width &&
           target.requested_height == state.render_height &&
           target.basis.geometry_serial == state.geometry_serial &&
           almost_equal(target.basis.fit_scale_x, state.fit_scale_x) &&
           almost_equal(target.basis.fit_scale_y, state.fit_scale_y) &&
           almost_equal(target.basis.fit_translation_x,
                        state.fit_translation_x) &&
           almost_equal(target.basis.fit_translation_y,
                        state.fit_translation_y);
}

static void *get_proc_address(void *, const char *name)
{
    void *address = reinterpret_cast<void *>(eglGetProcAddress(name));
    if (address)
        return address;

    if (!g_gles_library)
        g_gles_library = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    return g_gles_library ? dlsym(g_gles_library, name) : NULL;
}

static GLuint compile_shader(GLenum type, const char *source)
{
    GLuint shader = glCreateShader(type);
    if (!shader)
        return 0;

    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE)
        return shader;

    char message[1024] = {};
    GLsizei length = 0;
    glGetShaderInfoLog(shader, sizeof(message), &length, message);
    ALOGE("Android compositor shader compilation failed: %s", message);
    glDeleteShader(shader);
    return 0;
}

static bool create_compositor(Renderer *renderer)
{
    static const char vertex_shader[] =
        "attribute vec2 a_position;\n"
        "void main() {\n"
        "    gl_Position = vec4(a_position, 0.0, 1.0);\n"
        "}\n";
    static const char fragment_body[] =
        "uniform sampler2D u_texture;\n"
        "uniform vec2 u_output_size;\n"
        "uniform vec2 u_scale;\n"
        "uniform vec2 u_translation;\n"
        "void main() {\n"
        "    vec2 screen = vec2(\n"
        "        gl_FragCoord.x / u_output_size.x,\n"
        "        1.0 - gl_FragCoord.y / u_output_size.y);\n"
        "    vec2 local = (screen - u_translation) / u_scale;\n"
        "    if (local.x < 0.0 || local.x > 1.0 ||\n"
        "        local.y < 0.0 || local.y > 1.0) {\n"
        "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
        "    } else {\n"
        "        gl_FragColor = texture2D(\n"
        "            u_texture, vec2(local.x, 1.0 - local.y));\n"
        "    }\n"
        "}\n";

    GLint high_range[2] = {};
    GLint high_precision = 0;
    glGetShaderPrecisionFormat(
        GL_FRAGMENT_SHADER, GL_HIGH_FLOAT, high_range, &high_precision);
    std::string fragment_shader =
        high_precision > 0
            ? "precision highp float;\n"
            : "precision mediump float;\n";
    fragment_shader += fragment_body;

    GLuint vertex = compile_shader(GL_VERTEX_SHADER, vertex_shader);
    GLuint fragment =
        compile_shader(GL_FRAGMENT_SHADER, fragment_shader.c_str());
    if (!vertex || !fragment) {
        if (vertex)
            glDeleteShader(vertex);
        if (fragment)
            glDeleteShader(fragment);
        return false;
    }

    renderer->compositor_program = glCreateProgram();
    glAttachShader(renderer->compositor_program, vertex);
    glAttachShader(renderer->compositor_program, fragment);
    glBindAttribLocation(renderer->compositor_program, 0, "a_position");
    glLinkProgram(renderer->compositor_program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(renderer->compositor_program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char message[1024] = {};
        GLsizei length = 0;
        glGetProgramInfoLog(
            renderer->compositor_program,
            sizeof(message),
            &length,
            message);
        ALOGE("Android compositor program link failed: %s", message);
        glDeleteProgram(renderer->compositor_program);
        renderer->compositor_program = 0;
        return false;
    }

    renderer->position_location =
        glGetAttribLocation(renderer->compositor_program, "a_position");
    renderer->texture_location =
        glGetUniformLocation(renderer->compositor_program, "u_texture");
    renderer->output_size_location =
        glGetUniformLocation(renderer->compositor_program, "u_output_size");
    renderer->scale_location =
        glGetUniformLocation(renderer->compositor_program, "u_scale");
    renderer->translation_location =
        glGetUniformLocation(renderer->compositor_program, "u_translation");

    return renderer->position_location >= 0 &&
           renderer->texture_location >= 0 &&
           renderer->output_size_location >= 0 &&
           renderer->scale_location >= 0 &&
           renderer->translation_location >= 0;
}

static bool choose_egl_config(Renderer *renderer)
{
    static const EGLint color_sizes[][4] = {
        {8, 8, 8, 0},
        {8, 8, 8, 8},
        {5, 6, 5, 0},
    };

    for (size_t i = 0; i < sizeof(color_sizes) / sizeof(color_sizes[0]); i++) {
        const EGLint attributes[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, color_sizes[i][0],
            EGL_GREEN_SIZE, color_sizes[i][1],
            EGL_BLUE_SIZE, color_sizes[i][2],
            EGL_ALPHA_SIZE, color_sizes[i][3],
            EGL_NONE,
        };
        EGLint count = 0;
        EGLConfig config = NULL;
        if (eglChooseConfig(
                renderer->egl_display,
                attributes,
                &config,
                1,
                &count) &&
            count > 0) {
            renderer->egl_config = config;
            return true;
        }
    }

    ALOGE("No window+pbuffer OpenGL ES 2 EGL configuration is available");
    return false;
}

static void load_fence_sync(Renderer *renderer)
{
    const char *extensions =
        eglQueryString(renderer->egl_display, EGL_EXTENSIONS);
    if (!extensions || !std::strstr(extensions, "EGL_KHR_fence_sync"))
        return;

    renderer->create_sync =
        reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
            eglGetProcAddress("eglCreateSyncKHR"));
    renderer->destroy_sync =
        reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
            eglGetProcAddress("eglDestroySyncKHR"));
    renderer->client_wait_sync =
        reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
            eglGetProcAddress("eglClientWaitSyncKHR"));
    renderer->fence_sync_available =
        renderer->create_sync &&
        renderer->destroy_sync &&
        renderer->client_wait_sync;
}

static bool initialize_presenter_egl(Renderer *renderer)
{
    renderer->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (renderer->egl_display == EGL_NO_DISPLAY ||
        !eglInitialize(renderer->egl_display, NULL, NULL)) {
        ALOGE("EGL display initialization failed (0x%x)", eglGetError());
        return false;
    }

    if (!eglBindAPI(EGL_OPENGL_ES_API) || !choose_egl_config(renderer)) {
        ALOGE("Could not bind/configure OpenGL ES (0x%x)", eglGetError());
        return false;
    }

    if (!eglGetConfigAttrib(
            renderer->egl_display,
            renderer->egl_config,
            EGL_NATIVE_VISUAL_ID,
            &renderer->native_format)) {
        ALOGE("Could not query EGL native visual format (0x%x)", eglGetError());
        return false;
    }

    PresentationState initial;
    pthread_mutex_lock(&renderer->state_lock);
    initial = renderer->state;
    pthread_mutex_unlock(&renderer->state_lock);
    const int width = std::max(initial.view_width, 1);
    const int height = std::max(initial.view_height, 1);
    ANativeWindow_setBuffersGeometry(
        renderer->window, width, height, renderer->native_format);
    renderer->applied_window_width = width;
    renderer->applied_window_height = height;

    const EGLint context_attributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE,
    };
    renderer->presenter_context =
        eglCreateContext(
            renderer->egl_display,
            renderer->egl_config,
            EGL_NO_CONTEXT,
            context_attributes);
    if (renderer->presenter_context == EGL_NO_CONTEXT) {
        ALOGE("Presenter EGL context creation failed (0x%x)", eglGetError());
        return false;
    }

    renderer->window_surface =
        eglCreateWindowSurface(
            renderer->egl_display,
            renderer->egl_config,
            renderer->window,
            NULL);
    if (renderer->window_surface == EGL_NO_SURFACE) {
        ALOGE("EGL window surface creation failed (0x%x)", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(
            renderer->egl_display,
            renderer->window_surface,
            renderer->window_surface,
            renderer->presenter_context)) {
        ALOGE("Could not make presenter EGL context current (0x%x)",
              eglGetError());
        return false;
    }

    const EGLint pbuffer_attributes[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE,
    };
    renderer->worker_surface =
        eglCreatePbufferSurface(
            renderer->egl_display,
            renderer->egl_config,
            pbuffer_attributes);
    if (renderer->worker_surface == EGL_NO_SURFACE) {
        ALOGE("Worker EGL pbuffer creation failed (0x%x)", eglGetError());
        return false;
    }

    renderer->worker_context =
        eglCreateContext(
            renderer->egl_display,
            renderer->egl_config,
            renderer->presenter_context,
            context_attributes);
    if (renderer->worker_context == EGL_NO_CONTEXT) {
        ALOGE("Shared worker EGL context creation failed (0x%x)",
              eglGetError());
        return false;
    }

    eglSwapInterval(renderer->egl_display, 1);
    load_fence_sync(renderer);
    return create_compositor(renderer);
}

static void wait_for_slot(Renderer *renderer, TargetSlot *slot)
{
    if (slot->presentation_fence == EGL_NO_SYNC_KHR)
        return;

    if (renderer->fence_sync_available) {
        EGLint result = renderer->client_wait_sync(
            renderer->egl_display,
            slot->presentation_fence,
            EGL_SYNC_FLUSH_COMMANDS_BIT_KHR,
            EGL_FOREVER_KHR);
        if (result == EGL_FALSE)
            ALOGE("Waiting for compositor texture fence failed (0x%x)",
                  eglGetError());
        renderer->destroy_sync(
            renderer->egl_display, slot->presentation_fence);
    }
    slot->presentation_fence = EGL_NO_SYNC_KHR;
}

static void destroy_slot(Renderer *renderer, TargetSlot *slot)
{
    wait_for_slot(renderer, slot);
    if (slot->framebuffer)
        glDeleteFramebuffers(1, &slot->framebuffer);
    if (slot->texture)
        glDeleteTextures(1, &slot->texture);
    *slot = empty_slot();
}

static void destroy_target_set(Renderer *renderer, TargetSet *target)
{
    destroy_slot(renderer, &target->slots[0]);
    destroy_slot(renderer, &target->slots[1]);
    *target = empty_target_set();
}

static void clear_gl_errors()
{
    // Error flags are finite, but bound the loop defensively for broken
    // context-lost drivers.
    for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) {
    }
}

static bool allocate_slot(int width, int height, TargetSlot *slot)
{
    *slot = empty_slot();
    clear_gl_errors();

    glGenTextures(1, &slot->texture);
    glBindTexture(GL_TEXTURE_2D, slot->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA,
        width,
        height,
        0,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        NULL);
    GLenum texture_error = glGetError();

    glGenFramebuffers(1, &slot->framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, slot->framebuffer);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER,
        GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D,
        slot->texture,
        0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (!slot->texture ||
        !slot->framebuffer ||
        texture_error != GL_NO_ERROR ||
        status != GL_FRAMEBUFFER_COMPLETE) {
        if (slot->framebuffer)
            glDeleteFramebuffers(1, &slot->framebuffer);
        if (slot->texture)
            glDeleteTextures(1, &slot->texture);
        *slot = empty_slot();
        return false;
    }

    slot->width = width;
    slot->height = height;
    slot->valid = true;
    return true;
}

static bool allocate_target_set(const PresentationState &state,
                                TargetSet *target)
{
    *target = empty_target_set();

    GLint max_texture_size = 0;
    GLint max_renderbuffer_size = 0;
    GLint max_viewport_size[2] = {0, 0};
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_texture_size);
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &max_renderbuffer_size);
    glGetIntegerv(GL_MAX_VIEWPORT_DIMS, max_viewport_size);
    const int max_edge =
        std::max(
            1,
            std::min(
                std::max(max_texture_size, 1),
                std::max(max_renderbuffer_size, 1)));

    const int requested_width = std::max(state.render_width, 1);
    const int requested_height = std::max(state.render_height, 1);
    double limit = std::min(
        std::min(
            1.0,
            static_cast<double>(max_edge) /
                std::max(requested_width, requested_height)),
        std::min(
            static_cast<double>(std::max(max_viewport_size[0], 1)) /
                requested_width,
            static_cast<double>(std::max(max_viewport_size[1], 1)) /
                requested_height));

    int width =
        std::max(1, static_cast<int>(std::floor(requested_width * limit)));
    int height =
        std::max(1, static_cast<int>(std::floor(requested_height * limit)));

    bool allocated = false;
    for (int attempt = 0; attempt < 8; attempt++) {
        if (allocate_slot(width, height, &target->slots[0])) {
            allocated = true;
            break;
        }
        width = std::max(1, width * 3 / 4);
        height = std::max(1, height * 3 / 4);
    }
    if (!allocated) {
        ALOGE("Could not allocate an mpv FBO for requested size %dx%d",
              requested_width, requested_height);
        return false;
    }

    target->slot_count = 1;
    const int64_t pixels =
        static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (pixels <= DOUBLE_BUFFER_PIXEL_LIMIT &&
        allocate_slot(width, height, &target->slots[1])) {
        target->slot_count = 2;
    }

    target->front = 0;
    target->requested_width = requested_width;
    target->requested_height = requested_height;
    target->basis = state;
    target->frame_serial = 0;
    target->valid = true;

    ALOGV("mpv filtered target requested %dx%d, allocated %dx%d (%d slot%s)",
          requested_width,
          requested_height,
          width,
          height,
          target->slot_count,
          target->slot_count == 1 ? "" : "s");
    return true;
}

static bool initialize_mpv_renderer(Renderer *renderer)
{
    mpv_opengl_init_params gl_init = {
        get_proc_address,
        NULL,
    };
    mpv_render_param params[] = {
        {
            MPV_RENDER_PARAM_API_TYPE,
            const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL),
        },
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };

    int result =
        mpv_render_context_create(&renderer->mpv_renderer, g_mpv, params);
    if (result < 0) {
        ALOGE("mpv OpenGL render context creation failed: %s",
              mpv_error_string(result));
        renderer->mpv_renderer = NULL;
        return false;
    }
    return true;
}

static void reset_gl_for_mpv()
{
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glUseProgram(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
}

static bool render_mpv_frame(Renderer *renderer,
                             TargetSlot *slot,
                             bool nonblocking)
{
    wait_for_slot(renderer, slot);
    reset_gl_for_mpv();
    clear_gl_errors();

    mpv_opengl_fbo fbo = {
        static_cast<int>(slot->framebuffer),
        slot->width,
        slot->height,
        0,
    };
    int flip_y = 0;
    int block_for_target_time = nonblocking ? 0 : 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {
            MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,
            &block_for_target_time,
        },
        {MPV_RENDER_PARAM_INVALID, NULL},
    };

    int result =
        mpv_render_context_render(renderer->mpv_renderer, params);
    if (result < 0) {
        ALOGE("mpv filtered FBO rendering failed: %s",
              mpv_error_string(result));
        return false;
    }

    glFinish();
    const GLenum gl_error = glGetError();
    if (gl_error != GL_NO_ERROR) {
        ALOGE("mpv filtered FBO completed with GL error 0x%x", gl_error);
        return false;
    }
    slot->has_frame = true;
    return true;
}

static void render_update_callback(void *context)
{
    Renderer *renderer = static_cast<Renderer *>(context);
    pthread_mutex_lock(&renderer->state_lock);
    if (!renderer->stop) {
        renderer->mpv_update_pending = true;
        pthread_cond_broadcast(&renderer->state_changed);
    }
    pthread_mutex_unlock(&renderer->state_lock);
}

static bool publish_target(Renderer *renderer,
                           TargetSet *pending,
                           uint64_t expected_state_serial)
{
    TargetSet retired = empty_target_set();
    bool published = false;

    pthread_mutex_lock(&renderer->state_lock);
    if (!renderer->stop &&
        renderer->hold_count == 0 &&
        renderer->state_serial == expected_state_serial &&
        target_matches_state(*pending, renderer->state)) {
        pthread_mutex_lock(&renderer->target_lock);
        retired = renderer->current;
        renderer->current = *pending;
        *pending = empty_target_set();
        pthread_mutex_unlock(&renderer->target_lock);

        renderer->present_serial++;
        pthread_cond_broadcast(&renderer->state_changed);
        published = true;
    }
    pthread_mutex_unlock(&renderer->state_lock);

    if (published)
        destroy_target_set(renderer, &retired);
    return published;
}

static bool render_current_frame(Renderer *renderer,
                                 uint64_t expected_state_serial)
{
    pthread_mutex_lock(&renderer->target_lock);
    if (!renderer->current.valid) {
        pthread_mutex_unlock(&renderer->target_lock);
        return false;
    }

    const int target_index =
        renderer->current.slot_count > 1
            ? 1 - renderer->current.front
            : renderer->current.front;
    TargetSlot *slot = &renderer->current.slots[target_index];

    if (renderer->current.slot_count == 1) {
        pthread_mutex_unlock(&renderer->target_lock);
        return false;
    }

    const GLuint expected_framebuffer = slot->framebuffer;
    pthread_mutex_unlock(&renderer->target_lock);

    if (!render_mpv_frame(renderer, slot, false))
        return false;

    bool published = false;
    pthread_mutex_lock(&renderer->state_lock);
    const bool may_publish =
        renderer->hold_count == 0 &&
        renderer->state_serial == expected_state_serial;
    if (may_publish)
        pthread_mutex_lock(&renderer->target_lock);
    if (may_publish &&
        renderer->current.valid &&
        renderer->current.slot_count > 1 &&
        renderer->current.slots[target_index].framebuffer ==
            expected_framebuffer) {
        renderer->current.front = target_index;
        renderer->current.frame_serial =
            ++renderer->produced_frame_serial;
        published = true;
    }
    if (may_publish)
        pthread_mutex_unlock(&renderer->target_lock);
    pthread_mutex_unlock(&renderer->state_lock);
    return published;
}

static void signal_present(Renderer *renderer)
{
    pthread_mutex_lock(&renderer->state_lock);
    renderer->present_serial++;
    pthread_cond_broadcast(&renderer->state_changed);
    pthread_mutex_unlock(&renderer->state_lock);
}

static void signal_worker_initialized(Renderer *renderer, int result)
{
    pthread_mutex_lock(&renderer->state_lock);
    renderer->worker_init_result = result;
    renderer->worker_ready = true;
    pthread_cond_broadcast(&renderer->worker_initialized);
    pthread_mutex_unlock(&renderer->state_lock);
}

static void *worker_thread_main(void *context)
{
    Renderer *renderer = static_cast<Renderer *>(context);
    if (!eglMakeCurrent(
            renderer->egl_display,
            renderer->worker_surface,
            renderer->worker_surface,
            renderer->worker_context) ||
        !initialize_mpv_renderer(renderer)) {
        ALOGE("Could not initialize the filtered mpv worker (0x%x)",
              eglGetError());
        signal_worker_initialized(renderer, -1);
        return NULL;
    }

    mpv_render_context_set_update_callback(
        renderer->mpv_renderer, render_update_callback, renderer);
    signal_worker_initialized(renderer, 0);

    uint64_t seen_state_serial = 0;
    while (true) {
        PresentationState state;
        uint64_t state_serial;
        bool update_pending;
        uint64_t report_swaps;
        int hold_count;

        pthread_mutex_lock(&renderer->state_lock);
        while (!renderer->stop &&
               renderer->state_serial == seen_state_serial &&
               !renderer->mpv_update_pending &&
               renderer->pending_swap_reports == 0) {
            pthread_cond_wait(
                &renderer->state_changed, &renderer->state_lock);
        }
        if (renderer->stop) {
            pthread_mutex_unlock(&renderer->state_lock);
            break;
        }

        state = renderer->state;
        state_serial = renderer->state_serial;
        update_pending = renderer->mpv_update_pending;
        report_swaps = renderer->pending_swap_reports;
        hold_count = renderer->hold_count;
        renderer->mpv_update_pending = false;
        renderer->pending_swap_reports = 0;
        pthread_mutex_unlock(&renderer->state_lock);

        while (report_swaps > 0) {
            mpv_render_context_report_swap(renderer->mpv_renderer);
            report_swaps--;
        }

        uint64_t update_flags = 0;
        if (update_pending)
            update_flags =
                mpv_render_context_update(renderer->mpv_renderer);

        if (hold_count > 0) {
            seen_state_serial = state_serial;
            continue;
        }

        bool target_changed;
        bool preview_required;
        pthread_mutex_lock(&renderer->target_lock);
        target_changed =
            !target_matches_state(renderer->current, state);
        preview_required =
            target_changed &&
            renderer->current.valid &&
            renderer->current
                .slots[renderer->current.front].has_frame &&
            renderer->current.basis.geometry_serial ==
                state.geometry_serial;
        pthread_mutex_unlock(&renderer->target_lock);

        if (preview_required) {
            // A zoom quality target can be expensive on older Mali GPUs. Do
            // not let that work enter the GPU queue before the presenter has
            // shown the first transform from the already-complete texture.
            pthread_mutex_lock(&renderer->state_lock);
            while (!renderer->stop &&
                   renderer->hold_count == 0 &&
                   renderer->state_serial == state_serial &&
                   renderer->previewed_state_serial < state_serial) {
                pthread_cond_wait(
                    &renderer->state_changed, &renderer->state_lock);
            }
            const bool preview_ready =
                !renderer->stop &&
                renderer->hold_count == 0 &&
                renderer->state_serial == state_serial &&
                renderer->previewed_state_serial >= state_serial;
            pthread_mutex_unlock(&renderer->state_lock);
            if (!preview_ready)
                continue;
        }

        if (target_changed) {
            TargetSet pending = empty_target_set();
            if (allocate_target_set(state, &pending) &&
                render_mpv_frame(
                    renderer,
                    &pending.slots[pending.front],
                    true)) {
                pending.frame_serial =
                    ++renderer->produced_frame_serial;
                publish_target(
                    renderer, &pending, state_serial);
            }
            destroy_target_set(renderer, &pending);
        } else if (update_flags & MPV_RENDER_UPDATE_FRAME) {
            int slot_count = 0;
            pthread_mutex_lock(&renderer->target_lock);
            if (renderer->current.valid)
                slot_count = renderer->current.slot_count;
            pthread_mutex_unlock(&renderer->target_lock);

            if (slot_count > 1) {
                if (render_current_frame(renderer, state_serial))
                    signal_present(renderer);
            } else if (slot_count == 1) {
                // Huge still-image targets avoid a permanently allocated
                // second texture. Redraw into a temporary replacement so the
                // visible texture is never overwritten in-place.
                TargetSet replacement = empty_target_set();
                if (allocate_target_set(state, &replacement) &&
                    render_mpv_frame(
                        renderer,
                        &replacement.slots[replacement.front],
                        false)) {
                    replacement.frame_serial =
                        ++renderer->produced_frame_serial;
                    publish_target(
                        renderer,
                        &replacement,
                        state_serial);
                }
                destroy_target_set(renderer, &replacement);
            }
        }

        seen_state_serial = state_serial;
    }

    mpv_render_context_set_update_callback(
        renderer->mpv_renderer, NULL, NULL);
    mpv_render_context_free(renderer->mpv_renderer);
    renderer->mpv_renderer = NULL;

    TargetSet remaining = empty_target_set();
    pthread_mutex_lock(&renderer->target_lock);
    remaining = renderer->current;
    renderer->current = empty_target_set();
    pthread_mutex_unlock(&renderer->target_lock);
    destroy_target_set(renderer, &remaining);

    eglMakeCurrent(
        renderer->egl_display,
        EGL_NO_SURFACE,
        EGL_NO_SURFACE,
        EGL_NO_CONTEXT);
    return NULL;
}

static void update_slot_fence(Renderer *renderer, TargetSlot *slot)
{
    if (!renderer->fence_sync_available) {
        // Android 9 devices normally expose EGL_KHR_fence_sync. This fallback
        // keeps correctness on unusual drivers at the cost of a presentation
        // thread finish.
        glFinish();
        return;
    }

    if (slot->presentation_fence != EGL_NO_SYNC_KHR) {
        renderer->destroy_sync(
            renderer->egl_display, slot->presentation_fence);
        slot->presentation_fence = EGL_NO_SYNC_KHR;
    }
    slot->presentation_fence =
        renderer->create_sync(
            renderer->egl_display, EGL_SYNC_FENCE_KHR, NULL);
    if (slot->presentation_fence == EGL_NO_SYNC_KHR) {
        ALOGE("Could not create compositor texture fence (0x%x)",
              eglGetError());
        glFinish();
    } else {
        glFlush();
    }
}

static void ensure_window_geometry(Renderer *renderer,
                                   const PresentationState &state)
{
    const int width = std::max(state.view_width, 1);
    const int height = std::max(state.view_height, 1);
    if (width == renderer->applied_window_width &&
        height == renderer->applied_window_height)
        return;

    ANativeWindow_setBuffersGeometry(
        renderer->window, width, height, renderer->native_format);
    renderer->applied_window_width = width;
    renderer->applied_window_height = height;
}

static bool composite_locked(Renderer *renderer,
                             TargetSet *target,
                             const PresentationState &latest)
{
    if (!target->valid)
        return false;

    TargetSlot *slot = &target->slots[target->front];
    if (!slot->valid || !slot->has_frame)
        return false;

    PresentationState presentation = target->basis;
    if (target->basis.geometry_serial == latest.geometry_serial) {
        presentation.view_width = latest.view_width;
        presentation.view_height = latest.view_height;
        presentation.scale = latest.scale;
        presentation.translation_x = latest.translation_x;
        presentation.translation_y = latest.translation_y;
        // fit belongs to the texture. During a base<->zoom quality handoff,
        // the old view-sized and new media-sized textures use different fits
        // but represent exactly the same on-screen picture.
    }

    ensure_window_geometry(renderer, latest);
    EGLint output_width = 0;
    EGLint output_height = 0;
    eglQuerySurface(
        renderer->egl_display,
        renderer->window_surface,
        EGL_WIDTH,
        &output_width);
    eglQuerySurface(
        renderer->egl_display,
        renderer->window_surface,
        EGL_HEIGHT,
        &output_height);
    if (output_width <= 0 || output_height <= 0) {
        output_width = std::max(latest.view_width, 1);
        output_height = std::max(latest.view_height, 1);
    }

    const float final_scale_x =
        std::max(
            presentation.scale * target->basis.fit_scale_x,
            0.000001f);
    const float final_scale_y =
        std::max(
            presentation.scale * target->basis.fit_scale_y,
            0.000001f);
    const float normalized_translation_x =
        (
            presentation.translation_x +
            presentation.scale * target->basis.fit_translation_x
        ) /
        std::max(presentation.view_width, 1);
    const float normalized_translation_y =
        (
            presentation.translation_y +
            presentation.scale * target->basis.fit_translation_y
        ) /
        std::max(presentation.view_height, 1);

    static const GLfloat vertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f,
    };

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, output_width, output_height);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(renderer->compositor_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, slot->texture);
    glUniform1i(renderer->texture_location, 0);
    glUniform2f(
        renderer->output_size_location,
        static_cast<float>(output_width),
        static_cast<float>(output_height));
    glUniform2f(
        renderer->scale_location, final_scale_x, final_scale_y);
    glUniform2f(
        renderer->translation_location,
        normalized_translation_x,
        normalized_translation_y);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glEnableVertexAttribArray(renderer->position_location);
    glVertexAttribPointer(
        renderer->position_location,
        2,
        GL_FLOAT,
        GL_FALSE,
        0,
        vertices);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(renderer->position_location);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    update_slot_fence(renderer, slot);
    if (!eglSwapBuffers(renderer->egl_display, renderer->window_surface)) {
        ALOGE("Android compositor swap failed (0x%x)", eglGetError());
        return false;
    }
    return true;
}

static void draw_initial_black(Renderer *renderer,
                               const PresentationState &state)
{
    ensure_window_geometry(renderer, state);
    EGLint width = 0;
    EGLint height = 0;
    eglQuerySurface(
        renderer->egl_display,
        renderer->window_surface,
        EGL_WIDTH,
        &width);
    eglQuerySurface(
        renderer->egl_display,
        renderer->window_surface,
        EGL_HEIGHT,
        &height);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, std::max(width, 1), std::max(height, 1));
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(renderer->egl_display, renderer->window_surface);
}

static void signal_initialized(Renderer *renderer, int result)
{
    pthread_mutex_lock(&renderer->state_lock);
    renderer->init_result = result;
    renderer->ready = true;
    pthread_cond_broadcast(&renderer->initialized);
    pthread_mutex_unlock(&renderer->state_lock);
}

static void cleanup_presenter(Renderer *renderer)
{
    if (renderer->egl_display != EGL_NO_DISPLAY &&
        renderer->presenter_context != EGL_NO_CONTEXT) {
        eglMakeCurrent(
            renderer->egl_display,
            renderer->window_surface,
            renderer->window_surface,
            renderer->presenter_context);
        if (renderer->compositor_program)
            glDeleteProgram(renderer->compositor_program);
        renderer->compositor_program = 0;
        eglMakeCurrent(
            renderer->egl_display,
            EGL_NO_SURFACE,
            EGL_NO_SURFACE,
            EGL_NO_CONTEXT);
    }

    if (renderer->egl_display != EGL_NO_DISPLAY) {
        if (renderer->worker_context != EGL_NO_CONTEXT)
            eglDestroyContext(
                renderer->egl_display, renderer->worker_context);
        if (renderer->worker_surface != EGL_NO_SURFACE)
            eglDestroySurface(
                renderer->egl_display, renderer->worker_surface);
        if (renderer->window_surface != EGL_NO_SURFACE)
            eglDestroySurface(
                renderer->egl_display, renderer->window_surface);
        if (renderer->presenter_context != EGL_NO_CONTEXT)
            eglDestroyContext(
                renderer->egl_display, renderer->presenter_context);
        eglTerminate(renderer->egl_display);
    }

    renderer->egl_display = EGL_NO_DISPLAY;
    renderer->egl_config = NULL;
    renderer->presenter_context = EGL_NO_CONTEXT;
    renderer->window_surface = EGL_NO_SURFACE;
    renderer->worker_context = EGL_NO_CONTEXT;
    renderer->worker_surface = EGL_NO_SURFACE;
    renderer->native_format = 0;
    renderer->applied_window_width = 0;
    renderer->applied_window_height = 0;
    renderer->create_sync = NULL;
    renderer->destroy_sync = NULL;
    renderer->client_wait_sync = NULL;
    renderer->fence_sync_available = false;

    if (renderer->window) {
        ANativeWindow_release(renderer->window);
        renderer->window = NULL;
    }
}

static void *presenter_thread_main(void *context)
{
    Renderer *renderer = static_cast<Renderer *>(context);
    if (!initialize_presenter_egl(renderer)) {
        cleanup_presenter(renderer);
        signal_initialized(renderer, -1);
        return NULL;
    }

    pthread_mutex_lock(&renderer->state_lock);
    renderer->worker_ready = false;
    renderer->worker_init_result = -1;
    renderer->worker_active = true;
    if (pthread_create(
            &renderer->worker_thread,
            NULL,
            worker_thread_main,
            renderer) != 0) {
        renderer->worker_active = false;
        pthread_mutex_unlock(&renderer->state_lock);
        cleanup_presenter(renderer);
        signal_initialized(renderer, -1);
        return NULL;
    }
    while (!renderer->worker_ready)
        pthread_cond_wait(
            &renderer->worker_initialized, &renderer->state_lock);
    const bool worker_ok = renderer->worker_init_result == 0;
    pthread_mutex_unlock(&renderer->state_lock);

    if (!worker_ok) {
        pthread_join(renderer->worker_thread, NULL);
        renderer->worker_active = false;
        cleanup_presenter(renderer);
        signal_initialized(renderer, -1);
        return NULL;
    }

    signal_initialized(renderer, 0);

    uint64_t seen_state_serial = 0;
    uint64_t seen_present_serial = 0;
    uint64_t last_reported_frame_serial = 0;
    bool drew_black = false;

    while (true) {
        PresentationState state;
        uint64_t state_serial;
        uint64_t present_serial;
        int hold_count;

        pthread_mutex_lock(&renderer->state_lock);
        while (!renderer->stop &&
               renderer->state_serial == seen_state_serial &&
               renderer->present_serial == seen_present_serial) {
            pthread_cond_wait(
                &renderer->state_changed, &renderer->state_lock);
        }
        if (renderer->stop) {
            pthread_mutex_unlock(&renderer->state_lock);
            break;
        }
        state = renderer->state;
        state_serial = renderer->state_serial;
        present_serial = renderer->present_serial;
        hold_count = renderer->hold_count;
        pthread_mutex_unlock(&renderer->state_lock);

        if (hold_count > 0) {
            seen_state_serial = state_serial;
            seen_present_serial = present_serial;
            continue;
        }

        bool displayed = false;
        uint64_t displayed_frame_serial = 0;
        pthread_mutex_lock(&renderer->target_lock);
        if (renderer->current.valid) {
            displayed =
                composite_locked(renderer, &renderer->current, state);
            displayed_frame_serial = renderer->current.frame_serial;
        } else if (!drew_black) {
            draw_initial_black(renderer, state);
            drew_black = true;
        }
        pthread_mutex_unlock(&renderer->target_lock);

        if (displayed &&
            displayed_frame_serial != last_reported_frame_serial) {
            last_reported_frame_serial = displayed_frame_serial;
            pthread_mutex_lock(&renderer->state_lock);
            renderer->pending_swap_reports++;
            pthread_cond_broadcast(&renderer->state_changed);
            pthread_mutex_unlock(&renderer->state_lock);
        }

        if (displayed) {
            pthread_mutex_lock(&renderer->state_lock);
            renderer->previewed_state_serial =
                std::max(
                    renderer->previewed_state_serial,
                    state_serial);
            pthread_cond_broadcast(&renderer->state_changed);
            pthread_mutex_unlock(&renderer->state_lock);
        }

        seen_state_serial = state_serial;
        seen_present_serial = present_serial;
    }

    pthread_mutex_lock(&renderer->state_lock);
    pthread_cond_broadcast(&renderer->state_changed);
    pthread_mutex_unlock(&renderer->state_lock);
    if (renderer->worker_active) {
        pthread_join(renderer->worker_thread, NULL);
        renderer->worker_active = false;
    }

    cleanup_presenter(renderer);
    return NULL;
}

jni_func(jboolean, attachSurface,
         jobject surface_, jint width, jint height)
{
    (void)obj;
    CHECK_MPV_INIT();
    if (!surface_)
        return JNI_FALSE;

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface_);
    if (!window)
        return JNI_FALSE;

    pthread_mutex_lock(&g_renderer.state_lock);
    if (g_renderer.presenter_active) {
        pthread_mutex_unlock(&g_renderer.state_lock);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    g_renderer.window = window;
    if (!g_renderer.state_valid) {
        g_renderer.state = base_state(width, height);
        g_renderer.state_valid = true;
    } else {
        const bool base_target =
            g_renderer.state.render_width ==
                g_renderer.state.view_width &&
            g_renderer.state.render_height ==
                g_renderer.state.view_height &&
            almost_equal(g_renderer.state.fit_scale_x, 1.0f) &&
            almost_equal(g_renderer.state.fit_scale_y, 1.0f) &&
            almost_equal(g_renderer.state.fit_translation_x, 0.0f) &&
            almost_equal(g_renderer.state.fit_translation_y, 0.0f);
        g_renderer.state.view_width = std::max(static_cast<int>(width), 1);
        g_renderer.state.view_height = std::max(static_cast<int>(height), 1);
        if (base_target) {
            g_renderer.state.render_width =
                g_renderer.state.view_width;
            g_renderer.state.render_height =
                g_renderer.state.view_height;
        }
    }
    g_renderer.state_serial++;
    g_renderer.present_serial++;
    g_renderer.stop = false;
    g_renderer.ready = false;
    g_renderer.worker_ready = false;
    g_renderer.mpv_update_pending = false;
    g_renderer.pending_swap_reports = 0;
    g_renderer.previewed_state_serial = 0;
    g_renderer.produced_frame_serial = 0;
    g_renderer.init_result = -1;
    g_renderer.worker_init_result = -1;
    // START_FILE can arrive while Android is recreating the Surface. Preserve
    // that transition across the recreation so no pre-reconfigure frame is
    // allowed to reach the new window.
    g_renderer.hold_count =
        g_renderer.file_transition_hold ? 1 : 0;
    g_renderer.current = empty_target_set();
    g_renderer.presenter_active = true;

    if (pthread_create(
            &g_renderer.presenter_thread,
            NULL,
            presenter_thread_main,
            &g_renderer) != 0) {
        g_renderer.presenter_active = false;
        g_renderer.window = NULL;
        pthread_mutex_unlock(&g_renderer.state_lock);
        ANativeWindow_release(window);
        ALOGE("Could not create Android compositor thread");
        return JNI_FALSE;
    }

    while (!g_renderer.ready)
        pthread_cond_wait(
            &g_renderer.initialized, &g_renderer.state_lock);
    const bool success = g_renderer.init_result == 0;
    pthread_mutex_unlock(&g_renderer.state_lock);

    if (!success) {
        pthread_join(g_renderer.presenter_thread, NULL);
        pthread_mutex_lock(&g_renderer.state_lock);
        g_renderer.presenter_active = false;
        pthread_mutex_unlock(&g_renderer.state_lock);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

jni_func(void, detachSurface)
{
    (void)env;
    (void)obj;

    pthread_mutex_lock(&g_renderer.state_lock);
    if (!g_renderer.presenter_active) {
        pthread_mutex_unlock(&g_renderer.state_lock);
        return;
    }
    g_renderer.stop = true;
    pthread_cond_broadcast(&g_renderer.state_changed);
    pthread_mutex_unlock(&g_renderer.state_lock);

    pthread_join(g_renderer.presenter_thread, NULL);

    pthread_mutex_lock(&g_renderer.state_lock);
    g_renderer.presenter_active = false;
    g_renderer.worker_active = false;
    g_renderer.ready = false;
    g_renderer.worker_ready = false;
    g_renderer.stop = false;
    g_renderer.mpv_update_pending = false;
    g_renderer.pending_swap_reports = 0;
    // A file transition belongs to the mpv core rather than to one Android
    // Surface lifetime. UI render transactions cannot span this synchronous
    // detach, so only the file-transition hold is carried forward.
    g_renderer.hold_count =
        g_renderer.file_transition_hold ? 1 : 0;
    pthread_mutex_unlock(&g_renderer.state_lock);
}

jni_func(void, resizeRenderSurface, jint width, jint height)
{
    (void)env;
    (void)obj;
    const int safe_width = std::max(static_cast<int>(width), 1);
    const int safe_height = std::max(static_cast<int>(height), 1);

    pthread_mutex_lock(&g_renderer.state_lock);
    const int old_view_width = g_renderer.state.view_width;
    const int old_view_height = g_renderer.state.view_height;
    const bool base_target =
        g_renderer.state.render_width == old_view_width &&
        g_renderer.state.render_height == old_view_height &&
        almost_equal(g_renderer.state.fit_scale_x, 1.0f) &&
        almost_equal(g_renderer.state.fit_scale_y, 1.0f) &&
        almost_equal(g_renderer.state.fit_translation_x, 0.0f) &&
        almost_equal(g_renderer.state.fit_translation_y, 0.0f);

    g_renderer.state.view_width = safe_width;
    g_renderer.state.view_height = safe_height;
    if (base_target) {
        g_renderer.state.render_width = safe_width;
        g_renderer.state.render_height = safe_height;
    }
    g_renderer.state.geometry_serial++;
    g_renderer.state_valid = true;
    g_renderer.state_serial++;
    g_renderer.present_serial++;
    pthread_cond_broadcast(&g_renderer.state_changed);
    pthread_mutex_unlock(&g_renderer.state_lock);
}

jni_func(void, setRenderState,
         jint render_width, jint render_height,
         jint view_width, jint view_height,
         jfloat scale, jfloat translation_x, jfloat translation_y,
         jfloat fit_scale_x, jfloat fit_scale_y,
         jfloat fit_translation_x, jfloat fit_translation_y,
         jlong geometry_serial)
{
    (void)env;
    (void)obj;
    if (!std::isfinite(scale) ||
        !std::isfinite(translation_x) ||
        !std::isfinite(translation_y) ||
        !std::isfinite(fit_scale_x) ||
        !std::isfinite(fit_scale_y) ||
        !std::isfinite(fit_translation_x) ||
        !std::isfinite(fit_translation_y)) {
        return;
    }

    PresentationState state = {};
    state.render_width = std::max(static_cast<int>(render_width), 1);
    state.render_height = std::max(static_cast<int>(render_height), 1);
    state.view_width = std::max(static_cast<int>(view_width), 1);
    state.view_height = std::max(static_cast<int>(view_height), 1);
    state.scale = std::max(static_cast<float>(scale), 0.000001f);
    state.translation_x = translation_x;
    state.translation_y = translation_y;
    state.fit_scale_x =
        std::max(static_cast<float>(fit_scale_x), 0.000001f);
    state.fit_scale_y =
        std::max(static_cast<float>(fit_scale_y), 0.000001f);
    state.fit_translation_x = fit_translation_x;
    state.fit_translation_y = fit_translation_y;
    state.geometry_serial =
        geometry_serial > 0
            ? static_cast<uint64_t>(geometry_serial)
            : 1;

    pthread_mutex_lock(&g_renderer.state_lock);
    if (g_renderer.state_valid &&
        states_equal(g_renderer.state, state)) {
        pthread_mutex_unlock(&g_renderer.state_lock);
        return;
    }
    g_renderer.state = state;
    g_renderer.state_valid = true;
    g_renderer.state_serial++;
    g_renderer.present_serial++;
    pthread_cond_broadcast(&g_renderer.state_changed);
    pthread_mutex_unlock(&g_renderer.state_lock);
}

jni_func(void, beginRenderTransaction)
{
    (void)env;
    (void)obj;
    pthread_mutex_lock(&g_renderer.state_lock);
    g_renderer.hold_count++;
    pthread_cond_broadcast(&g_renderer.state_changed);
    pthread_mutex_unlock(&g_renderer.state_lock);
}

jni_func(void, endRenderTransaction)
{
    (void)env;
    (void)obj;
    pthread_mutex_lock(&g_renderer.state_lock);
    if (g_renderer.hold_count > 0)
        g_renderer.hold_count--;
    g_renderer.state_serial++;
    g_renderer.present_serial++;
    pthread_cond_broadcast(&g_renderer.state_changed);
    pthread_mutex_unlock(&g_renderer.state_lock);
}
