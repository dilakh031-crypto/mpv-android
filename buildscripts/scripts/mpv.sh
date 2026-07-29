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

# Aspect override travels through the decoder wrapper while panscan is consumed
# directly by the VO. Tag the decoded frame produced by the complete option
# transaction, wait asynchronously until that exact generation is presented,
# and return its Android BufferQueue timestamp to the app. This gives TextureView
# a verifiable handoff marker instead of relying on timing or a frame count.
if ! grep -Fq '"mpv-android-set-aspect-panscan"' player/command.c; then
	patch -p1 --forward --batch <<'PATCH'
diff --git a/filters/f_decoder_wrapper.c b/filters/f_decoder_wrapper.c
--- a/filters/f_decoder_wrapper.c
+++ b/filters/f_decoder_wrapper.c
@@ -95,6 +95,7 @@ static const struct m_sub_options adec_queue_conf = {
 struct dec_wrapper_opts {
     double movie_aspect;
     int aspect_method;
+    int64_t mpv_android_geometry_serial;
     double fps_override;
     bool correct_pts;
     int video_rotate;
@@ -130,6 +131,8 @@ const struct m_sub_options dec_wrapper_conf = {
         {"video-aspect-method", OPT_CHOICE(aspect_method,
             {"bitstream", 1}, {"container", 2}, {"ignore", 3}),
             .flags = UPDATE_IMGPAR},
+        {"mpv-android-geometry-serial",
+            OPT_INT64(mpv_android_geometry_serial), .flags = UPDATE_IMGPAR},
         {"vd-queue", OPT_SUBSTRUCT(vdec_queue_opts, vdec_queue_conf)},
         {"ad-queue", OPT_SUBSTRUCT(adec_queue_opts, adec_queue_conf)},
         {"video-reversal-buffer", OPT_BYTE_SIZE(video_reverse_size),
@@ -611,6 +614,7 @@ static void fix_image_params(struct priv *p,
         m.p_w = m.p_h = 1;
 
     m.stereo3d = p->codec->stereo_mode;
+    m.mpv_android_geometry_serial = opts->mpv_android_geometry_serial;
 
     if (!mp_rect_equals(&p->codec->crop, &(struct mp_rect){0})) {
         struct mp_rect crop = p->codec->crop;
diff --git a/player/command.c b/player/command.c
--- a/player/command.c
+++ b/player/command.c
@@ -127,6 +127,10 @@ struct command_ctx {
     int hwdec_osd_mode;
 
     double cached_window_scale;
+
+    struct mp_cmd_ctx *mpv_android_geometry_cmd;
+    uint64_t mpv_android_geometry_serial;
+    float mpv_android_geometry_panscan;
 };
 
 static const struct m_option script_props_type = {
@@ -5936,6 +5940,61 @@ static void cmd_set(void *p)
                         M_PROPERTY_SET_STRING, cmd->args[1].v.s);
 }
 
+static void cmd_mpv_android_set_aspect_panscan(void *p)
+{
+    struct mp_cmd_ctx *cmd = p;
+    struct MPContext *mpctx = cmd->mpctx;
+    struct command_ctx *ctx = mpctx->command_ctx;
+
+    if (ctx->mpv_android_geometry_cmd || !mpctx->video_out ||
+        !mpctx->vo_chain)
+    {
+        cmd->success = false;
+        mp_cmd_ctx_complete(cmd);
+        return;
+    }
+
+    int aspect_result =
+        mp_property_do("file-local-options/video-aspect-override",
+                       M_PROPERTY_SET_STRING, cmd->args[0].v.s, mpctx);
+    int panscan_result =
+        mp_property_do("file-local-options/panscan",
+                       M_PROPERTY_SET_STRING, cmd->args[1].v.s, mpctx);
+    if (aspect_result <= 0 || panscan_result <= 0) {
+        cmd->success = false;
+        mp_cmd_ctx_complete(cmd);
+        return;
+    }
+
+    /*
+     * video-aspect-override is applied by the decoder wrapper while panscan is
+     * consumed directly by the VO. Mark the decoder output only after both
+     * setters succeeded, then keep this asynchronous command pending until the
+     * VO reports that it actually presented the marked frame with the matching
+     * panscan value. This crosses the real pipeline boundary instead of waiting
+     * an assumed number of frames.
+     */
+    if (ctx->mpv_android_geometry_serial >= INT64_MAX)
+        ctx->mpv_android_geometry_serial = 0;
+    int64_t serial = ++ctx->mpv_android_geometry_serial;
+    int serial_result =
+        mp_property_do("file-local-options/mpv-android-geometry-serial",
+                       M_PROPERTY_SET, &serial, mpctx);
+    float panscan = 0;
+    int read_result =
+        mp_property_do("panscan", M_PROPERTY_GET, &panscan, mpctx);
+    if (serial_result <= 0 || read_result <= 0) {
+        cmd->success = false;
+        mp_cmd_ctx_complete(cmd);
+        return;
+    }
+
+    cmd->completed = false;
+    ctx->mpv_android_geometry_cmd = cmd;
+    ctx->mpv_android_geometry_panscan = panscan;
+    mp_wakeup_core(mpctx);
+}
+
 static void cmd_del(void *p)
 {
     struct mp_cmd_ctx *cmd = p;
@@ -7705,6 +7764,10 @@ const struct mp_cmd_def mp_cmds[] = {
     },
 
     { "set", cmd_set, {{"name", OPT_STRING(v.s)}, {"value", OPT_STRING(v.s)}}},
+    { "mpv-android-set-aspect-panscan", cmd_mpv_android_set_aspect_panscan, {
+        {"aspect", OPT_STRING(v.s)},
+        {"panscan", OPT_STRING(v.s)},
+    }, .exec_async = true, .can_abort = true, .abort_on_playback_end = true},
     { "del", cmd_del, {{"name", OPT_STRING(v.s)}}},
     { "change-list", cmd_change_list, { {"name", OPT_STRING(v.s)},
                                         {"operation", OPT_STRING(v.s)},
@@ -7902,6 +7965,12 @@ void command_uninit(struct MPContext *mpctx)
     struct command_ctx *ctx = mpctx->command_ctx;
 
     mp_assert(!ctx->cache_dump_cmd); // closing the demuxer must have aborted it
+    if (ctx->mpv_android_geometry_cmd) {
+        struct mp_cmd_ctx *cmd = ctx->mpv_android_geometry_cmd;
+        ctx->mpv_android_geometry_cmd = NULL;
+        cmd->success = false;
+        mp_cmd_ctx_complete(cmd);
+    }
 
     overlay_uninit(mpctx);
     ao_hotplug_destroy(ctx->hotplug);
@@ -8036,6 +8105,27 @@ void handle_command_updates(struct MPContext *mpctx)
 {
     struct command_ctx *ctx = mpctx->command_ctx;
 
+    struct mp_cmd_ctx *geometry_cmd = ctx->mpv_android_geometry_cmd;
+    if (geometry_cmd) {
+        bool aborted = mp_cancel_test(geometry_cmd->abort->cancel);
+        int64_t presentation_time = 0;
+        bool presented =
+            vo_mpv_android_geometry_presented(
+                mpctx->video_out,
+                ctx->mpv_android_geometry_serial,
+                ctx->mpv_android_geometry_panscan,
+                &presentation_time);
+        if (aborted || presented || !mpctx->video_out) {
+            ctx->mpv_android_geometry_cmd = NULL;
+            geometry_cmd->success = presented && !aborted;
+            if (geometry_cmd->success) {
+                node_init(&geometry_cmd->result, MPV_FORMAT_INT64, NULL);
+                geometry_cmd->result.u.int64 = presentation_time;
+            }
+            mp_cmd_ctx_complete(geometry_cmd);
+        }
+    }
+
     // This is a bit messy: ao_hotplug wakes up the player, and then we have
     // to recheck the state. Then the client(s) will read the property.
     if (ctx->hotplug && ao_hotplug_check_update(ctx->hotplug))
diff --git a/video/mp_image.h b/video/mp_image.h
--- a/video/mp_image.h
+++ b/video/mp_image.h
@@ -63,6 +63,12 @@ struct mp_image_params {
     int rotate;
     enum mp_stereo3d_mode stereo3d; // image is encoded with this mode
     struct mp_rect crop;        // crop applied on image
+
+    // App-private generation carried from the decoder wrapper to the VO. It is
+    // deliberately excluded from mp_image_params_equal(): changing the marker
+    // must not reconfigure the VO, it only identifies which option transaction
+    // produced a presented frame.
+    uint64_t mpv_android_geometry_serial;
 };
 
 /* Memory management:
diff --git a/video/out/opengl/context_android.c b/video/out/opengl/context_android.c
--- a/video/out/opengl/context_android.c
+++ b/video/out/opengl/context_android.c
@@ -17,6 +17,7 @@
 
 #include <EGL/egl.h>
 #include <EGL/eglext.h>
+#include <time.h>
 
 #include "video/out/android_common.h"
 #include "egl_helpers.h"
@@ -28,12 +29,23 @@ struct priv {
     EGLDisplay egl_display;
     EGLContext egl_context;
     EGLSurface egl_surface;
+    PFNEGLPRESENTATIONTIMEANDROIDPROC presentation_time;
 };
 
 static void android_swap_buffers(struct ra_ctx *ctx)
 {
     struct priv *p = ctx->priv;
+    ctx->vo->mpv_android_presentation_time = 0;
+    if (ctx->vo->mpv_android_tag_next_frame && p->presentation_time) {
+        struct timespec now = {0};
+        clock_gettime(CLOCK_MONOTONIC, &now);
+        int64_t timestamp =
+            (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
+        if (p->presentation_time(p->egl_display, p->egl_surface, timestamp))
+            ctx->vo->mpv_android_presentation_time = timestamp;
+    }
-    eglSwapBuffers(p->egl_display, p->egl_surface);
+    if (!eglSwapBuffers(p->egl_display, p->egl_surface))
+        ctx->vo->mpv_android_presentation_time = 0;
 }
 
 static void android_uninit(struct ra_ctx *ctx)
@@ -89,6 +101,9 @@ static bool android_init(struct ra_ctx *ctx)
     }
 
     mpegl_load_functions(&p->gl, ctx->log);
+    p->presentation_time =
+        (PFNEGLPRESENTATIONTIMEANDROIDPROC)
+            eglGetProcAddress("eglPresentationTimeANDROID");
 
     struct ra_ctx_params params = {
         .swap_buffers = android_swap_buffers,
diff --git a/video/out/vo.c b/video/out/vo.c
--- a/video/out/vo.c
+++ b/video/out/vo.c
@@ -172,6 +172,10 @@ struct vo_internal {
     int req_frames;                 // VO's requested value of num_frames
     uint64_t current_frame_id;
 
+    uint64_t mpv_android_geometry_serial;
+    float mpv_android_panscan;
+    int64_t mpv_android_presentation_time;
+
     double display_fps;
     double reported_display_fps;
 
@@ -918,6 +922,9 @@ static bool render_frame(struct vo *vo)
     struct vo_internal *in = vo->in;
     struct vo_frame *frame = NULL;
     bool more_frames = false;
+    bool geometry_changed = false;
+    uint64_t geometry_serial = 0;
+    float geometry_panscan = 0;
 
     update_display_fps(vo);
 
@@ -999,6 +1006,15 @@ static bool render_frame(struct vo *vo)
         // timer instead, but possibly benefits from preparing a frame early.
         bool can_queue = !in->frame_queued &&
             (in->current_frame->num_vsyncs < 1 || !use_vsync);
+        if (frame->current) {
+            geometry_serial =
+                frame->current->params.mpv_android_geometry_serial;
+            geometry_panscan = vo->opts->panscan;
+            geometry_changed =
+                geometry_serial != in->mpv_android_geometry_serial ||
+                geometry_panscan != in->mpv_android_panscan;
+        }
+        vo->mpv_android_tag_next_frame = geometry_changed;
         mp_mutex_unlock(&in->lock);
 
         if (can_queue)
@@ -1032,6 +1048,15 @@ static bool render_frame(struct vo *vo)
         mp_mutex_lock(&in->lock);
         in->dropped_frame = prev_drop_count < vo->in->drop_count;
         in->rendering = false;
+        vo->mpv_android_tag_next_frame = false;
+
+        if (geometry_changed) {
+            in->mpv_android_geometry_serial = geometry_serial;
+            in->mpv_android_panscan = geometry_panscan;
+            in->mpv_android_presentation_time =
+                vo->mpv_android_presentation_time;
+            wakeup_core(vo);
+        }
 
         update_vsync_timing_after_swap(vo, &vsync);
     }
@@ -1098,15 +1123,58 @@ static void do_redraw(struct vo *vo)
     frame->still = true;
     frame->pts = 0;
     frame->duration = -1;
+    uint64_t geometry_serial = 0;
+    float geometry_panscan = 0;
+    bool geometry_changed = false;
+    if (frame->current) {
+        geometry_serial =
+            frame->current->params.mpv_android_geometry_serial;
+        geometry_panscan = vo->opts->panscan;
+        geometry_changed =
+            geometry_serial != in->mpv_android_geometry_serial ||
+            geometry_panscan != in->mpv_android_panscan;
+    }
+    vo->mpv_android_tag_next_frame = geometry_changed;
     mp_mutex_unlock(&in->lock);
 
     vo->driver->draw_frame(vo, frame);
     vo->driver->flip_page(vo);
+    vo->mpv_android_tag_next_frame = false;
+
+    if (geometry_changed) {
+        mp_mutex_lock(&in->lock);
+        in->mpv_android_geometry_serial = geometry_serial;
+        in->mpv_android_panscan = geometry_panscan;
+        in->mpv_android_presentation_time =
+            vo->mpv_android_presentation_time;
+        mp_mutex_unlock(&in->lock);
+    }
+    if (geometry_changed)
+        wakeup_core(vo);
 
     if (frame != &dummy && !(vo->driver->caps & VO_CAP_FRAMEOWNER))
         talloc_free(frame);
 }
 
+bool vo_mpv_android_geometry_presented(struct vo *vo, uint64_t serial,
+                                       float panscan,
+                                       int64_t *presentation_time)
+{
+    if (!vo)
+        return false;
+
+    struct vo_internal *in = vo->in;
+    mp_mutex_lock(&in->lock);
+    bool presented =
+        in->mpv_android_geometry_serial >= serial &&
+        fabsf(in->mpv_android_panscan - panscan) <= 0.00001f;
+    if (presentation_time)
+        *presentation_time =
+            presented ? in->mpv_android_presentation_time : 0;
+    mp_mutex_unlock(&in->lock);
+    return presented;
+}
+
 static struct mp_image *get_image_vo(void *ctx, int imgfmt, int w, int h,
                                      int stride_align, int flags)
 {
diff --git a/video/out/vo.h b/video/out/vo.h
--- a/video/out/vo.h
+++ b/video/out/vo.h
@@ -510,6 +510,11 @@ struct vo {
     bool want_redraw;   // redraw as soon as possible
     int64_t previous_redraw_time;
 
+    // Android/EGL tags only geometry-transaction buffers with CLOCK_MONOTONIC
+    // time. These are written and read only on the VO thread.
+    bool mpv_android_tag_next_frame;
+    int64_t mpv_android_presentation_time;
+
     // current window state
     int dwidth;
     int dheight;
@@ -538,6 +543,11 @@ bool vo_still_displaying(struct vo *vo);
 void vo_request_wakeup_on_done(struct vo *vo);
 bool vo_has_frame(struct vo *vo);
 void vo_redraw(struct vo *vo);
+// True only after the VO has actually presented a frame produced by the given
+// geometry transaction while the matching panscan value was active.
+bool vo_mpv_android_geometry_presented(struct vo *vo, uint64_t serial,
+                                       float panscan,
+                                       int64_t *presentation_time);
 bool vo_want_redraw(struct vo *vo);
 void vo_seek_reset(struct vo *vo);
 void vo_destroy(struct vo *vo);
PATCH
fi

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
