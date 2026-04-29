package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Single-renderer pinch-to-zoom + pan for mpv output.
 *
 * v3 removes the hybrid Android-view/mpv switch completely. The Android view is never used as
 * the zoom renderer; it is kept at identity and mpv is the only renderer for both moving and
 * settled states. This eliminates the visible transition between the old smooth Android scaler
 * and the sharp mpv scaler.
 *
 * Smoothness is preserved by:
 *  - coalescing MotionEvent bursts to one update per display vsync;
 *  - sending mpv commands asynchronously instead of blocking the UI thread;
 *  - sending only properties that actually changed.
 *
 * IMPORTANT:
 *  - Touch input must come from an untransformed overlay view (gestureLayer), not from the video
 *    view itself.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio, with rotation already applied. 0 => unknown */
    private var videoAspect = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()

    // Logical transform requested by the user. 1.0 = normal. tx/ty are in view pixels.
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var didDrag = false

    // Single-finger pan is applied on vsync to avoid bursty MotionEvent delivery causing
    // visible micro-stutter.
    private var panFingerDown = false
    private var panPendingX = 0f
    private var panPendingY = 0f
    private var panFrameX = 0f
    private var panFrameY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var lastSentZoom = Double.NaN
    private var lastSentPanX = Double.NaN
    private var lastSentPanY = Double.NaN
    private var asyncSerial = 1L

    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private var lastMpvSendTime = 0L

    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false

        if (panFingerDown && didDrag && isZoomed() && !scaleDetector.isInProgress) {
            val dx = panPendingX - panFrameX
            val dy = panPendingY - panFrameY
            panFrameX = panPendingX
            panFrameY = panPendingY
            tx += dx
            ty += dy
        }

        clampTranslationToVideoContent()
        applyToMpvCoalesced()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                didDrag = true
                keepAndroidViewIdentity()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val newScale = (oldScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (newScale == oldScale)
                    return true

                // Keep pinch focus stable.
                // Transform model: screen = scale * content + translation.
                // Zoom around focus F: t' = k * t + (1 - k) * F.
                val k = newScale / oldScale
                tx = (k * tx) + ((1f - k) * detector.focusX)
                ty = (k * ty) + ((1f - k) * detector.focusY)
                scale = newScale

                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1f + EPS)
                    reset()
                else
                    scheduleApply()
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (isZoomed())
            scheduleApply()
    }

    fun setVideoAspect(aspect: Double?) {
        videoAspect = aspect ?: 0.0
        if (isZoomed())
            scheduleApply()
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

        scale = 1f
        tx = 0f
        ty = 0f
        didDrag = false
        panFingerDown = false
        lastTapTime = 0L

        keepAndroidViewIdentity()
        resetMpvTransform(force = true)
    }

    /**
     * @return true if the event should be consumed.
     *         While zoomed: pinch/pan/double-tap are consumed.
     *         Single tap returns false so the Activity can toggle controls.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        keepAndroidViewIdentity()

        // Always feed the scale detector first.
        scaleDetector.onTouchEvent(e)

        // Pointer transitions during pinch:
        // If one finger lifts and another remains down, update lastX/lastY so we don't jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            didDrag = true
            panFingerDown = false
            if (e.pointerCount >= 2) {
                val upIdx = e.actionIndex
                val remainIdx = if (upIdx == 0) 1 else 0
                lastX = e.getX(remainIdx)
                lastY = e.getY(remainIdx)
                downX = lastX
                downY = lastY
                panPendingX = lastX
                panPendingY = lastY
                panFrameX = lastX
                panFrameY = lastY
                downTime = SystemClock.uptimeMillis()
            }
            scheduleApply()
            return true
        }

        // Multi-touch, or active pinch, should always be consumed.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
            panFingerDown = false
            scheduleApply()
            return true
        }

        if (!isZoomed())
            return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                panPendingX = e.x
                panPendingY = e.y
                panFrameX = e.x
                panFrameY = e.y
                panFingerDown = true
                downTime = SystemClock.uptimeMillis()
                didDrag = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                panPendingX = e.x
                panPendingY = e.y

                if (!didDrag) {
                    val dist = hypot(e.x - downX, e.y - downY)
                    if (dist >= touchSlop)
                        didDrag = true
                }

                if (didDrag)
                    scheduleApply()

                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !didDrag && (now - downTime) < DOUBLE_TAP_TIMEOUT

                panFingerDown = false

                if (!wasTap) {
                    lastTapTime = 0L
                    scheduleApply()
                    return true
                }

                // Double-tap anywhere while zoomed => reset.
                val dt = now - lastTapTime
                val dist = hypot(e.x - lastTapX, e.y - lastTapY)
                if (lastTapTime != 0L && dt < DOUBLE_TAP_TIMEOUT && dist < touchSlop * 3f) {
                    reset()
                    lastTapTime = 0L
                    return true
                }

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                scheduleApply()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                panFingerDown = false
                scheduleApply()
                return true
            }
        }

        return true
    }

    /** Compute the content/video rect within the view at base scale. */
    private fun contentRect(): ContentRect {
        val w = viewWidth
        val h = viewHeight
        if (w <= 1f || h <= 1f)
            return ContentRect(0f, 0f, w, h)

        val ar = if (videoAspect > 0.001) videoAspect.toFloat() else (w / h)
        val viewAr = w / h
        val cw: Float
        val ch: Float
        if (ar > viewAr) {
            cw = w
            ch = w / ar
        } else {
            ch = h
            cw = h * ar
        }
        val ox = (w - cw) * 0.5f
        val oy = (h - ch) * 0.5f
        return ContentRect(ox, oy, cw, ch)
    }

    private fun clampTranslationToVideoContent() {
        if (viewWidth <= 1f || viewHeight <= 1f)
            return

        if (scale <= 1f + EPS) {
            scale = 1f
            tx = 0f
            ty = 0f
            return
        }

        val c = contentRect()
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        tx = if (contentWScaled <= viewWidth + EPS) {
            ((viewWidth - contentWScaled) * 0.5f) - scale * c.ox
        } else {
            val minTx = viewWidth - scale * (c.ox + c.w)
            val maxTx = -scale * c.ox
            tx.coerceIn(minTx, maxTx)
        }

        ty = if (contentHScaled <= viewHeight + EPS) {
            ((viewHeight - contentHScaled) * 0.5f) - scale * c.oy
        } else {
            val minTy = viewHeight - scale * (c.oy + c.h)
            val maxTy = -scale * c.oy
            ty.coerceIn(minTy, maxTy)
        }
    }

    private fun applyToMpvCoalesced() {
        keepAndroidViewIdentity()

        val now = SystemClock.uptimeMillis()
        if (now - lastMpvSendTime < MIN_MPV_SEND_INTERVAL_MS) {
            scheduleApply()
            return
        }
        lastMpvSendTime = now

        val c = contentRect()
        val safeScale = scale.coerceAtLeast(MIN_SCALE)
        val zoom = if (safeScale <= 1f + EPS) 0.0 else log2(safeScale.toDouble())

        var panX = 0.0
        var panY = 0.0

        if (safeScale > 1f + EPS && c.w > 1f && c.h > 1f) {
            val desiredLeft = safeScale * c.ox + tx
            val desiredTop = safeScale * c.oy + ty

            // mpv centers the zoomed video first, then applies pan as a fraction of the scaled
            // video dimensions. Convert from our pixel-space transform to mpv pan units.
            val centeredLeft = (viewWidth - safeScale * c.w) * 0.5f
            val centeredTop = (viewHeight - safeScale * c.h) * 0.5f
            panX = ((desiredLeft - centeredLeft) / (safeScale * c.w)).toDouble()
            panY = ((desiredTop - centeredTop) / (safeScale * c.h)).toDouble()
        }

        setMpvDoubleAsync("video-zoom", zoom, force = false)
        setMpvDoubleAsync("video-pan-x", panX, force = false)
        setMpvDoubleAsync("video-pan-y", panY, force = false)
    }

    private fun keepAndroidViewIdentity() {
        // Do not let Android become a second zoom renderer. This line is what removes the
        // Android<->mpv visual switch completely.
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.translationX = 0f
        target.translationY = 0f
    }

    private fun resetMpvTransform(force: Boolean) {
        setMpvDoubleAsync("video-zoom", 0.0, force)
        setMpvDoubleAsync("video-pan-x", 0.0, force)
        setMpvDoubleAsync("video-pan-y", 0.0, force)
    }

    private fun setMpvDoubleAsync(property: String, value: Double, force: Boolean) {
        val previous = when (property) {
            "video-zoom" -> lastSentZoom
            "video-pan-x" -> lastSentPanX
            "video-pan-y" -> lastSentPanY
            else -> Double.NaN
        }

        if (!force && !previous.isNaN() && abs(previous - value) < MPV_APPLY_EPS)
            return

        try {
            // commandAsync avoids blocking the UI thread during pinch/pan. If it fails for any
            // reason, fall back to the direct property setter.
            val result = MPVLib.commandAsync(arrayOf("set", property, value.toString()), asyncSerial++)
            if (result < 0)
                MPVLib.setPropertyDouble(property, value)

            when (property) {
                "video-zoom" -> lastSentZoom = value
                "video-pan-x" -> lastSentPanX = value
                "video-pan-y" -> lastSentPanY = value
            }
        } catch (_: Throwable) {
            try {
                MPVLib.setPropertyDouble(property, value)
                when (property) {
                    "video-zoom" -> lastSentZoom = value
                    "video-pan-x" -> lastSentPanX = value
                    "video-pan-y" -> lastSentPanY = value
                }
            } catch (_: Throwable) {
                // mpv may not be initialized yet during early Activity setup or after shutdown.
            }
        }
    }

    private fun log2(value: Double): Double = ln(value) / LN_2

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)

    companion object {
        private const val EPS = 0.001f
        private const val MPV_APPLY_EPS = 0.00001
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val MIN_MPV_SEND_INTERVAL_MS = 16L
        private const val LN_2 = 0.6931471805599453
    }
}
