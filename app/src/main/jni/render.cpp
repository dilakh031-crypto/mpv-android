#include <jni.h>

#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"

extern "C" {
    jni_func(void, attachSurface, jobject surface_);
    jni_func(void, detachSurface);
};

static jobject surface;

// Called after mpv_terminate_destroy(). At that point mpv/VO can no longer use the native window,
// so deleting the JNI global reference cannot race EGL. This also prevents a leaked Surface when
// Java intentionally keeps the TextureView alive until mpv teardown is complete.
void release_surface_reference(JNIEnv *env)
{
    if (!surface)
        return;
    env->DeleteGlobalRef(surface);
    surface = NULL;
}

jni_func(void, attachSurface, jobject surface_) {
    CHECK_MPV_INIT();

    // Be defensive against an accidental double attach. Drop mpv's old wid before deleting the
    // global reference so the previous Java Surface can never be freed while mpv still points at it.
    if (surface) {
        ALOGV("attachSurface called while another surface is still attached; replacing it safely");
        int64_t no_wid = 0;
        int clear_result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &no_wid);
        if (clear_result < 0)
            ALOGE("mpv_set_option(wid=0) returned error %s", mpv_error_string(clear_result));
        env->DeleteGlobalRef(surface);
        surface = NULL;
    }

    surface = env->NewGlobalRef(surface_);
    if (!surface)
        die("invalid surface provided");
    int64_t wid = reinterpret_cast<intptr_t>(surface);
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));
}

jni_func(void, detachSurface) {
    CHECK_MPV_INIT();

    int64_t wid = 0;
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));

    release_surface_reference(env);
}
