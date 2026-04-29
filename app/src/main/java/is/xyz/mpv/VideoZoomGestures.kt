package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * High-FPS pinch-to-zoom + pan for mpv output.
 *
 * Design goals (Samsung Gallery-like):
 *  - 120fps smooth zoom/pan during gestures (using Android View transformations).
 *  - Lossless high-res quality when paused (committing exact values to MPV natively after the gesture).
 *  - While zoomed: one-finger pan, double-tap resets, seeking disabled
 *  - Black bars (letter/pillarbox) are treated as "not part of video".
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio (rotation already applied). 0 => unknown */
    private var videoAspect = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()

    // Absolute intended visual values (Combining Native + View)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    // Native values currently applied to MPV
    private var nativeScale = 1f
    private var nativeTx = 0f
    private var nativeTy = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var didDrag = false

    private var panFingerDown = false
    private var panPendingX = 0f
    private var panPendingY = 0f
    private var panFrameX = 0f
    private var panFrameY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var commitRunnable: Runnable? = null

    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
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
        applyToView()
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
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)
                if (newScale == oldScale)
                    return true

                val fx = detector.focusX
                val fy = detector.focusY
                val k = newScale / oldScale
                tx = (k * tx) + ((1f - k) * fx)
                ty = (k * ty) + ((1f - k) * fy)
                scale = newScale

                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1f + EPS) {
                    reset()
                } else if (!panFingerDown) {
                    commitToNative()
                }
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

        // Instant reset for MPV and View without delays
        commitRunnable?.let { target.removeCallbacks(it) }
        nativeScale = 1f
        nativeTx = 0f
        nativeTy = 0f

        try {
            MPVLib.setPropertyDouble("video-zoom", 0.0)
            MPVLib.setPropertyDouble("video-pan-x", 0.0)
            MPVLib.setPropertyDouble("video-pan-y", 0.0)
        } catch (e: Exception) {}

        applyToView()
    }

    /**
     * Commits the current absolute scale and pan to MPV natively to render the high-res frame.
     * This is only called when the user stops touching the screen.
     */
    private fun commitToNative() {
        if (viewWidth <= 1f || viewHeight <= 1f) return
        if (nativeScale == scale && nativeTx == tx && nativeTy == ty) return

        val newNativeScale = scale
        val newNativeTx = tx
        val newNativeTy = ty

        try {
            val zoom = kotlin.math.log2(newNativeScale.toDouble())
            val panX = (newNativeScale - 1f) / 2f + newNativeTx / viewWidth
            val panY = (newNativeScale - 1f) / 2f + newNativeTy / viewHeight

            MPVLib.setPropertyDouble("video-zoom", zoom)
            MPVLib.setPropertyDouble("video-pan-x", panX.toDouble())
            MPVLib.setPropertyDouble("video-pan-y", panY.toDouble())
        } catch (e: Exception) { return }

        // Delay updating our native state to allow MPV ~75ms to render the sharp high-res frame.
        // Once rendered, we snap the Android View transform back to 1.0.
        commitRunnable?.let { target.removeCallbacks(it) }
        commitRunnable = Runnable {
            // Ensure the user hasn't started a new gesture during the delay
            if (!panFingerDown && !scaleDetector.isInProgress) {
                nativeScale = newNativeScale
                nativeTx = newNativeTx
                nativeTy = newNativeTy
                applyToView()
            }
        }
        target.postDelayed(commitRunnable, 75)
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)

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
            return true
        }

        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
            panFingerDown = false
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

                if (didDrag) {
                    scheduleApply()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !didDrag && (now - downTime) < DOUBLE_TAP_TIMEOUT
                panFingerDown = false

                if (!wasTap) {
                    lastTapTime = 0L
                    if (!scaleDetector.isInProgress) commitToNative()
                    return true
                }

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
                if (!scaleDetector.isInProgress) commitToNative()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                panFingerDown = false
                if (!scaleDetector.isInProgress) commitToNative()
                return true
            }
        }

        return true
    }

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

    private fun applyToView() {
        if (viewWidth <= 1f || viewHeight <= 1f) return

        // Calculate the exact visual difference between the Android View and MPV's native render.
        // This ensures buttery smooth 120Hz tracking even if MPV is busy rendering 4K frames in the background.
        val viewScale = scale / nativeScale
        val viewTx = tx - (nativeTx * viewScale)
        val viewTy = ty - (nativeTy * viewScale)

        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = viewScale
        target.scaleY = viewScale
        target.translationX = viewTx
        target.translationY = viewTy
    }

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L
    }
}