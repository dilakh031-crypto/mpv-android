#!/bin/bash -e

. ../../include/path.sh

build=_build$ndk_suffix

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf $build
	exit 0
else
	exit 255
fi

# AudioTrack.pause() preserves queued audio, while flush() deliberately discards
# it. mpv's AudioTrack backend only exposes reset (pause + flush), so a normal
# player pause loses the 75-150 ms already handed to Android. Teach the backend
# to use its hardware pause path and retain the unwritten tail of a blocking
# AudioTrack.write() if pause interrupts that call.
if ! grep -Eq '^[[:space:]]*\.set_pause[[:space:]]*=[[:space:]]*set_pause,' \
	audio/out/ao_audiotrack.c; then
	patch -p1 --forward --batch <<'PATCH'
diff --git a/audio/out/ao_audiotrack.c b/audio/out/ao_audiotrack.c
--- a/audio/out/ao_audiotrack.c
+++ b/audio/out/ao_audiotrack.c
@@ -21,6 +21,8 @@
  * License along with mpv.  If not, see <http://www.gnu.org/licenses/>.
  */
 
+#include <string.h>
+
 #include "ao.h"
 #include "internal.h"
 #include "common/msg.h"
@@ -52,6 +54,7 @@ struct priv {
 
     void *chunk;
     int chunksize;
+    int pending_bytes;
     jbyteArray bytearray;
     jshortArray shortarray;
     jfloatArray floatarray;
@@ -579,14 +582,25 @@ static MP_THREAD_VOID ao_thread(void *arg)
             state = MP_JNI_CALL_INT(p->audiotrack, AudioTrack.getPlayState);
         }
         if (state == AudioTrack.PLAYSTATE_PLAYING) {
-            int read_samples = p->chunksize / ao->sstride;
-            int64_t ts = mp_time_ns();
-            ts += MP_TIME_S_TO_NS(read_samples / (double)(ao->samplerate));
-            ts += MP_TIME_S_TO_NS(AudioTrack_getLatency(ao));
-            int samples = ao_read_data(ao, &p->chunk, read_samples, ts, NULL, false, false);
-            int ret = AudioTrack_write(ao, samples * ao->sstride);
+            int bytes = p->pending_bytes;
+            if (!bytes) {
+                int read_samples = p->chunksize / ao->sstride;
+                int64_t ts = mp_time_ns();
+                ts += MP_TIME_S_TO_NS(read_samples / (double)(ao->samplerate));
+                ts += MP_TIME_S_TO_NS(AudioTrack_getLatency(ao));
+                int samples = ao_read_data(ao, &p->chunk, read_samples, ts,
+                                           NULL, false, false);
+                bytes = samples * ao->sstride;
+            }
+
+            int ret = AudioTrack_write(ao, bytes);
             if (ret >= 0) {
+                mp_assert(ret <= bytes);
+                mp_assert(ret % ao->sstride == 0);
                 p->written_frames += ret / ao->sstride;
+                p->pending_bytes = bytes - ret;
+                if (ret > 0 && p->pending_bytes > 0)
+                    memmove(p->chunk, (char *)p->chunk + ret, p->pending_bytes);
             } else if (ret == AudioManager.ERROR_DEAD_OBJECT) {
                 MP_WARN(ao, "AudioTrack.write failed with ERROR_DEAD_OBJECT. Recreating AudioTrack...\n");
                 if (AudioTrack_Recreate(ao) < 0) {
@@ -808,30 +822,54 @@ static void stop(struct ao *ao)
 
     JNIEnv *env = MP_JNI_GET_ENV(ao);
     MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
-    MP_JNI_EXCEPTION_LOG(ao);
+    if (MP_JNI_EXCEPTION_LOG(ao) < 0)
+        return;
+
+    // AudioTrack.pause() interrupts a blocking write. Wait for that write to
+    // return before flushing and discarding any unwritten tail retained by the
+    // audio thread.
+    mp_mutex_lock(&p->lock);
     MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.flush);
     MP_JNI_EXCEPTION_LOG(ao);
 
+    p->pending_bytes = 0;
     p->playhead_offset = 0;
     p->reset_pending = true;
     p->written_frames = 0;
     p->timestamp_fetched = 0;
     p->timestamp_set = false;
+    mp_mutex_unlock(&p->lock);
 }
 
-static void start(struct ao *ao)
+static bool set_pause(struct ao *ao, bool paused)
 {
     struct priv *p = ao->priv;
     if (!p->audiotrack) {
-        MP_ERR(ao, "AudioTrack does not exist to start!\n");
-        return;
+        MP_ERR(ao, "AudioTrack does not exist to %s!\n",
+               paused ? "pause" : "resume");
+        return false;
     }
 
+    // Do not take p->lock here. The audio thread holds it while blocked in
+    // AudioTrack.write(), and pause() is what interrupts that write.
     JNIEnv *env = MP_JNI_GET_ENV(ao);
-    MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
-    MP_JNI_EXCEPTION_LOG(ao);
+    if (paused)
+        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.pause);
+    else
+        MP_JNI_CALL_VOID(p->audiotrack, AudioTrack.play);
 
-    mp_cond_signal(&p->wakeup);
+    if (MP_JNI_EXCEPTION_LOG(ao) < 0)
+        return false;
+
+    if (!paused)
+        mp_cond_signal(&p->wakeup);
+
+    return true;
+}
+
+static void start(struct ao *ao)
+{
+    set_pause(ao, false);
 }
 
 #define OPT_BASE_STRUCT struct priv
