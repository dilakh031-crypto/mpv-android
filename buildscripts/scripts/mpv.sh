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
