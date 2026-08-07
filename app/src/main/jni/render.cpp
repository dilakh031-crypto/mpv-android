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

// Called after mpv has fully torn down its VO. Keeping this separate from
// detachSurface() lets destroy() release the JNI reference only after
// mpv_terminate_destroy() has stopped every renderer thread.
void release_surface_ref(JNIEnv *env) {
    if (!surface)
        return;
    env->DeleteGlobalRef(surface);
    surface = NULL;
}

jni_func(void, attachSurface, jobject surface_) {
    CHECK_MPV_INIT();

    if (surface) {
        ALOGE("attachSurface called while another surface is still attached");
        return;
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

    release_surface_ref(env);
}