@@ -843,6 +881,7 @@ const struct ao_driver audio_out_audiotrack = {
     .uninit    = uninit,
     .reset     = stop,
     .start     = start,
+    .set_pause = set_pause,
     .priv_size = sizeof(struct priv),
     .priv_defaults = &(const OPT_BASE_STRUCT) {
         .cfg_pcm_float = 1,
PATCH
fi

# Make subtitle seeking treat the primary and secondary tracks as one timeline.
# mpv exposes per-track seeking, so add a "both" mode which asks both tracks for
# their target and performs one seek to the closest result in the requested
# direction.
if ! grep -Eq '\{"both",[[:space:]]*2\}' player/command.c; then
	patch -p1 --forward --batch <<'PATCH'
diff --git a/player/command.c b/player/command.c
--- a/player/command.c
+++ b/player/command.c
@@ -6260,6 +6260,27 @@ static void cmd_playlist_play_index(void *p)
         mpctx->add_osd_seek_info |= OSD_SEEK_INFO_CURRENT_FILE;
 }
 
+static void queue_sub_seek(struct MPContext *mpctx, struct mp_cmd_ctx *cmd,
+                           double refpts, double target)
+{
+    // We can easily seek/step to the wrong subtitle line (because
+    // video frame PTS and sub PTS rarely match exactly).
+    // sub/sd_ass.c adds SUB_SEEK_OFFSET as a workaround, and we
+    // need an even bigger offset without a video.
+    if (!mpctx->current_track[0][STREAM_VIDEO] ||
+        mpctx->current_track[0][STREAM_VIDEO]->image) {
+        target += SUB_SEEK_WITHOUT_VIDEO_OFFSET - SUB_SEEK_OFFSET;
+    }
+    mark_seek(mpctx);
+    queue_seek(mpctx, MPSEEK_ABSOLUTE, target, MPSEEK_EXACT,
+               MPSEEK_FLAG_DELAY);
+    set_osd_function(mpctx, (target > refpts) ? OSD_FFW : OSD_REW);
+    if (cmd->seek_bar_osd)
+        mpctx->add_osd_seek_info |= OSD_SEEK_INFO_BAR;
+    if (cmd->seek_msg_osd)
+        mpctx->add_osd_seek_info |= OSD_SEEK_INFO_TEXT;
+}
+
 static void cmd_sub_step_seek(void *p)
 {
     struct mp_cmd_ctx *cmd = p;
@@ -6272,9 +6293,40 @@ static void cmd_sub_step_seek(void *p)
         return;
     }
 
+    double refpts = get_current_time(mpctx);
+    if (!step && track_ind == 2) {
+        if (refpts == MP_NOPTS_VALUE)
+            return;
+
+        int skip = cmd->args[0].v.i;
+        if (skip != -1 && skip != 1) {
+            cmd->success = false;
+            return;
+        }
+
+        double target = MP_NOPTS_VALUE;
+        for (int n = 0; n < 2; n++) {
+            struct track *track = mpctx->current_track[n][STREAM_SUB];
+            struct dec_sub *sub = track ? track->d_sub : NULL;
+            if (!sub)
+                continue;
+
+            double candidate[2] = {refpts, skip};
+            if (sub_control(sub, SD_CTRL_SUB_STEP, candidate) <= 0)
+                continue;
+
+            if (target == MP_NOPTS_VALUE ||
+                (skip > 0 ? candidate[0] < target : candidate[0] > target))
+                target = candidate[0];
+        }
+
+        if (target != MP_NOPTS_VALUE)
+            queue_sub_seek(mpctx, cmd, refpts, target);
+        return;
+    }
+
     struct track *track = mpctx->current_track[track_ind][STREAM_SUB];
     struct dec_sub *sub = track ? track->d_sub : NULL;
-    double refpts = get_current_time(mpctx);
     if (sub && refpts != MP_NOPTS_VALUE) {
         double a[2];
         a[0] = refpts;
@@ -6289,22 +6341,7 @@ static void cmd_sub_step_seek(void *p)
                     track_ind == 0 ? "sub-delay" : "secondary-sub-delay",
                     cmd->on_osd);
             } else {
-                // We can easily seek/step to the wrong subtitle line (because
-                // video frame PTS and sub PTS rarely match exactly).
-                // sub/sd_ass.c adds SUB_SEEK_OFFSET as a workaround, and we
-                // need an even bigger offset without a video.
-                if (!mpctx->current_track[0][STREAM_VIDEO] ||
-                    mpctx->current_track[0][STREAM_VIDEO]->image) {
-                    a[0] += SUB_SEEK_WITHOUT_VIDEO_OFFSET - SUB_SEEK_OFFSET;
-                }
-                mark_seek(mpctx);
-                queue_seek(mpctx, MPSEEK_ABSOLUTE, a[0], MPSEEK_EXACT,
-                           MPSEEK_FLAG_DELAY);
-                set_osd_function(mpctx, (a[0] > refpts) ? OSD_FFW : OSD_REW);
-                if (cmd->seek_bar_osd)
-                    mpctx->add_osd_seek_info |= OSD_SEEK_INFO_BAR;
-                if (cmd->seek_msg_osd)
-                    mpctx->add_osd_seek_info |= OSD_SEEK_INFO_TEXT;
+                queue_sub_seek(mpctx, cmd, refpts, a[0]);
             }
         }
     }
