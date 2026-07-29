#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <string>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"

/*
 * mpv renders into an off-screen FBO owned by this thread. The TextureView's
 * BufferQueue therefore never changes size while a pinch or aspect transition
 * is visible. Zoom/pan is the final GPU sampling pass, and a new mpv geometry
 * is exposed only after its complete FBO has been rendered.
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
             jfloat fit_translation_x, jfloat fit_translation_y);
    jni_func(void, beginRenderTransaction);
    jni_func(void, endRenderTransaction);
};

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
};

struct RenderTarget {
    GLuint framebuffer;
    GLuint texture;
    int requested_width;
    int requested_height;
    int width;
    int height;
    float fit_scale_x;
    float fit_scale_y;
    float fit_translation_x;
    float fit_translation_y;
    bool valid;
    bool has_frame;
};

struct Renderer {
    pthread_mutex_t lock;
    pthread_cond_t wake;
    pthread_cond_t initialized;
    pthread_t thread;

    bool thread_active;
    bool ready;
    bool stop;
    bool update_pending;
    bool redraw_pending;
    bool force_nonblocking;
    int init_result;
    int hold_count;
    uint64_t state_serial;

    ANativeWindow *window;
    PresentationState state;

    EGLDisplay egl_display;
    EGLContext egl_context;
    EGLSurface egl_surface;
    EGLConfig egl_config;
    int native_format;
    int applied_window_width;
    int applied_window_height;

    mpv_render_context *mpv_renderer;

    GLuint program;
    GLint position_location;
    GLint texture_location;
    GLint output_size_location;
    GLint scale_location;
    GLint translation_location;

    Renderer()
        : thread_active(false),
          ready(false),
          stop(false),
          update_pending(false),
          redraw_pending(false),
          force_nonblocking(false),
          init_result(-1),
          hold_count(0),
          state_serial(0),
          window(NULL),
          egl_display(EGL_NO_DISPLAY),
          egl_context(EGL_NO_CONTEXT),
          egl_surface(EGL_NO_SURFACE),
          egl_config(NULL),
          native_format(0),
          applied_window_width(0),
          applied_window_height(0),
          mpv_renderer(NULL),
          program(0),
          position_location(-1),
          texture_location(-1),
          output_size_location(-1),
          scale_location(-1),
          translation_location(-1)
    {
        pthread_mutex_init(&lock, NULL);
        pthread_cond_init(&wake, NULL);
        pthread_cond_init(&initialized, NULL);
        std::memset(&thread, 0, sizeof(thread));
        std::memset(&state, 0, sizeof(state));
    }
};

static Renderer g_renderer;
static void *g_gles_library;

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
    return state;
}

static RenderTarget empty_target()
{
    RenderTarget target = {};
    return target;
}

static bool almost_equal(float a, float b)
{
    return std::fabs(a - b) <= 0.00001f;
}

static bool presentation_states_equal(const PresentationState &a,
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
           almost_equal(a.fit_translation_y, b.fit_translation_y);
}

static bool target_matches_state(const RenderTarget &target,
                                 const PresentationState &state)
{
    return target.valid &&
           target.requested_width == state.render_width &&
           target.requested_height == state.render_height &&
           almost_equal(target.fit_scale_x, state.fit_scale_x) &&
           almost_equal(target.fit_scale_y, state.fit_scale_y) &&
           almost_equal(target.fit_translation_x, state.fit_translation_x) &&
           almost_equal(target.fit_translation_y, state.fit_translation_y);
}

static bool state_uses_base_target(const PresentationState &state)
{
    return state.render_width == state.view_width &&
           state.render_height == state.view_height &&
           almost_equal(state.fit_scale_x, 1.0f) &&
           almost_equal(state.fit_scale_y, 1.0f) &&
           almost_equal(state.fit_translation_x, 0.0f) &&
           almost_equal(state.fit_translation_y, 0.0f);
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

    char log[1024] = {};
    GLsizei length = 0;
    glGetShaderInfoLog(shader, sizeof(log), &length, log);
    ALOGE("zoom compositor shader compilation failed: %s", log);
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

    static const char fragment_shader_body[] =
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
        "        gl_FragColor = texture2D(u_texture,\n"
        "            vec2(local.x, 1.0 - local.y));\n"
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
    fragment_shader += fragment_shader_body;

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

    renderer->program = glCreateProgram();
    glAttachShader(renderer->program, vertex);
    glAttachShader(renderer->program, fragment);
    glBindAttribLocation(renderer->program, 0, "a_position");
    glLinkProgram(renderer->program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(renderer->program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024] = {};
        GLsizei length = 0;
        glGetProgramInfoLog(renderer->program, sizeof(log), &length, log);
        ALOGE("zoom compositor program link failed: %s", log);
        glDeleteProgram(renderer->program);
        renderer->program = 0;
        return false;
    }

    renderer->position_location =
        glGetAttribLocation(renderer->program, "a_position");
    renderer->texture_location =
        glGetUniformLocation(renderer->program, "u_texture");
    renderer->output_size_location =
        glGetUniformLocation(renderer->program, "u_output_size");
    renderer->scale_location =
        glGetUniformLocation(renderer->program, "u_scale");
    renderer->translation_location =
        glGetUniformLocation(renderer->program, "u_translation");

    return renderer->position_location >= 0 &&
           renderer->texture_location >= 0 &&
           renderer->output_size_location >= 0 &&
           renderer->scale_location >= 0 &&
           renderer->translation_location >= 0;
}

static bool initialize_egl(Renderer *renderer)
{
    renderer->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (renderer->egl_display == EGL_NO_DISPLAY ||
        !eglInitialize(renderer->egl_display, NULL, NULL)) {
        ALOGE("EGL display initialization failed");
        return false;
    }

    if (!eglBindAPI(EGL_OPENGL_ES_API)) {
        ALOGE("EGL OpenGL ES binding failed");
        return false;
    }

    const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint config_count = 0;
    if (!eglChooseConfig(renderer->egl_display, config_attributes,
                         &renderer->egl_config, 1, &config_count) ||
        config_count != 1) {
        ALOGE("No suitable EGL configuration for the mpv compositor");
        return false;
    }

    if (!eglGetConfigAttrib(renderer->egl_display, renderer->egl_config,
                            EGL_NATIVE_VISUAL_ID, &renderer->native_format)) {
        ALOGE("Could not query EGL native visual format");
        return false;
    }

    PresentationState state;
    pthread_mutex_lock(&renderer->lock);
    state = renderer->state;
    pthread_mutex_unlock(&renderer->lock);
    ANativeWindow_setBuffersGeometry(
        renderer->window,
        std::max(state.view_width, 1),
        std::max(state.view_height, 1),
        renderer->native_format);
    renderer->applied_window_width = std::max(state.view_width, 1);
    renderer->applied_window_height = std::max(state.view_height, 1);

    const EGLint context_attributes[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE,
    };
    renderer->egl_context =
        eglCreateContext(renderer->egl_display, renderer->egl_config,
                         EGL_NO_CONTEXT, context_attributes);
    if (renderer->egl_context == EGL_NO_CONTEXT) {
        ALOGE("EGL context creation failed");
        return false;
    }

    renderer->egl_surface =
        eglCreateWindowSurface(renderer->egl_display, renderer->egl_config,
                               renderer->window, NULL);
    if (renderer->egl_surface == EGL_NO_SURFACE) {
        ALOGE("EGL window surface creation failed");
        return false;
    }

    if (!eglMakeCurrent(renderer->egl_display, renderer->egl_surface,
                        renderer->egl_surface, renderer->egl_context)) {
        ALOGE("Could not make the mpv EGL context current");
        return false;
    }

    eglSwapInterval(renderer->egl_display, 1);
    return create_compositor(renderer);
}

static bool initialize_mpv_renderer(Renderer *renderer)
{
    mpv_opengl_init_params gl_init = {
        get_proc_address,
        NULL,
    };
    int advanced_control = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE,
         const_cast<char *>(MPV_RENDER_API_TYPE_OPENGL)},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &gl_init},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced_control},
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

static void destroy_target(RenderTarget *target)
{
    if (target->framebuffer)
        glDeleteFramebuffers(1, &target->framebuffer);
    if (target->texture)
        glDeleteTextures(1, &target->texture);
    *target = empty_target();
}

static void retain_target(RenderTarget *cache, RenderTarget *target)
{
    destroy_target(cache);
    *cache = *target;
    cache->has_frame = false;
    *target = empty_target();
}

static bool allocate_target(const PresentationState &state,
                            RenderTarget *target)
{
    destroy_target(target);

    GLint max_texture_size = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &max_texture_size);
    max_texture_size = std::max(max_texture_size, 1);

    int requested_width = std::max(state.render_width, 1);
    int requested_height = std::max(state.render_height, 1);
    double limit = std::min(
        1.0,
        static_cast<double>(max_texture_size) /
            std::max(requested_width, requested_height));
    int width = std::max(
        1, static_cast<int>(std::floor(requested_width * limit)));
    int height = std::max(
        1, static_cast<int>(std::floor(requested_height * limit)));

    glGenTextures(1, &target->texture);
    glBindTexture(GL_TEXTURE_2D, target->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, NULL);

    glGenFramebuffers(1, &target->framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, target->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, target->texture, 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (!target->texture || !target->framebuffer ||
        status != GL_FRAMEBUFFER_COMPLETE) {
        ALOGE("Could not allocate mpv render FBO %dx%d (status 0x%x)",
              width, height, status);
        destroy_target(target);
        return false;
    }

    target->requested_width = requested_width;
    target->requested_height = requested_height;
    target->width = width;
    target->height = height;
    target->fit_scale_x = state.fit_scale_x;
    target->fit_scale_y = state.fit_scale_y;
    target->fit_translation_x = state.fit_translation_x;
    target->fit_translation_y = state.fit_translation_y;
    target->valid = true;
    target->has_frame = false;
    return true;
}

static bool render_mpv_frame(Renderer *renderer, RenderTarget *target,
                             bool nonblocking)
{
    glBindFramebuffer(GL_FRAMEBUFFER, target->framebuffer);
    glViewport(0, 0, target->width, target->height);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glUseProgram(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);

    mpv_opengl_fbo fbo = {
        static_cast<int>(target->framebuffer),
        target->width,
        target->height,
        0,
    };
    // This is an off-screen texture FBO, not the vertically inverted default
    // framebuffer. The compositor performs the final framebuffer conversion.
    int flip_y = 0;
    int block_for_target_time = 0;
    mpv_render_param params[5] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flip_y},
        {MPV_RENDER_PARAM_INVALID, NULL},
        {MPV_RENDER_PARAM_INVALID, NULL},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    if (nonblocking) {
        params[2] = {
            MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,
            &block_for_target_time,
        };
        params[3] = {MPV_RENDER_PARAM_INVALID, NULL};
    }

    int result =
        mpv_render_context_render(renderer->mpv_renderer, params);
    if (result < 0) {
        ALOGE("mpv FBO rendering failed: %s", mpv_error_string(result));
        return false;
    }

    target->has_frame = true;
    return true;
}

static bool composite_target(Renderer *renderer, const RenderTarget &target,
                             const PresentationState &state)
{
    if (!target.valid || !target.has_frame)
        return false;

    const int requested_window_width = std::max(state.view_width, 1);
    const int requested_window_height = std::max(state.view_height, 1);
    if (requested_window_width != renderer->applied_window_width ||
        requested_window_height != renderer->applied_window_height) {
        ANativeWindow_setBuffersGeometry(
            renderer->window,
            requested_window_width,
            requested_window_height,
            renderer->native_format);
        renderer->applied_window_width = requested_window_width;
        renderer->applied_window_height = requested_window_height;
    }

    EGLint output_width = 0;
    EGLint output_height = 0;
    eglQuerySurface(renderer->egl_display, renderer->egl_surface,
                    EGL_WIDTH, &output_width);
    eglQuerySurface(renderer->egl_display, renderer->egl_surface,
                    EGL_HEIGHT, &output_height);
    if (output_width <= 0 || output_height <= 0) {
        output_width = std::max(state.view_width, 1);
        output_height = std::max(state.view_height, 1);
    }

    const float final_scale_x =
        std::max(state.scale * target.fit_scale_x, 0.000001f);
    const float final_scale_y =
        std::max(state.scale * target.fit_scale_y, 0.000001f);
    const float translation_x =
        (state.translation_x +
         state.scale * target.fit_translation_x) /
        std::max(state.view_width, 1);
    const float translation_y =
        (state.translation_y +
         state.scale * target.fit_translation_y) /
        std::max(state.view_height, 1);

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

    glUseProgram(renderer->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, target.texture);
    glUniform1i(renderer->texture_location, 0);
    glUniform2f(renderer->output_size_location,
                static_cast<float>(output_width),
                static_cast<float>(output_height));
    glUniform2f(renderer->scale_location,
                final_scale_x, final_scale_y);
    glUniform2f(renderer->translation_location,
                translation_x, translation_y);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glEnableVertexAttribArray(renderer->position_location);
    glVertexAttribPointer(renderer->position_location, 2, GL_FLOAT,
                          GL_FALSE, 0, vertices);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(renderer->position_location);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);

    if (!eglSwapBuffers(renderer->egl_display, renderer->egl_surface)) {
        ALOGE("EGL swap failed with error 0x%x", eglGetError());
        return false;
    }
    return true;
}

static void render_update_callback(void *context)
{
    Renderer *renderer = static_cast<Renderer *>(context);
    pthread_mutex_lock(&renderer->lock);
    if (renderer->thread_active && !renderer->stop) {
        renderer->update_pending = true;
        pthread_cond_signal(&renderer->wake);
    }
    pthread_mutex_unlock(&renderer->lock);
}

static void signal_initialized(Renderer *renderer, int result)
{
    pthread_mutex_lock(&renderer->lock);
    renderer->init_result = result;
    renderer->ready = true;
    pthread_cond_broadcast(&renderer->initialized);
    pthread_mutex_unlock(&renderer->lock);
}

static void cleanup_renderer(Renderer *renderer, RenderTarget *target,
                             RenderTarget *cache)
{
    if (renderer->mpv_renderer) {
        mpv_render_context_set_update_callback(
            renderer->mpv_renderer, NULL, NULL);
        mpv_render_context_free(renderer->mpv_renderer);
        renderer->mpv_renderer = NULL;
    }

    if (renderer->egl_display != EGL_NO_DISPLAY &&
        renderer->egl_context != EGL_NO_CONTEXT) {
        destroy_target(target);
        destroy_target(cache);
        if (renderer->program) {
            glDeleteProgram(renderer->program);
            renderer->program = 0;
        }
    }

    if (renderer->egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(renderer->egl_display, EGL_NO_SURFACE,
                       EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (renderer->egl_surface != EGL_NO_SURFACE)
            eglDestroySurface(renderer->egl_display, renderer->egl_surface);
        if (renderer->egl_context != EGL_NO_CONTEXT)
            eglDestroyContext(renderer->egl_display, renderer->egl_context);
        eglTerminate(renderer->egl_display);
    }

    renderer->egl_display = EGL_NO_DISPLAY;
    renderer->egl_context = EGL_NO_CONTEXT;
    renderer->egl_surface = EGL_NO_SURFACE;
    renderer->egl_config = NULL;
    renderer->native_format = 0;
    renderer->applied_window_width = 0;
    renderer->applied_window_height = 0;

    if (renderer->window) {
        ANativeWindow_release(renderer->window);
        renderer->window = NULL;
    }
}

static void *render_thread_main(void *context)
{
    Renderer *renderer = static_cast<Renderer *>(context);
    RenderTarget current = empty_target();
    RenderTarget zoom_cache = empty_target();

    if (!initialize_egl(renderer) ||
        !initialize_mpv_renderer(renderer)) {
        cleanup_renderer(renderer, &current, &zoom_cache);
        signal_initialized(renderer, -1);
        return NULL;
    }

    mpv_render_context_set_update_callback(
        renderer->mpv_renderer, render_update_callback, renderer);
    signal_initialized(renderer, 0);

    uint64_t seen_state_serial = 0;
    bool frame_pending = true;

    while (true) {
        PresentationState state;
        uint64_t state_serial;
        bool update_pending;
        bool redraw_pending;
        bool force_nonblocking;
        int hold_count;

        pthread_mutex_lock(&renderer->lock);
        while (!renderer->stop &&
               !renderer->update_pending &&
               !renderer->redraw_pending &&
               renderer->state_serial == seen_state_serial) {
            pthread_cond_wait(&renderer->wake, &renderer->lock);
        }
        if (renderer->stop) {
            pthread_mutex_unlock(&renderer->lock);
            break;
        }

        state = renderer->state;
        state_serial = renderer->state_serial;
        update_pending = renderer->update_pending;
        redraw_pending = renderer->redraw_pending;
        force_nonblocking = renderer->force_nonblocking;
        hold_count = renderer->hold_count;
        renderer->update_pending = false;
        renderer->redraw_pending = false;
        renderer->force_nonblocking = false;
        pthread_mutex_unlock(&renderer->lock);

        if (update_pending) {
            uint64_t flags =
                mpv_render_context_update(renderer->mpv_renderer);
            if (flags & MPV_RENDER_UPDATE_FRAME)
                frame_pending = true;
        }
        if (redraw_pending)
            frame_pending = true;

        if (hold_count > 0) {
            seen_state_serial = state_serial;
            continue;
        }

        const bool state_changed =
            state_serial != seen_state_serial;
        const bool target_changed =
            !target_matches_state(current, state);
        bool rendered_mpv = false;

        if (target_changed) {
            /*
             * Keep the already GPU-resident frame responsive while mpv
             * prepares the new-resolution target. No readback and no bitmap
             * allocation occur on the UI thread.
             */
            if (current.has_frame && state_changed)
                composite_target(renderer, current, state);

            RenderTarget pending = empty_target();
            if (target_matches_state(zoom_cache, state)) {
                pending = zoom_cache;
                pending.has_frame = false;
                zoom_cache = empty_target();
            } else if (!allocate_target(state, &pending)) {
                seen_state_serial = state_serial;
                continue;
            }
            if (!render_mpv_frame(renderer, &pending, true)) {
                retain_target(&zoom_cache, &pending);
                seen_state_serial = state_serial;
                continue;
            }
            rendered_mpv = true;
            frame_pending = false;

            PresentationState latest;
            uint64_t latest_serial;
            int latest_hold;
            bool stopping;
            pthread_mutex_lock(&renderer->lock);
            latest = renderer->state;
            latest_serial = renderer->state_serial;
            latest_hold = renderer->hold_count;
            stopping = renderer->stop;
            pthread_mutex_unlock(&renderer->lock);

            if (stopping) {
                destroy_target(&pending);
                break;
            }
            if (latest_hold > 0 ||
                !target_matches_state(pending, latest)) {
                retain_target(&zoom_cache, &pending);
                continue;
            }

            RenderTarget previous = current;
            current = pending;
            pending = empty_target();
            state = latest;
            state_serial = latest_serial;

            // A completed source-detail FBO is expensive to allocate. Keep one
            // only while base rendering is active, so every later pinch can
            // reuse it without retaining multiple high-resolution targets.
            if (state_uses_base_target(state) &&
                !target_matches_state(previous, state)) {
                retain_target(&zoom_cache, &previous);
            } else {
                destroy_target(&previous);
                if (!state_uses_base_target(state))
                    destroy_target(&zoom_cache);
            }
        } else if (frame_pending || !current.has_frame) {
            if (render_mpv_frame(
                    renderer, &current, force_nonblocking)) {
                rendered_mpv = true;
                frame_pending = false;
            }
        }

        PresentationState latest;
        uint64_t latest_serial;
        int latest_hold;
        bool stopping;
        pthread_mutex_lock(&renderer->lock);
        latest = renderer->state;
        latest_serial = renderer->state_serial;
        latest_hold = renderer->hold_count;
        stopping = renderer->stop;
        pthread_mutex_unlock(&renderer->lock);

        if (stopping)
            break;
        if (latest_hold > 0) {
            seen_state_serial = latest_serial;
            continue;
        }
        if (!target_matches_state(current, latest))
            continue;

        if (composite_target(renderer, current, latest) &&
            rendered_mpv) {
            mpv_render_context_report_swap(renderer->mpv_renderer);
        }
        seen_state_serial = latest_serial;
    }

    cleanup_renderer(renderer, &current, &zoom_cache);
    return NULL;
}

