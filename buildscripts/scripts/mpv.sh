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

# Keep a sliding decoded A/V window for instant short backward exact seeks.
# The Android side enables this only with Smoother seeking and uses
# mediacodec-copy so cached frames do not retain MediaCodec's surface pool.
if ! grep -q '"exact-seek-cache-secs"' options/options.c; then
    git apply --whitespace=nowarn <<'EXACT_SEEK_CACHE_PATCH'
diff --git a/options/options.c b/options/options.c
index 2a5373d..60b5040 100644
--- a/options/options.c
+++ b/options/options.c
@@ -882,6 +882,8 @@ static const m_option_t mp_opts[] = {
         {"no", -1}, {"absolute", 0}, {"yes", 1}, {"always", 1}, {"default", 2})},
     {"hr-seek-demuxer-offset", OPT_FLOAT(hr_seek_demuxer_offset)},
     {"hr-seek-framedrop", OPT_BOOL(hr_seek_framedrop)},
+    {"exact-seek-cache-secs", OPT_DOUBLE(exact_seek_cache_secs),
+        M_RANGE(0, DBL_MAX)},
     {"autosync", OPT_CHOICE(autosync, {"no", -1}), M_RANGE(0, 10000)},
 
     {"term-osd", OPT_CHOICE(term_osd,
diff --git a/options/options.h b/options/options.h
index 1524262..fa7f1ca 100644
--- a/options/options.h
+++ b/options/options.h
@@ -263,6 +263,7 @@ typedef struct MPOpts {
     int hr_seek;
     float hr_seek_demuxer_offset;
     bool hr_seek_framedrop;
+    double exact_seek_cache_secs;
     double audio_delay;
     float default_max_pts_correction;
     int autosync;
diff --git a/player/audio.c b/player/audio.c
index b3e975d..dd6bd67 100644
--- a/player/audio.c
+++ b/player/audio.c
@@ -21,6 +21,7 @@
 #include <limits.h>
 #include <math.h>
 #include <assert.h>
+#include <string.h>
 
 #include "mpv_talloc.h"
 
@@ -110,6 +111,9 @@ int reinit_audio_filters(struct MPContext *mpctx)
     if (!ao_c)
         return 0;
 
+    // Filtered PCM from the old graph must never be mixed with new output.
+    audio_exact_seek_cache_clear(mpctx);
+
     double delay = mp_output_get_measured_total_delay(ao_c->filter);
 
     if (recreate_audio_filters(mpctx) < 0)
@@ -227,6 +231,143 @@ static void ao_chain_reset_state(struct ao_chain *ao_c)
     ao_c->delaying_audio_start = false;
 }
 
+static void clear_audio_exact_seek_cache(struct ao_chain *ao_c)
+{
+    if (!ao_c)
+        return;
+    for (int n = 0; n < ao_c->num_exact_seek_cache; n++)
+        talloc_free(ao_c->exact_seek_cache[n]);
+    ao_c->num_exact_seek_cache = 0;
+    ao_c->exact_seek_cache_pos = 0;
+    ao_c->exact_seek_cache_pts = MP_NOPTS_VALUE;
+    ao_c->exact_seek_cache_replaying = false;
+}
+
+void audio_exact_seek_cache_clear(struct MPContext *mpctx)
+{
+    clear_audio_exact_seek_cache(mpctx->ao_chain);
+}
+
+static int audio_exact_seek_cache_index(struct MPContext *mpctx, double pts)
+{
+    struct ao_chain *ao_c = mpctx->ao_chain;
+    if (!ao_c || mpctx->opts->exact_seek_cache_secs <= 0 ||
+        ao_c->num_exact_seek_cache == 0 || pts == MP_NOPTS_VALUE)
+        return -1;
+
+    struct mp_aframe *first_frame = ao_c->exact_seek_cache[0];
+    struct mp_aframe *last_frame =
+        ao_c->exact_seek_cache[ao_c->num_exact_seek_cache - 1];
+    double first = mp_aframe_get_pts(first_frame);
+    double last = mp_aframe_end_pts(last_frame);
+    if (first == MP_NOPTS_VALUE || last == MP_NOPTS_VALUE ||
+        pts < first - 0.005 || pts > last + 0.005)
+        return -1;
+
+    for (int n = 0; n < ao_c->num_exact_seek_cache; n++) {
+        if (mp_aframe_end_pts(ao_c->exact_seek_cache[n]) > pts - 0.005)
+            return n;
+    }
+    return -1;
+}
+
+bool audio_exact_seek_cache_contains(struct MPContext *mpctx, double pts)
+{
+    return !mpctx->ao_chain || audio_exact_seek_cache_index(mpctx, pts) >= 0;
+}
+
+void audio_exact_seek_cache_start(struct MPContext *mpctx, double pts)
+{
+    struct ao_chain *ao_c = mpctx->ao_chain;
+    if (!ao_c)
+        return;
+    int index = audio_exact_seek_cache_index(mpctx, pts);
+    mp_assert(index >= 0);
+    ao_c->exact_seek_cache_pos = index;
+    ao_c->exact_seek_cache_pts = pts;
+    ao_c->exact_seek_cache_replaying = true;
+
+    // ao_reset() clears the shared queue and its consumer filter. Reset the
+    // producer end as well so a live-edge frame requested before the seek
+    // cannot be inserted ahead of the replayed cache.
+    if (ao_c->queue_filter)
+        mp_filter_reset(ao_c->queue_filter);
+}
+
+static void cache_audio_frame(struct MPContext *mpctx, struct mp_aframe *af)
+{
+    struct ao_chain *ao_c = mpctx->ao_chain;
+    double seconds = mpctx->opts->exact_seek_cache_secs;
+    double pts = mp_aframe_get_pts(af);
+    if (!ao_c || seconds <= 0 || ao_c->exact_seek_cache_replaying ||
+        pts == MP_NOPTS_VALUE || af_fmt_is_spdif(mp_aframe_get_format(af)))
+    {
+        if (ao_c && (seconds <= 0 || af_fmt_is_spdif(mp_aframe_get_format(af))))
+            clear_audio_exact_seek_cache(ao_c);
+        return;
+    }
+
+    if (ao_c->num_exact_seek_cache) {
+        struct mp_aframe *last =
+            ao_c->exact_seek_cache[ao_c->num_exact_seek_cache - 1];
+        double last_pts = mp_aframe_get_pts(last);
+        if (pts < last_pts - 0.005 || pts - last_pts > seconds + 1.0 ||
+            !mp_aframe_config_equals(last, af) ||
+            fabs(mp_aframe_get_speed(last) - mp_aframe_get_speed(af)) > 1e-9)
+        {
+            clear_audio_exact_seek_cache(ao_c);
+        }
+    }
+
+    struct mp_aframe *ref = mp_aframe_new_ref(af);
+    if (!ref)
+        return;
+    MP_TARRAY_APPEND(ao_c, ao_c->exact_seek_cache,
+                     ao_c->num_exact_seek_cache, ref);
+
+    // Keep a small extra audio lead because AO normally buffers farther ahead
+    // than the last video frame. Video still defines the advertised window.
+    double audio_window = seconds + 1.0;
+    double newest = mp_aframe_end_pts(af);
+    if (newest == MP_NOPTS_VALUE) {
+        clear_audio_exact_seek_cache(ao_c);
+        return;
+    }
+    while (ao_c->num_exact_seek_cache > 1 &&
+           newest - mp_aframe_get_pts(ao_c->exact_seek_cache[0]) > audio_window)
+    {
+        talloc_free(ao_c->exact_seek_cache[0]);
+        memmove(ao_c->exact_seek_cache, ao_c->exact_seek_cache + 1,
+                (ao_c->num_exact_seek_cache - 1) * sizeof(ao_c->exact_seek_cache[0]));
+        ao_c->num_exact_seek_cache--;
+    }
+}
+
+static struct mp_frame read_audio_exact_seek_cache(struct ao_chain *ao_c)
+{
+    if (!ao_c->exact_seek_cache_replaying)
+        return MP_NO_FRAME;
+
+    struct mp_frame frame = MP_NO_FRAME;
+    if (ao_c->exact_seek_cache_pos < ao_c->num_exact_seek_cache) {
+        struct mp_aframe *ref = mp_aframe_new_ref(
+            ao_c->exact_seek_cache[ao_c->exact_seek_cache_pos++]);
+        if (ref) {
+            if (ao_c->exact_seek_cache_pts != MP_NOPTS_VALUE) {
+                mp_aframe_clip_timestamps(ref, ao_c->exact_seek_cache_pts,
+                                          MP_NOPTS_VALUE);
+                ao_c->exact_seek_cache_pts = MP_NOPTS_VALUE;
+            }
+            frame = MAKE_FRAME(MP_FRAME_AUDIO, ref);
+        } else {
+            clear_audio_exact_seek_cache(ao_c);
+        }
+    }
+    if (ao_c->exact_seek_cache_pos >= ao_c->num_exact_seek_cache)
+        ao_c->exact_seek_cache_replaying = false;
+    return frame;
+}
+
 void reset_audio_state(struct MPContext *mpctx)
 {
     if (mpctx->ao_chain) {
@@ -279,6 +420,7 @@ static void ao_chain_uninit(struct ao_chain *ao_c)
     if (ao_c->filter_src)
         mp_pin_disconnect(ao_c->filter_src);
 
+    clear_audio_exact_seek_cache(ao_c);
     talloc_free(ao_c->filter->f);
     talloc_free(ao_c->ao_filter);
     talloc_free(ao_c);
@@ -681,10 +823,17 @@ static void ao_process(struct mp_filter *f)
     if (ao_c->untimed_throttle)
         return;
 
-    if (!mp_pin_can_transfer_data(ao_c->queue_filter->pins[0], f->ppins[0]))
+    if (!mp_pin_in_needs_data(ao_c->queue_filter->pins[0]))
         return;
 
-    struct mp_frame frame = mp_pin_out_read(f->ppins[0]);
+    bool from_exact_seek_cache = ao_c->exact_seek_cache_replaying;
+    struct mp_frame frame = read_audio_exact_seek_cache(ao_c);
+    if (!frame.type) {
+        from_exact_seek_cache = false;
+        if (!mp_pin_can_transfer_data(ao_c->queue_filter->pins[0], f->ppins[0]))
+            return;
+        frame = mp_pin_out_read(f->ppins[0]);
+    }
     if (frame.type == MP_FRAME_AUDIO) {
         struct mp_aframe *af = frame.data;
 
@@ -693,7 +842,10 @@ static void ao_process(struct mp_filter *f)
             endpts *= mpctx->play_dir;
             // Avoid decoding and discarding the entire rest of the file.
             if (mp_aframe_get_pts(af) >= endpts) {
-                mp_pin_out_unread(f->ppins[0], frame);
+                if (from_exact_seek_cache)
+                    mp_frame_unref(&frame);
+                else
+                    mp_pin_out_unread(f->ppins[0], frame);
                 if (!ao_c->out_eof) {
                     ao_c->out_eof = true;
                     mp_pin_in_write(ao_c->queue_filter->pins[0], MP_EOF_FRAME);
@@ -714,6 +866,9 @@ static void ao_process(struct mp_filter *f)
 
         ao_c->out_eof = false;
 
+        if (!from_exact_seek_cache)
+            cache_audio_frame(mpctx, af);
+
         if (mpctx->audio_status == STATUS_DRAINING ||
             mpctx->audio_status == STATUS_EOF)
         {
diff --git a/player/command.c b/player/command.c
index a0dccf4..544ebda 100644
--- a/player/command.c
+++ b/player/command.c
@@ -8358,10 +8358,17 @@ void mp_option_run_callback(struct MPContext *mpctx, struct mp_option_callback *
 
     if (opt_ptr == &opts->playback_speed || opt_ptr == &opts->playback_pitch ||
         opt_ptr == &opts->pitch_correction) {
+        video_exact_seek_cache_clear(mpctx);
+        audio_exact_seek_cache_clear(mpctx);
         update_playback_speed(mpctx);
         mp_wakeup_core(mpctx);
     }
 
+    if (opt_ptr == &opts->exact_seek_cache_secs) {
+        video_exact_seek_cache_clear(mpctx);
+        audio_exact_seek_cache_clear(mpctx);
+    }
+
     if (opt_ptr == &opts->play_dir) {
         if (mpctx->play_dir != opts->play_dir) {
             // The option must be set before we seek if we're at EOF.
diff --git a/player/core.h b/player/core.h
index ec67844..073b7cd 100644
--- a/player/core.h
+++ b/player/core.h
@@ -180,6 +180,13 @@ struct vo_chain {
 
     bool underrun;
     bool underrun_signaled;
+
+    // Sliding cache of already filtered frames used by short exact seeks.
+    struct mp_image **exact_seek_cache;
+    int num_exact_seek_cache;
+    int exact_seek_cache_pos;
+    bool exact_seek_cache_replaying;
+    bool exact_seek_cache_hw_warned;
 };
 
 // Like vo_chain, for audio.
@@ -214,6 +221,13 @@ struct ao_chain {
 
     bool ao_underrun;   // last known AO state
     bool underrun;      // for cache pause logic
+
+    // Audio is cached with video so a cache hit can resume with correct A/V sync.
+    struct mp_aframe **exact_seek_cache;
+    int num_exact_seek_cache;
+    int exact_seek_cache_pos;
+    double exact_seek_cache_pts;
+    bool exact_seek_cache_replaying;
 };
 
 /* Note that playback can be paused, stopped, etc. at any time. While paused,
@@ -672,6 +686,9 @@ void update_osd_msg(struct MPContext *mpctx);
 bool update_subtitles(struct MPContext *mpctx, double video_pts);
 
 // video.c
+bool video_exact_seek_cache_contains(struct MPContext *mpctx, double pts);
+void video_exact_seek_cache_start(struct MPContext *mpctx, double pts);
+void video_exact_seek_cache_clear(struct MPContext *mpctx);
 void reset_video_state(struct MPContext *mpctx);
 int init_video_decoder(struct MPContext *mpctx, struct track *track);
 void reinit_video_chain(struct MPContext *mpctx);
@@ -683,4 +700,9 @@ void uninit_video_out(struct MPContext *mpctx);
 void uninit_video_chain(struct MPContext *mpctx);
 double calc_average_frame_duration(struct MPContext *mpctx);
 
+// audio.c
+bool audio_exact_seek_cache_contains(struct MPContext *mpctx, double pts);
+void audio_exact_seek_cache_start(struct MPContext *mpctx, double pts);
+void audio_exact_seek_cache_clear(struct MPContext *mpctx);
+
 #endif /* MPLAYER_MP_CORE_H */
diff --git a/player/loadfile.c b/player/loadfile.c
index 3ded4e7..70f91e3 100644
--- a/player/loadfile.c
+++ b/player/loadfile.c
@@ -1609,6 +1609,8 @@ void update_vo_chain_el_pair(struct MPContext *mpctx)
 void update_lavfi_complex(struct MPContext *mpctx)
 {
     if (mpctx->playback_initialized) {
+        video_exact_seek_cache_clear(mpctx);
+        audio_exact_seek_cache_clear(mpctx);
         int r = reinit_complex_filters(mpctx, false);
         if (r != 0)
             issue_refresh_seek(mpctx, MPSEEK_EXACT);
diff --git a/player/playloop.c b/player/playloop.c
index d899fba..c00a624 100644
--- a/player/playloop.c
+++ b/player/playloop.c
@@ -237,15 +237,8 @@ void step_frame_mute(struct MPContext *mpctx, bool mute)
     ao_set_gain(mpctx->ao_chain->ao, gain);
 }
 
-// Clear some playback-related fields on file loading or after seeks.
-void reset_playback_state(struct MPContext *mpctx)
+static void reset_playback_state_fields(struct MPContext *mpctx)
 {
-    mp_filter_reset(mpctx->filter_root);
-
-    reset_video_state(mpctx);
-    reset_audio_state(mpctx);
-    reset_subtitle_state(mpctx);
-
     for (int n = 0; n < mpctx->num_tracks; n++) {
         struct track *t = mpctx->tracks[n];
         // (Often, but not always, this is redundant and also done elsewhere.)
@@ -277,6 +270,29 @@ void reset_playback_state(struct MPContext *mpctx)
     update_core_idle_state(mpctx);
 }
 
+// Clear playback and decoder/filter state on file loading or normal seeks.
+void reset_playback_state(struct MPContext *mpctx)
+{
+    video_exact_seek_cache_clear(mpctx);
+    audio_exact_seek_cache_clear(mpctx);
+    mp_filter_reset(mpctx->filter_root);
+
+    reset_video_state(mpctx);
+    reset_audio_state(mpctx);
+    reset_subtitle_state(mpctx);
+    reset_playback_state_fields(mpctx);
+}
+
+// A decoded-cache seek deliberately leaves the demuxer, decoders and filters at
+// the live edge. Cached A/V is replayed until it catches up to that edge.
+static void reset_playback_state_for_exact_cache(struct MPContext *mpctx)
+{
+    reset_video_state(mpctx);
+    reset_audio_state(mpctx);
+    redraw_subs(mpctx);
+    reset_playback_state_fields(mpctx);
+}
+
 static double calculate_framestep_pts(MPContext *mpctx, double current_time,
                                       int step_frames)
 {
@@ -287,6 +303,46 @@ static double calculate_framestep_pts(MPContext *mpctx, double current_time,
     return current_time + pts;
 }
 
+static bool try_exact_seek_cache(struct MPContext *mpctx,
+                                 struct seek_params seek, double seek_pts,
+                                 double current_time, bool hr_seek)
+{
+    struct MPOpts *opts = mpctx->opts;
+    if (!hr_seek || seek.exact != MPSEEK_EXACT ||
+        (seek.flags & MPSEEK_FLAG_NOFLUSH) ||
+        opts->exact_seek_cache_secs <= 0 || opts->play_dir != 1 ||
+        mpctx->encode_lavc_ctx || (!mpctx->vo_chain && !mpctx->ao_chain) ||
+        seek_pts == MP_NOPTS_VALUE || seek_pts > current_time + 0.005 ||
+        !video_exact_seek_cache_contains(mpctx, seek_pts) ||
+        !audio_exact_seek_cache_contains(mpctx, seek_pts))
+    {
+        return false;
+    }
+
+    clear_audio_output_buffers(mpctx);
+    video_exact_seek_cache_start(mpctx, seek_pts);
+    audio_exact_seek_cache_start(mpctx, seek_pts);
+
+    mpctx->play_dir = 1;
+    reset_playback_state_for_exact_cache(mpctx);
+    mpctx->last_seek_pts = seek_pts;
+
+    if (mpctx->stop_play == AT_END_OF_FILE)
+        mpctx->stop_play = KEEP_PLAYING;
+
+    mpctx->start_timestamp = mp_time_sec();
+    mp_wakeup_core(mpctx);
+
+    MP_VERBOSE(mpctx, "exact seek cache hit at %f\n", seek_pts);
+    mp_notify(mpctx, MPV_EVENT_SEEK, NULL);
+    mp_notify(mpctx, MPV_EVENT_TICK, NULL);
+
+    update_ab_loop_clip(mpctx);
+    mpctx->current_seek = seek;
+    redraw_subs(mpctx);
+    return true;
+}
+
 static void mp_seek(MPContext *mpctx, struct seek_params seek)
 {
     struct MPOpts *opts = mpctx->opts;
@@ -336,6 +392,9 @@ static void mp_seek(MPContext *mpctx, struct seek_params seek)
          (opts->hr_seek >= 0 && seek.type == MPSEEK_ABSOLUTE) ||
          (opts->hr_seek == 2 && (!mpctx->vo_chain || mpctx->vo_chain->is_sparse)));
 
+    if (try_exact_seek_cache(mpctx, seek, seek_pts, current_time, hr_seek))
+        return;
+
     // Under certain circumstances, prefer SEEK_FACTOR.
     if (seek.type == MPSEEK_FACTOR && !hr_seek &&
         (mpctx->demuxer->ts_resets_possible || seek_pts == MP_NOPTS_VALUE))
@@ -375,6 +434,11 @@ static void mp_seek(MPContext *mpctx, struct seek_params seek)
 
     demux_flags |= SEEK_BLOCK;
 
+    // Any real demuxer seek makes the retained decoded timeline unrelated to
+    // subsequent output. Start a new sliding window at the seek result.
+    video_exact_seek_cache_clear(mpctx);
+    audio_exact_seek_cache_clear(mpctx);
+
     if (!demux_seek(mpctx->demuxer, demux_pts, demux_flags)) {
         if (!mpctx->demuxer->seekable) {
             MP_ERR(mpctx, "Cannot seek in this stream.\n");
diff --git a/player/video.c b/player/video.c
index e2cc480..5016735 100644
--- a/player/video.c
+++ b/player/video.c
@@ -20,6 +20,7 @@
 #include <inttypes.h>
 #include <math.h>
 #include <assert.h>
+#include <string.h>
 
 #include "mpv_talloc.h"
 
@@ -78,6 +79,9 @@ int reinit_video_filters(struct MPContext *mpctx)
     if (!vo_c)
         return 0;
 
+    // Frames produced by the previous filter graph are no longer valid.
+    video_exact_seek_cache_clear(mpctx);
+
     if (!recreate_video_filters(mpctx))
         return -1;
 
@@ -95,6 +99,108 @@ static void vo_chain_reset_state(struct vo_chain *vo_c)
     vo_c->underrun_signaled = false;
 }
 
+static void clear_video_exact_seek_cache(struct vo_chain *vo_c)
+{
+    if (!vo_c)
+        return;
+    for (int n = 0; n < vo_c->num_exact_seek_cache; n++)
+        talloc_free(vo_c->exact_seek_cache[n]);
+    vo_c->num_exact_seek_cache = 0;
+    vo_c->exact_seek_cache_pos = 0;
+    vo_c->exact_seek_cache_replaying = false;
+}
+
+void video_exact_seek_cache_clear(struct MPContext *mpctx)
+{
+    clear_video_exact_seek_cache(mpctx->vo_chain);
+}
+
+static int video_exact_seek_cache_index(struct MPContext *mpctx, double pts)
+{
+    struct vo_chain *vo_c = mpctx->vo_chain;
+    if (!vo_c || mpctx->opts->exact_seek_cache_secs <= 0 ||
+        vo_c->is_sparse || vo_c->num_exact_seek_cache == 0 ||
+        pts == MP_NOPTS_VALUE)
+        return -1;
+
+    double first = vo_c->exact_seek_cache[0]->pts;
+    double last = vo_c->exact_seek_cache[vo_c->num_exact_seek_cache - 1]->pts;
+    if (first == MP_NOPTS_VALUE || last == MP_NOPTS_VALUE ||
+        pts < first - 0.005 || pts > last + 0.005)
+        return -1;
+
+    // Match hr-seek's rule: use the first frame at, or within 5 ms before,
+    // the requested timestamp.
+    for (int n = 0; n < vo_c->num_exact_seek_cache; n++) {
+        if (vo_c->exact_seek_cache[n]->pts >= pts - 0.005)
+            return n;
+    }
+    return -1;
+}
+
+bool video_exact_seek_cache_contains(struct MPContext *mpctx, double pts)
+{
+    return !mpctx->vo_chain || video_exact_seek_cache_index(mpctx, pts) >= 0;
+}
+
+void video_exact_seek_cache_start(struct MPContext *mpctx, double pts)
+{
+    struct vo_chain *vo_c = mpctx->vo_chain;
+    if (!vo_c)
+        return;
+    int index = video_exact_seek_cache_index(mpctx, pts);
+    mp_assert(index >= 0);
+    vo_c->exact_seek_cache_pos = index;
+    vo_c->exact_seek_cache_replaying = true;
+}
+
+static void cache_video_frame(struct MPContext *mpctx, struct mp_image *img)
+{
+    struct vo_chain *vo_c = mpctx->vo_chain;
+    double seconds = mpctx->opts->exact_seek_cache_secs;
+    if (!vo_c || seconds <= 0 || vo_c->is_sparse ||
+        vo_c->exact_seek_cache_replaying || img->pts == MP_NOPTS_VALUE)
+    {
+        if (vo_c && seconds <= 0)
+            clear_video_exact_seek_cache(vo_c);
+        return;
+    }
+
+    // Opaque hardware frames retain scarce decoder surfaces. mpv-android uses
+    // mediacodec-copy while this cache is enabled, but reject unsafe formats if
+    // another client enables the option directly.
+    if (IMGFMT_IS_HWACCEL(img->imgfmt)) {
+        clear_video_exact_seek_cache(vo_c);
+        if (!vo_c->exact_seek_cache_hw_warned) {
+            MP_WARN(mpctx, "Exact-seek frame cache requires copy-back or software video frames.\n");
+            vo_c->exact_seek_cache_hw_warned = true;
+        }
+        return;
+    }
+
+    if (vo_c->num_exact_seek_cache) {
+        double last = vo_c->exact_seek_cache[vo_c->num_exact_seek_cache - 1]->pts;
+        if (img->pts < last - 0.005 || img->pts - last > seconds + 1.0)
+            clear_video_exact_seek_cache(vo_c);
+    }
+
+    struct mp_image *ref = mp_image_new_ref(img);
+    if (!ref)
+        return;
+    MP_TARRAY_APPEND(vo_c, vo_c->exact_seek_cache,
+                     vo_c->num_exact_seek_cache, ref);
+
+    double newest = img->pts;
+    while (vo_c->num_exact_seek_cache > 1 &&
+           newest - vo_c->exact_seek_cache[0]->pts > seconds)
+    {
+        talloc_free(vo_c->exact_seek_cache[0]);
+        memmove(vo_c->exact_seek_cache, vo_c->exact_seek_cache + 1,
+                (vo_c->num_exact_seek_cache - 1) * sizeof(vo_c->exact_seek_cache[0]));
+        vo_c->num_exact_seek_cache--;
+    }
+}
+
 void reset_video_state(struct MPContext *mpctx)
 {
     if (mpctx->vo_chain) {
@@ -154,6 +260,7 @@ static void vo_chain_uninit(struct vo_chain *vo_c)
     if (vo_c->filter_src)
         mp_pin_disconnect(vo_c->filter_src);
 
+    clear_video_exact_seek_cache(vo_c);
     talloc_free(vo_c->filter->f);
     talloc_free(vo_c);
     // this does not free the VO
@@ -501,7 +608,25 @@ static int video_output_image(struct MPContext *mpctx, bool *logical_eof)
     if (needs_new_frame(mpctx)) {
         // Filter a new frame.
         struct mp_image *img = NULL;
-        struct mp_frame frame = mp_pin_out_read(vo_c->filter->f->pins[1]);
+        bool from_exact_seek_cache = false;
+        struct mp_frame frame = MP_NO_FRAME;
+        if (vo_c->exact_seek_cache_replaying) {
+            if (vo_c->exact_seek_cache_pos < vo_c->num_exact_seek_cache) {
+                struct mp_image *ref = mp_image_new_ref(
+                    vo_c->exact_seek_cache[vo_c->exact_seek_cache_pos]);
+                if (ref) {
+                    vo_c->exact_seek_cache_pos++;
+                    frame = MAKE_FRAME(MP_FRAME_VIDEO, ref);
+                    from_exact_seek_cache = true;
+                } else {
+                    clear_video_exact_seek_cache(vo_c);
+                }
+            }
+            if (vo_c->exact_seek_cache_pos >= vo_c->num_exact_seek_cache)
+                vo_c->exact_seek_cache_replaying = false;
+        }
+        if (!frame.type)
+            frame = mp_pin_out_read(vo_c->filter->f->pins[1]);
         if (frame.type == MP_FRAME_NONE) {
             r = vo_c->filter->got_output_eof ? VD_EOF : VD_WAIT;
         } else if (frame.type == MP_FRAME_EOF) {
@@ -521,8 +646,10 @@ static int video_output_image(struct MPContext *mpctx, bool *logical_eof)
             if ((endpts != MP_NOPTS_VALUE && img->pts >= endpts) ||
                 mpctx->max_frames == 0)
             {
-                mp_pin_out_unread(vo_c->filter->f->pins[1], frame);
-                img = NULL;
+                if (!from_exact_seek_cache) {
+                    mp_pin_out_unread(vo_c->filter->f->pins[1], frame);
+                    img = NULL;
+                }
                 r = VD_EOF;
             } else if (hrseek && (img->pts < hrseek_pts - tolerance ||
                                   mpctx->hrseek_lastframe))
@@ -540,6 +667,8 @@ static int video_output_image(struct MPContext *mpctx, bool *logical_eof)
                     mpctx->hrseek_backstep = false;
                 }
                 mp_image_unrefp(&mpctx->saved_frame);
+                if (!from_exact_seek_cache)
+                    cache_video_frame(mpctx, img);
                 add_new_frame(mpctx, img);
                 img = NULL;
             }
EXACT_SEEK_CACHE_PATCH
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