@@ -7554,7 +7591,8 @@ const struct mp_cmd_def mp_cmds[] = {
             {"skip", OPT_INT(v.i)},
             {"flags", OPT_CHOICE(v.i,
                 {"primary", 0},
-                {"secondary", 1}),
+                {"secondary", 1},
+                {"both", 2}),
                 OPTDEF_INT(0)},
         },
         .allow_auto_repeat = true,
PATCH
fi


# mpv-android native 60 Hz video transform fast path
#
# Gesture zoom is intentionally kept out of mpv's property system. Updating
# video-zoom/video-pan-x/video-pan-y independently makes the VO recalculate the
# video geometry several times for one Android display frame. Instead, expose a
# tiny private libmpv API which stores the newest transform atomically in the VO,
# coalesces intermediate touch samples, applies it on the VO thread immediately
# before rendering, and requests a retained-frame redraw. mpv's own VO loop then
# rate-limits those redraws to the display refresh rate.
python3 - <<'PY_MPV_ANDROID_ZOOM'
from pathlib import Path
import re


def fail(where, detail):
    raise SystemExit(f"mpv native zoom patch failed at {where}: {detail}")


def replace_once(text, old, new, where):
    count = text.count(old)
    if count != 1:
        fail(where, f"expected one exact match, found {count}")
    return text.replace(old, new, 1)

path = Path('include/mpv/client.h')
text = path.read_text()
if 'mpv_android_set_video_transform' not in text:
    needle = 'MPV_EXPORT void mpv_wakeup(mpv_handle *ctx);\n'
    addition = needle + r'''

/**
 * mpv-android private extension. Update the live gpu-next video transform as a
 * single operation without changing persistent mpv properties.
 */
MPV_EXPORT void mpv_android_set_video_transform(mpv_handle *ctx,
                                                double zoom,
                                                double pan_x,
                                                double pan_y);
'''
    text = replace_once(text, needle, addition, 'client.h declaration')
    path.write_text(text)

path = Path('video/out/vo.h')
text = path.read_text()
if 'VOCTRL_ANDROID_VIDEO_TRANSFORM' not in text:
    enum_re = re.compile(r'(\n\s*VOCTRL_SET_CLIPBOARD,\s*\n)(\};)')
    text, n = enum_re.subn(r'\1    VOCTRL_ANDROID_VIDEO_TRANSFORM,\n\2', text, count=1)
    if n != 1:
        fail('vo.h enum', f'expected one insertion point, found {n}')

