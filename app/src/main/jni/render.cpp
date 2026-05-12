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

jni_func(void, attachSurface, jobject surface_) {
    CHECK_MPV_INIT();

    jobject old_surface = surface;
    surface = env->NewGlobalRef(surface_);
    if (!surface)
        die("invalid surface provided");

    int64_t wid = reinterpret_cast<intptr_t>(surface);
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));

    if (old_surface)
        env->DeleteGlobalRef(old_surface);
}

jni_func(void, detachSurface) {
    CHECK_MPV_INIT();

    int64_t wid = 0;
    int result = mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
         ALOGE("mpv_set_option(wid) returned error %s", mpv_error_string(result));

    if (surface) {
        env->DeleteGlobalRef(surface);
        surface = NULL;
    }
}
