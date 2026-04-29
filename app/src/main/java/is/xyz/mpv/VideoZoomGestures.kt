package `is`.xyz.mpv

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * High-FPS pinch-to-zoom + pan for mpv output.
 *
 * Design goals (Samsung Gallery-like):
 *  - Buttery smooth 120fps zoom/pan using pure Android hardware-accelerated View transformations.
 *  - While zoomed: one-finger pan, double-tap resets, seeking disabled.
 *  - Black bars (letter/pillarbox) are treated as "not part of video".
 */
internal class VideoZoomGestures(private val target: View) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio (rotation already applied). 0 => unknown */
    private var videoAspect = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()

    // Linear scale factor (1.0 = normal)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var didDrag = false

    private var panFingerDown = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                didDrag = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (viewWidth <= 1f || viewHeight <= 1f) return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)
                if (newScale == oldScale) return true

                // Keep pinch focus stable.
                val fx = detector.focusX
                val fy = detector.focusY
                val k = newScale / oldScale
                tx = (k * tx) + ((1f - k) * fx)
                ty = (k * ty) + ((1f - k) * fy)
                scale = newScale

                clampAndApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1f + EPS) reset()
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (isZoomed()) clampAndApply()
    }

    fun setVideoAspect(aspect: Double?) {
        videoAspect = aspect ?: 0.0
        if (isZoomed()) clampAndApply()
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
        didDrag = false
        panFingerDown = false
        lastTapTime = 0L
        applyToView()
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
            }
            return true
        }

        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
            panFingerDown = false
            return true
        }

        if (!isZoomed()) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                panFingerDown = true
                downTime = SystemClock.uptimeMillis()
                didDrag = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x
                lastY = e.y

                if (!didDrag) {
                    val dist = hypot(e.x - downX, e.y - downY)
                    if (dist >= touchSlop) didDrag = true
                }

                if (didDrag && panFingerDown) {
                    tx += dx
                    ty += dy
                    clampAndApply()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !didDrag && (now - downTime) < DOUBLE_TAP_TIMEOUT
                panFingerDown = false

                if (!wasTap) {
                    lastTapTime = 0L
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
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                panFingerDown = false
                return true
            }
        }
        return true
    }

    private fun clampAndApply() {
        if (viewWidth <= 1f || viewHeight <= 1f) return

        if (scale <= 1f + EPS) {
            tx = 0f
            ty = 0f
            applyToView()
            return
        }

        val ar = if (videoAspect > 0.001) videoAspect.toFloat() else (viewWidth / viewHeight)
        val viewAr = viewWidth / viewHeight
        val cw: Float
        val ch: Float
        if (ar > viewAr) {
            cw = viewWidth
            ch = viewWidth / ar
        } else {
            ch = viewHeight
            cw = viewHeight * ar
        }
        val ox = (viewWidth - cw) * 0.5f
        val oy = (viewHeight - ch) * 0.5f

        val contentWScaled = scale * cw
        val contentHScaled = scale * ch

        tx = if (contentWScaled <= viewWidth + EPS) {
            ((viewWidth - contentWScaled) * 0.5f) - scale * ox
        } else {
            val minTx = viewWidth - scale * (ox + cw)
            val maxTx = -scale * ox
            tx.coerceIn(minTx, maxTx)
        }

        ty = if (contentHScaled <= viewHeight + EPS) {
            ((viewHeight - contentHScaled) * 0.5f) - scale * oy
        } else {
            val minTy = viewHeight - scale * (oy + ch)
            val maxTy = -scale * oy
            ty.coerceIn(minTy, maxTy)
        }

        applyToView()
    }

    private fun applyToView() {
        // Pure Android Hardware-Accelerated View transformation (Zero Lag)
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.translationX = tx
        target.translationY = ty
    }

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L
    }
}