if 'struct voctrl_android_video_transform' not in text:
    needle = '// VOCTRL_UPDATE_PLAYBACK_STATE\n'
    struct_def = r'''struct voctrl_android_video_transform {
    bool active;
    double zoom;
    double pan_x;
    double pan_y;
};

'''
    if needle not in text:
        fail('vo.h payload', 'playback-state marker not found')
    text = text.replace(needle, struct_def + needle, 1)

if 'vo_set_android_video_transform' not in text:
    needle = 'void vo_redraw(struct vo *vo);\n'
    decl = needle + r'''void vo_set_android_video_transform(
    struct vo *vo, const struct voctrl_android_video_transform *transform);
'''
    text = replace_once(text, needle, decl, 'vo.h producer declaration')
path.write_text(text)

path = Path('player/client.c')
text = path.read_text()
if 'mpv_android_set_video_transform' not in text:
    if '#include "video/out/vo.h"\n' not in text:
        needle = '#include "core.h"\n#include "client.h"\n'
        repl = '#include "core.h"\n#include "client.h"\n#include "video/out/vo.h"\n'
        text = replace_once(text, needle, repl, 'client.c include')

    marker = '// map client API types to internal types\n'
    function = r'''void mpv_android_set_video_transform(mpv_handle *ctx,
                                     double zoom,
                                     double pan_x,
                                     double pan_y)
{
    if (!ctx)
        return;

    struct voctrl_android_video_transform transform = {
        .active = zoom != 0.0 || pan_x != 0.0 || pan_y != 0.0,
        .zoom = zoom,
        .pan_x = pan_x,
        .pan_y = pan_y,
    };

    lock_core(ctx);
    struct vo *vo = ctx->mpctx->video_out;
    if (vo)
        vo_set_android_video_transform(vo, &transform);
    unlock_core(ctx);
}

'''
    if marker not in text:
        fail('client.c function', 'type-conversion marker not found')
    text = text.replace(marker, function + marker, 1)
    path.write_text(text)

path = Path('video/out/vo.c')
text = path.read_text()
if 'android_transform_pending' not in text:
    needle = '    bool request_redraw;            // redraw request from player to VO\n    bool want_redraw;               // redraw request from VO to player\n'
    repl = needle + r'''    bool android_transform_pending;
    struct voctrl_android_video_transform android_transform;
'''
    text = replace_once(text, needle, repl, 'vo.c state')

if 'apply_android_video_transform' not in text:
    needle = r'''static void wakeup_locked(struct vo *vo)
{
    struct vo_internal *in = vo->in;

    mp_cond_broadcast(&in->wakeup);
    if (vo->driver->wakeup)
        vo->driver->wakeup(vo);
    in->need_wakeup = true;
}
'''
    addition = needle + r'''

void vo_set_android_video_transform(
    struct vo *vo, const struct voctrl_android_video_transform *transform)
{
    if (!vo || !transform)
        return;

    struct vo_internal *in = vo->in;
    mp_mutex_lock(&in->lock);
    in->android_transform = *transform;
    in->android_transform_pending = true;
    in->request_redraw = true;
    in->want_redraw = false;
    wakeup_locked(vo);
    mp_mutex_unlock(&in->lock);
}

// VO-thread only. Multiple Android touch samples collapse to the newest one.
static void apply_android_video_transform(struct vo *vo)
{
    struct vo_internal *in = vo->in;
    struct voctrl_android_video_transform transform;
    bool pending;

    mp_mutex_lock(&in->lock);
    pending = in->android_transform_pending;
    if (pending) {
        transform = in->android_transform;
        in->android_transform_pending = false;
    }
    mp_mutex_unlock(&in->lock);

    if (pending && vo->driver->control)
        vo->driver->control(vo, VOCTRL_ANDROID_VIDEO_TRANSFORM, &transform);
}
'''
    text = replace_once(text, needle, addition, 'vo.c transform producer')

normal_draw = r'''        stats_time_start(in->stats, "video-draw");

        in->visible = vo->driver->draw_frame(vo, frame);
'''
if 'apply_android_video_transform(vo);\n\n        stats_time_start(in->stats, "video-draw");' not in text:
    normal_draw_new = r'''        apply_android_video_transform(vo);

        stats_time_start(in->stats, "video-draw");

        in->visible = vo->driver->draw_frame(vo, frame);
'''
    text = replace_once(text, normal_draw, normal_draw_new, 'vo.c normal draw')

redraw = r'''    mp_mutex_unlock(&in->lock);

    vo->driver->draw_frame(vo, frame);
    vo->driver->flip_page(vo);
'''
if 'apply_android_video_transform(vo);\n\n    vo->driver->draw_frame(vo, frame);' not in text:
    redraw_new = r'''    mp_mutex_unlock(&in->lock);

    apply_android_video_transform(vo);

    vo->driver->draw_frame(vo, frame);
    vo->driver->flip_page(vo);
'''
    text = replace_once(text, redraw, redraw_new, 'vo.c retained redraw')
