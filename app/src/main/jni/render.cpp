#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"

extern "C" {
    jni_func(void, attachSurface, jobject surface_);
    jni_func(void, detachSurface);
    jni_func(jboolean, setSurfaceBufferSize, jint width, jint height);
};

static jobject surface = NULL;
static ANativeWindow *native_window = NULL;

jni_func(void, attachSurface, jobject surface_) {
    CHECK_MPV_INIT();

    surface = env->NewGlobalRef(surface_);
    if (!surface)
        die("invalid surface provided");

    native_window = ANativeWindow_fromSurface(env, surface_);
    if (!native_window) {
        env->DeleteGlobalRef(surface);
        surface = NULL;
        die("could not acquire native window");
    }

    int64_t wid = reinterpret_cast<intptr_t>(surface);
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));
}

jni_func(jboolean, setSurfaceBufferSize, jint width, jint height) {
    CHECK_MPV_INIT();

    if (!native_window) {
        ALOGE("cannot resize a detached native window");
        return JNI_FALSE;
    }
    if (width <= 0 || height <= 0) {
        ALOGE("invalid native window size %dx%d", width, height);
        return JNI_FALSE;
    }

    // android-surface-size changes mpv's logical output dimensions, but the
    // producer side of BufferQueue must use the same geometry before mpv draws
    // the next frame. Otherwise an actively playing video can briefly render
    // the new rectangle into an old-sized buffer during zoom transitions.
    const int32_t format = ANativeWindow_getFormat(native_window);
    if (format < 0) {
        ALOGE("ANativeWindow_getFormat returned error %d", format);
        return JNI_FALSE;
    }

    const int result =
        ANativeWindow_setBuffersGeometry(native_window, width, height, format);
    if (result < 0) {
        ALOGE("ANativeWindow_setBuffersGeometry(%dx%d) returned error %d",
              width, height, result);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jni_func(void, detachSurface) {
    CHECK_MPV_INIT();

    int64_t wid = 0;
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));

    if (native_window) {
        ANativeWindow_release(native_window);
        native_window = NULL;
    }
    if (surface) {
        env->DeleteGlobalRef(surface);
        surface = NULL;
    }
}
