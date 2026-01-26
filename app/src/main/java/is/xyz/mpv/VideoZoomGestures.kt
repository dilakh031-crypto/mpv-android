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
 * IMPORTANT:
 *  - We zoom by transforming the Android SurfaceView (compositor-level), not via mpv video-zoom/video-pan.
 *  - Touch input MUST come from an untransformed overlay view (see player.xml: gestureLayer), otherwise
 *    Android delivers inverse-transformed coordinates and you can get feedback/jitter.
 *
 * While zoomed:
 *  - One-finger drag pans the image (seeking disabled)
 *  - Double-tap resets zoom
 *  - Pinch zoom is smooth (60fps) and video keeps playing
 *
 * Also:
 *  - When entering zoom, we enable mpv "panscan" to crop away pillar/letterboxing (black bands around video).
 */
internal class VideoZoomGestures(
    private val target: View,
    private val setPanscanEnabled: (Boolean) -> Unit = {},
) {

    private var viewWidth = 0f
    private var viewHeight = 0f

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

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var panscanOn = false

    // Coalesce view property updates to vsync to avoid SurfaceView transaction jitter.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        clampTranslation()
        applyToView()
    }

    private fun scheduleApply() {
        if (applyScheduled)
            return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun setPanscan(on: Boolean) {
        if (panscanOn == on)
            return
        panscanOn = on
        setPanscanEnabled(on)
    }

    private val scaleDetector = ScaleGestureDetector(target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Any multi-touch invalidates tap state.
                lastTapTime = 0L
                didDrag = true

                // Remove black bands as soon as user starts zooming.
                setPanscan(true)
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

                // Keep pinch focus stable (pivot (0,0) simplifies the math)
                val fx = detector.focusX
                val fy = detector.focusY
                tx += fx * (oldScale - newScale)
                ty += fy * (oldScale - newScale)
                scale = newScale

                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // If user pinched back to normal, cleanly reset.
                if (scale <= 1f + EPS)
                    reset()
            }
        })

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (isZoomed())
            scheduleApply()
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        // Cancel pending apply so we don't re-apply old values after reset.
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

        scale = 1f
        tx = 0f
        ty = 0f
        didDrag = false
        lastTapTime = 0L
        setPanscan(false)
        applyToView()
    }

    /**
     * @return true if the event should be consumed.
     *         While zoomed: pinch/pan/double-tap are consumed.
     *         Single tap returns false so the Activity can toggle controls.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        // Always feed the scale detector first.
        scaleDetector.onTouchEvent(e)

        // Pointer transitions during pinch:
        // If one finger lifts and another remains down, update lastX/lastY so we don't jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            didDrag = true

            // The event still contains both pointers, actionIndex is the lifted one.
            if (e.pointerCount >= 2) {
                val upIdx = e.actionIndex
                val remainIdx = if (upIdx == 0) 1 else 0
                lastX = e.getX(remainIdx)
                lastY = e.getY(remainIdx)
                downX = lastX
                downY = lastY
                downTime = SystemClock.uptimeMillis()
            }
            return true
        }

        // Multi-touch (or active pinch) should always be consumed.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
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
                    if (dist >= touchSlop)
                        didDrag = true
                }

                if (didDrag) {
                    // NOTE: dx/dy are in the overlay's coordinate space (untransformed, screen-like).
                    // translationX/Y are in parent coords, so apply directly.
                    tx += dx
                    ty += dy
                    scheduleApply()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !didDrag && (now - downTime) < DOUBLE_TAP_TIMEOUT

                if (!wasTap) {
                    lastTapTime = 0L
                    return true // consume so controls don't toggle after dragging
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
                return false // allow Activity to toggle controls on single tap
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                return true
            }
        }

        return true
    }

    private fun clampTranslation() {
        if (viewWidth <= 1f || viewHeight <= 1f)
            return

        if (scale <= 1f + EPS) {
            tx = 0f
            ty = 0f
            return
        }

        // With pivot(0,0): allowed translation range is [view - view*scale, 0]
        val minTx = viewWidth - (viewWidth * scale)
        val minTy = viewHeight - (viewHeight * scale)
        tx = tx.coerceIn(minTx, 0f)
        ty = ty.coerceIn(minTy, 0f)
    }

    private fun applyToView() {
        // Use pivot(0,0) for stable math.
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
        private const val MAX_SCALE = 6f
        private const val DOUBLE_TAP_TIMEOUT = 300L
    }
}