path.write_text(text)

path = Path('video/out/vo_gpu_next.c')
text = path.read_text()
if 'android_transform_active' not in text:
    needle = '    bool frame_pending;\n    bool paused;\n\n    pl_options pars;\n'
    repl = r'''    bool frame_pending;
    bool paused;
    bool android_transform_active;
    double android_zoom;
    double android_pan_x;
    double android_pan_y;

    pl_options pars;
'''
    text = replace_once(text, needle, repl, 'gpu-next state')

if 'static void update_android_zoom_geometry' not in text:
    old = r'''static void resize(struct vo *vo)
{
    struct priv *p = vo->priv;
    struct mp_rect src, dst;
    struct mp_osd_res osd;
    vo_get_src_dst_rects(vo, &src, &dst, &osd);
    if (vo->dwidth && vo->dheight) {
        gpu_ctx_resize(p->context, vo->dwidth, vo->dheight);
        vo->want_redraw = true;
    }

    if (mp_rect_equals(&p->src, &src) &&
        mp_rect_equals(&p->dst, &dst) &&
        osd_res_equals(p->osd_res, osd))
        return;

    p->osd_sync++;
    p->osd_res = osd;
    p->src = src;
    p->dst = dst;
}
'''
    new = r'''static void update_android_zoom_geometry(struct vo *vo)
{
    struct priv *p = vo->priv;
    struct mp_rect src, dst;
    struct mp_osd_res osd;

    // vo_get_src_dst_rects() already contains mpv's canonical zoom/pan/aspect
    // calculations. Temporarily expose the live Android transform only on the
    // VO thread, then restore normal mpv options immediately afterwards.
    double old_zoom = vo->opts->zoom;
    double old_pan_x = vo->opts->pan_x;
    double old_pan_y = vo->opts->pan_y;
    if (p->android_transform_active) {
        vo->opts->zoom = p->android_zoom;
        vo->opts->pan_x = p->android_pan_x;
        vo->opts->pan_y = p->android_pan_y;
    }

    vo_get_src_dst_rects(vo, &src, &dst, &osd);

    vo->opts->zoom = old_zoom;
    vo->opts->pan_x = old_pan_x;
    vo->opts->pan_y = old_pan_y;

    if (mp_rect_equals(&p->src, &src) &&
        mp_rect_equals(&p->dst, &dst) &&
        osd_res_equals(p->osd_res, osd))
        return;
    p->osd_sync++;
    p->osd_res = osd;
    p->src = src;
    p->dst = dst;
}

static void resize(struct vo *vo)
{
    struct priv *p = vo->priv;
    if (vo->dwidth && vo->dheight) {
        gpu_ctx_resize(p->context, vo->dwidth, vo->dheight);
        vo->want_redraw = true;
    }
    update_android_zoom_geometry(vo);
}
'''
    text = replace_once(text, old, new, 'gpu-next geometry split')

if 'case VOCTRL_ANDROID_VIDEO_TRANSFORM:' not in text:
    needle = r'''    switch (request) {
    case VOCTRL_SET_PANSCAN:
'''
    repl = r'''    switch (request) {
    case VOCTRL_ANDROID_VIDEO_TRANSFORM: {
        const struct voctrl_android_video_transform *transform = data;
        p->android_transform_active = transform->active;
        p->android_zoom = transform->zoom;
        p->android_pan_x = transform->pan_x;
        p->android_pan_y = transform->pan_y;
        update_android_zoom_geometry(vo);
        return VO_TRUE;
    }
    case VOCTRL_SET_PANSCAN:
'''
    text = replace_once(text, needle, repl, 'gpu-next control')
path.write_text(text)

print('mpv-android native zoom fast path applied')
PY_MPV_ANDROID_ZOOM

unset CC CXX # meson wants these unset

meson setup $build --cross-file "$prefix_dir"/crossfile.txt \
	--default-library shared \
	-Diconv=disabled -Dlua=enabled \
	-Dlibmpv=true -Dcplayer=false \
	-Dmanpage-build=disabled

ninja -C $build -j$cores
if [ -f $build/libmpv.a ]; then
	echo >&2 "Meson fucked up, forcing rebuild."
	$0 clean
	exec $0 build
fi
DESTDIR="$prefix_dir" ninja -C $build install