jni_func(jboolean, attachSurface, jobject surface_, jint width, jint height)
{
    (void)obj;
    CHECK_MPV_INIT();
    if (!surface_)
        return JNI_FALSE;

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface_);
    if (!window)
        return JNI_FALSE;

    pthread_mutex_lock(&g_renderer.lock);
    if (g_renderer.thread_active) {
        pthread_mutex_unlock(&g_renderer.lock);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    g_renderer.window = window;
    g_renderer.state = base_state(width, height);
    g_renderer.state_serial++;
    g_renderer.stop = false;
    g_renderer.ready = false;
    g_renderer.update_pending = false;
    g_renderer.redraw_pending = true;
    g_renderer.force_nonblocking = true;
    g_renderer.init_result = -1;
    g_renderer.hold_count = 0;
    g_renderer.thread_active = true;

    if (pthread_create(&g_renderer.thread, NULL,
                       render_thread_main, &g_renderer) != 0) {
        g_renderer.thread_active = false;
        g_renderer.window = NULL;
        pthread_mutex_unlock(&g_renderer.lock);
        ANativeWindow_release(window);
        ALOGE("Could not create mpv render thread");
        return JNI_FALSE;
    }

    while (!g_renderer.ready)
        pthread_cond_wait(&g_renderer.initialized, &g_renderer.lock);
    const bool success = g_renderer.init_result == 0;
    pthread_mutex_unlock(&g_renderer.lock);

    if (!success) {
        pthread_join(g_renderer.thread, NULL);
        pthread_mutex_lock(&g_renderer.lock);
        g_renderer.thread_active = false;
        pthread_mutex_unlock(&g_renderer.lock);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

jni_func(void, detachSurface)
{
    (void)env;
    (void)obj;
    CHECK_MPV_INIT();

    pthread_mutex_lock(&g_renderer.lock);
    if (!g_renderer.thread_active) {
        pthread_mutex_unlock(&g_renderer.lock);
        return;
    }
    g_renderer.stop = true;
    pthread_cond_broadcast(&g_renderer.wake);
    pthread_mutex_unlock(&g_renderer.lock);

    pthread_join(g_renderer.thread, NULL);

    pthread_mutex_lock(&g_renderer.lock);
    g_renderer.thread_active = false;
    g_renderer.ready = false;
    g_renderer.stop = false;
    g_renderer.update_pending = false;
    g_renderer.redraw_pending = false;
    g_renderer.hold_count = 0;
    pthread_mutex_unlock(&g_renderer.lock);
}

jni_func(void, resizeRenderSurface, jint width, jint height)
{
    (void)env;
    (void)obj;
    const int safe_width = std::max(static_cast<int>(width), 1);
    const int safe_height = std::max(static_cast<int>(height), 1);

    pthread_mutex_lock(&g_renderer.lock);
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
    g_renderer.state_serial++;
    g_renderer.redraw_pending = true;
    g_renderer.force_nonblocking = true;
    pthread_cond_signal(&g_renderer.wake);
    pthread_mutex_unlock(&g_renderer.lock);
}

jni_func(void, setRenderState,
         jint render_width, jint render_height,
         jint view_width, jint view_height,
         jfloat scale, jfloat translation_x, jfloat translation_y,
         jfloat fit_scale_x, jfloat fit_scale_y,
         jfloat fit_translation_x, jfloat fit_translation_y)
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

    pthread_mutex_lock(&g_renderer.lock);
    if (presentation_states_equal(g_renderer.state, state)) {
        pthread_mutex_unlock(&g_renderer.lock);
        return;
    }
    g_renderer.state = state;
    g_renderer.state_serial++;
    pthread_cond_signal(&g_renderer.wake);
    pthread_mutex_unlock(&g_renderer.lock);
}

jni_func(void, beginRenderTransaction)
{
    (void)env;
    (void)obj;
    pthread_mutex_lock(&g_renderer.lock);
    g_renderer.hold_count++;
    pthread_mutex_unlock(&g_renderer.lock);
}

jni_func(void, endRenderTransaction)
{
    (void)env;
    (void)obj;
    pthread_mutex_lock(&g_renderer.lock);
    if (g_renderer.hold_count > 0)
        g_renderer.hold_count--;
    g_renderer.state_serial++;
    g_renderer.redraw_pending = true;
    g_renderer.force_nonblocking = true;
    pthread_cond_signal(&g_renderer.wake);
    pthread_mutex_unlock(&g_renderer.lock);
}
