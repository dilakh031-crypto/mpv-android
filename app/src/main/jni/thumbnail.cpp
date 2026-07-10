#include <stdlib.h>
#include <string>
#include <string.h>
#include <stdint.h>

#include <jni.h>
#include <android/bitmap.h>
#include <mpv/client.h>

extern "C" {
    #include <libswscale/swscale.h>
};

#include "jni_utils.h"
#include "globals.h"
#include "log.h"

extern "C" {
    jni_func(jobject, grabThumbnail, jint dimension);
    jni_func(jobject, grabVideoFrame, jint dimension);
};

static inline mpv_node make_node_str(const char *s)
{
    mpv_node r{};
    r.format = MPV_FORMAT_STRING;
    r.u.string = const_cast<char*>(s);
    return r;
}

static jobject grab_frame(JNIEnv *env, int dimension, bool crop_square)
{
    if (dimension <= 0)
        return NULL;

    mpv_node result{};
    {
        mpv_node c{}, c_args[2];
        mpv_node_list c_array{};
        c_args[0] = make_node_str("screenshot-raw");
        c_args[1] = make_node_str("video");
        c_array.num = 2;
        c_array.values = c_args;
        c.format = MPV_FORMAT_NODE_ARRAY;
        c.u.list = &c_array;
        if (mpv_command_node(g_mpv, &c, &result) < 0) {
            ALOGE("screenshot-raw command failed");
            return NULL;
        }
    }

    // Extract the raw frame returned by mpv.
    int w = 0, h = 0, stride = 0;
    bool format_ok = false;
    struct mpv_byte_array *data = NULL;
    do {
        if (result.format != MPV_FORMAT_NODE_MAP)
            break;
        for (int i = 0; i < result.u.list->num; i++) {
            std::string key(result.u.list->keys[i]);
            const mpv_node *val = &result.u.list->values[i];
            if (key == "w" || key == "h" || key == "stride") {
                if (val->format != MPV_FORMAT_INT64)
                    break;
                if (key == "w")
                    w = val->u.int64;
                else if (key == "h")
                    h = val->u.int64;
                else
                    stride = val->u.int64;
            } else if (key == "format") {
                if (val->format != MPV_FORMAT_STRING)
                    break;
                format_ok = !strcmp(val->u.string, "bgr0");
            } else if (key == "data") {
                if (val->format != MPV_FORMAT_BYTE_ARRAY)
                    break;
                data = val->u.ba;
            }
        }
    } while (0);
    if (!w || !h || !stride || !format_ok || !data) {
        ALOGE("extracting screenshot data failed");
        mpv_free_node_contents(&result);
        return NULL;
    }
    ALOGV("screenshot w:%d h:%d stride:%d", w, h, stride);

    int crop_left = 0, crop_top = 0;
    int source_w = w, source_h = h;
    if (crop_square) {
        if (w > h) {
            crop_left = (w - h) / 2;
            source_w = h;
        } else {
            crop_top = (h - w) / 2;
            source_h = w;
        }
    }

    uint8_t *source_data = reinterpret_cast<uint8_t*>(data->data);
    source_data += crop_left * sizeof(uint32_t);
    source_data += stride * crop_top;

    // Notification thumbnails remain square. Cover snapshots keep the complete frame and
    // only constrain the longest edge, so their original aspect ratio is preserved.
    int target_w;
    int target_h;
    if (crop_square) {
        target_w = dimension;
        target_h = dimension;
    } else if (source_w >= source_h) {
        target_w = source_w > dimension ? dimension : source_w;
        target_h = static_cast<int>((static_cast<int64_t>(source_h) * target_w + source_w / 2) / source_w);
        if (target_h < 1)
            target_h = 1;
    } else {
        target_h = source_h > dimension ? dimension : source_h;
        target_w = static_cast<int>((static_cast<int64_t>(source_w) * target_h + source_h / 2) / source_h);
        if (target_w < 1)
            target_w = 1;
    }

    struct SwsContext *ctx = sws_getContext(
        source_w, source_h, AV_PIX_FMT_BGR0,
        target_w, target_h, AV_PIX_FMT_RGB32,
        SWS_BICUBIC, NULL, NULL, NULL);
    if (!ctx) {
        mpv_free_node_contents(&result);
        return NULL;
    }

    const int pixel_count = target_w * target_h;
    jintArray arr = env->NewIntArray(pixel_count);
    if (!arr) {
        sws_freeContext(ctx);
        mpv_free_node_contents(&result);
        return NULL;
    }
    jint *scaled = env->GetIntArrayElements(arr, NULL);
    if (!scaled) {
        env->DeleteLocalRef(arr);
        sws_freeContext(ctx);
        mpv_free_node_contents(&result);
        return NULL;
    }

    uint8_t *src_p[4] = { source_data, NULL, NULL, NULL };
    uint8_t *dst_p[4] = { reinterpret_cast<uint8_t*>(scaled), NULL, NULL, NULL };
    int src_stride[4] = { stride, 0, 0, 0 };
    int dst_stride[4] = { static_cast<int>(sizeof(jint)) * target_w, 0, 0, 0 };
    sws_scale(ctx, src_p, src_stride, 0, source_h, dst_p, dst_stride);
    sws_freeContext(ctx);
    mpv_free_node_contents(&result);

    env->ReleaseIntArrayElements(arr, scaled, 0);

    jobject bitmap_config =
        env->GetStaticObjectField(android_graphics_Bitmap_Config, android_graphics_Bitmap_Config_ARGB_8888);
    jobject bitmap =
        env->CallStaticObjectMethod(android_graphics_Bitmap, android_graphics_Bitmap_createBitmap,
        arr, target_w, target_h, bitmap_config);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(bitmap_config);

    return bitmap;
}

jni_func(jobject, grabThumbnail, jint dimension) {
    (void)obj;
    CHECK_MPV_INIT();
    return grab_frame(env, dimension, true);
}

jni_func(jobject, grabVideoFrame, jint dimension) {
    (void)obj;
    CHECK_MPV_INIT();
    return grab_frame(env, dimension, false);
}
