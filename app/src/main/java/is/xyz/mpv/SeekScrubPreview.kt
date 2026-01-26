package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Process
import android.view.View
import android.widget.ImageView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows fast, responsive preview frames while the user is scrubbing (seeking with finger).
 *
 * Why this exists:
 * - mpv seeks are asynchronous; during very fast scrubbing, the video output can appear to
 *   "stick" on the old frame until the user slows down.
 * - This class decodes preview frames independently and overlays them over the video output,
 *   giving Samsung-player-like responsiveness.
 */
class SeekScrubPreview(
    private val context: Context,
    private val view: ImageView,
) {
    private val latestRequestId = AtomicInteger(0)
    @Volatile private var source: String? = null
    @Volatile private var enabled = false

    // Latest-only execution: keep at most 1 queued task; discard older queued tasks.
    private val executor = ThreadPoolExecutor(
        /* corePoolSize = */ 2,
        /* maximumPoolSize = */ 2,
        /* keepAliveTime = */ 1L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(1),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private val retrieverTL = ThreadLocal<MediaMetadataRetriever>()
    private val retrieverSourceTL = ThreadLocal<String?>()

    fun prepare(newSource: String?) {
        source = newSource
        enabled = isSupportedSource(newSource)
        if (!enabled) {
            hideAndClear()
        }
    }

    fun show() {
        if (enabled) {
            view.visibility = View.VISIBLE
        }
    }

    fun requestSecond(second: Int) {
        if (!enabled) return
        val src = source ?: return
        val id = latestRequestId.incrementAndGet()

        // Snap dimensions to the view size if available; otherwise decode a modest size.
        val targetW = view.width.takeIf { it > 0 } ?: 720
        val targetH = view.height.takeIf { it > 0 } ?: 1280

        executor.execute {
            // Prioritize responsiveness.
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Throwable) {}

            if (id != latestRequestId.get()) return@execute

            val bmp = try {
                decodeFrame(src, second, targetW, targetH)
            } catch (_: Throwable) {
                null
            }

            if (id != latestRequestId.get()) return@execute

            view.post {
                if (id != latestRequestId.get()) return@post
                if (bmp != null) {
                    view.setImageBitmap(bmp)
                    view.visibility = View.VISIBLE
                }
            }
        }
    }

    fun hideAndClear(delayMs: Long = 0L) {
        // invalidate any in-flight work
        latestRequestId.incrementAndGet()
        val action = Runnable {
            view.setImageDrawable(null)
            view.visibility = View.GONE
        }
        if (delayMs <= 0L) {
            view.post(action)
        } else {
            view.postDelayed(action, delayMs)
        }
    }

    fun release() {
        hideAndClear()
        executor.shutdownNow()
        // Best-effort cleanup of thread-local retrievers
        try {
            retrieverTL.get()?.release()
        } catch (_: Throwable) {}
        retrieverTL.remove()
        retrieverSourceTL.remove()
    }

    private fun decodeFrame(src: String, second: Int, w: Int, h: Int): Bitmap? {
        val timeUs = second.coerceAtLeast(0).toLong() * 1_000_000L

        val r = getOrCreateRetriever(src)

        // Try exact-ish first; if device implementation is picky, fall back.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                ?: r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        } else {
            // Older Androids: decode full then scale by ImageView.
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    private fun getOrCreateRetriever(src: String): MediaMetadataRetriever {
        val existing = retrieverTL.get()
        val existingSrc = retrieverSourceTL.get()
        if (existing != null && existingSrc == src) return existing

        // Replace the thread-local retriever.
        try { existing?.release() } catch (_: Throwable) {}

        val r = MediaMetadataRetriever()
        setDataSource(r, src)
        retrieverTL.set(r)
        retrieverSourceTL.set(src)
        return r
    }

    private fun setDataSource(r: MediaMetadataRetriever, src: String) {
        val uri = Uri.parse(src)
        when (uri.scheme) {
            null, "file" -> r.setDataSource(src)
            "content" -> r.setDataSource(context, uri)
            else -> r.setDataSource(src)
        }
    }

    private fun isSupportedSource(src: String?): Boolean {
        if (src.isNullOrBlank()) return false
        // We only try local/file/content sources. Streams often fail or are too slow.
        val scheme = Uri.parse(src).scheme
        return scheme == null || scheme == "file" || scheme == "content"
    }
}
