package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pinch-to-zoom + pan for mpv output.
 *
 * This intentionally keeps zoom as an Android view transform instead of using
 * mpv's video-zoom/video-pan properties. Changing mpv properties during finger
 * movement goes through mpv's render/update path and is too coarse for this UI.
 *
 * The important part for high zoom levels (19x/20x) is that panning is NOT based
 * directly on the last MotionEvent coordinate. Finger sensors produce small
 * high-frequency noise; at very high zoom the eye sees this as wavy judder even
 * when the frame rate is fine. We therefore:
 *
 *  - consume MotionEvent historical samples, not only the latest point;
 *  - keep tap/double-tap detection separate from tiny pan startup;
 *  - run an adaptive One Euro filter over the requested view translation;
 *  - apply the final transform once per vsync.
 *
 * Touch input must come from an untransformed overlay view, not from [target].
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio (rotation already applied). 0 => unknown */
    private var videoAspect = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * 0.22f))

    // Linear scale factor (1.0 = normal).
    private var scale = 1f

    // tx/ty is the rendered transform. rawTx/rawTy is the exact finger target
    // before filtering. Keeping both prevents cumulative drift from the filter.
    private var tx = 0f
    private var ty = 0f
    private var rawTx = 0f
    private var rawTy = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downTime = 0L

    private var panFingerDown = false
    private var panActive = false
    private var canBeTap = false

    // If a gesture ends as a tap, undo any tiny movement caused by natural
    // finger jitter before returning false to let the normal tap UI run.
    private var tapStartTx = 0f
    private var tapStartTy = 0f
    private var tapStartRawTx = 0f
    private var tapStartRawTy = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val filterX = OneEuroFilter()
    private val filterY = OneEuroFilter()

    // Coalesce view property updates to vsync.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        clampTranslationToVideoContent()
        applyToView()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                panActive = false
                canBeTap = false
                rawTx = tx
                rawTy = ty
                resetPanFilter()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)
                if (newScale == oldScale)
                    return true

                // Keep pinch focus stable.
                // transform: screen = scale * content + translation
                val fx = detector.focusX
                val fy = detector.focusY
                val k = newScale / oldScale

                scale = newScale
                rawTx = (k * rawTx) + ((1f - k) * fx)
                rawTy = (k * rawTy) + ((1f - k) * fy)
                tx = rawTx
                ty = rawTy

                clampTranslationToVideoContent()
                resetPanFilter()
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1f + EPS)
                    reset()
                else
                    resetPanFilter()
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        refreshMetricsFromTarget()
        if (isZoomed()) {
            clampTranslationToVideoContent()
            scheduleApply()
        }
    }

    fun setVideoAspect(aspect: Double?) {
        videoAspect = aspect ?: 0.0
        if (isZoomed()) {
            clampTranslationToVideoContent()
            scheduleApply()
        }
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
        rawTx = 0f
        rawTy = 0f
        panFingerDown = false
        panActive = false
        canBeTap = false
        lastTapTime = 0L
        resetPanFilter()
        applyToView()
    }

    /**
     * @return true if the event should be consumed.
     *         While zoomed: pinch/pan/double-tap are consumed.
     *         Single tap returns false so the Activity can toggle controls.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        refreshMetricsFromTarget()

        // Always feed the scale detector first.
        scaleDetector.onTouchEvent(e)

        // Pointer transitions during pinch: if one finger remains down, rebase
        // pan input on that finger so the next one-finger event cannot jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            rawTx = tx
            rawTy = ty
            resetPanFilter()
            if (e.pointerCount >= 2) {
                val upIdx = e.actionIndex
                val remainIdx = if (upIdx == 0) 1 else 0
                val x = e.getX(remainIdx)
                val y = e.getY(remainIdx)
                downX = x
                downY = y
                lastRawX = x
                lastRawY = y
                downTime = SystemClock.uptimeMillis()
            }
            return true
        }

        // Multi-touch, or an active pinch, is handled only by ScaleGestureDetector.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            rawTx = tx
            rawTy = ty
            resetPanFilter()
            return true
        }

        if (!isZoomed())
            return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastRawX = e.x
                lastRawY = e.y
                downTime = SystemClock.uptimeMillis()

                tapStartTx = tx
                tapStartTy = ty
                tapStartRawTx = rawTx
                tapStartRawTy = rawTy

                panFingerDown = true
                panActive = false
                canBeTap = true
                resetPanFilter(e.eventTime * NS_PER_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panFingerDown)
                    return true

                // Process batched historical samples first. Android often delivers
                // touch points in bursts; using only the final point is a common
                // source of visible unevenness during slow panning.
                for (i in 0 until e.historySize) {
                    processPanSample(
                        e.getHistoricalX(0, i),
                        e.getHistoricalY(0, i),
                        e.getHistoricalEventTime(i) * NS_PER_MS,
                    )
                }
                processPanSample(e.x, e.y, e.eventTime * NS_PER_MS)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val moveDist = hypot(e.x - downX, e.y - downY)
                val wasTap = canBeTap && moveDist < touchSlop && (now - downTime) < DOUBLE_TAP_TIMEOUT

                panFingerDown = false
                panActive = false
                canBeTap = false
                resetPanFilter()

                if (!wasTap) {
                    lastTapTime = 0L
                    // The low-pass filter may intentionally lag a few pixels
                    // behind the raw finger target. Commit the visible position
                    // so the next pan starts exactly where the image stopped.
                    rawTx = tx
                    rawTy = ty
                    resetPanFilter()
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

                // Single tap: restore any microscopic jitter-pan and let the
                // Activity handle tap-to-toggle controls.
                tx = tapStartTx
                ty = tapStartTy
                rawTx = tapStartRawTx
                rawTy = tapStartRawTy
                clampTranslationToVideoContent()
                applyToView()

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                panFingerDown = false
                panActive = false
                canBeTap = false
                rawTx = tx
                rawTy = ty
                resetPanFilter()
                return true
            }
        }

        return true
    }

    private fun processPanSample(x: Float, y: Float, timeNs: Long) {
        val distFromDown = hypot(x - downX, y - downY)
        val gestureAge = SystemClock.uptimeMillis() - downTime

        // Do not let the tiny pan startup threshold break double-tap. A touch is
        // still a tap until it crosses Android's normal tap slop or is held.
        if (canBeTap && (distFromDown >= touchSlop || gestureAge >= DOUBLE_TAP_TIMEOUT)) {
            canBeTap = false
            lastTapTime = 0L
        }

        if (!panActive) {
            if (distFromDown < panStartSlop) {
                lastRawX = x
                lastRawY = y
                return
            }

            panActive = true
            // Rebase here so crossing the small startup slop does not create an
            // initial jump. Only movement after activation pans the image.
            lastRawX = x
            lastRawY = y
            resetPanFilter(timeNs)
            return
        }

        val dx = x - lastRawX
        val dy = y - lastRawY
        lastRawX = x
        lastRawY = y

        if (dx == 0f && dy == 0f)
            return

        rawTx += dx
        rawTy += dy
        clampRawTranslationToVideoContent()

        val params = filterParamsForCurrentScale()
        tx = filterX.filter(rawTx, timeNs, params)
        ty = filterY.filter(rawTy, timeNs, params)
        clampRenderedTranslationToVideoContent()
        scheduleApply()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun resetPanFilter(timeNs: Long = 0L) {
        filterX.reset(rawTx, timeNs)
        filterY.reset(rawTy, timeNs)
    }

    private fun refreshMetricsFromTarget() {
        val w = target.width
        val h = target.height
        if (w > 1 && h > 1) {
            viewWidth = w.toFloat()
            viewHeight = h.toFloat()
        }
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
            tx = 0f
            ty = 0f
            rawTx = 0f
            rawTy = 0f
            return
        }

        clampRawTranslationToVideoContent()
        clampRenderedTranslationToVideoContent()
    }

    private fun clampRawTranslationToVideoContent() {
        val clamped = clampTranslation(rawTx, rawTy)
        rawTx = clamped.x
        rawTy = clamped.y
    }

    private fun clampRenderedTranslationToVideoContent() {
        val clamped = clampTranslation(tx, ty)
        tx = clamped.x
        ty = clamped.y
    }

    private fun clampTranslation(x: Float, y: Float): Point {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return Point(0f, 0f)

        val c = contentRect()
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        val clampedX = if (contentWScaled <= viewWidth + EPS) {
            ((viewWidth - contentWScaled) * 0.5f) - scale * c.ox
        } else {
            val minTx = viewWidth - scale * (c.ox + c.w)
            val maxTx = -scale * c.ox
            x.coerceIn(minTx, maxTx)
        }

        val clampedY = if (contentHScaled <= viewHeight + EPS) {
            ((viewHeight - contentHScaled) * 0.5f) - scale * c.oy
        } else {
            val minTy = viewHeight - scale * (c.oy + c.h)
            val maxTy = -scale * c.oy
            y.coerceIn(minTy, maxTy)
        }

        return Point(clampedX, clampedY)
    }

    private fun applyToView() {
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.translationX = tx
        target.translationY = ty
    }

    private fun filterParamsForCurrentScale(): FilterParams {
        // Near normal zoom, keep the image very responsive. At 19x/20x, lower
        // the cutoff to remove the sensor's high-frequency micro-wobble. Beta
        // raises the cutoff during faster movement, so long swipes stay direct.
        val t = ((scale - 1f) / (MAX_SCALE - 1f)).coerceIn(0f, 1f)
        val smoothT = t * t * (3f - 2f * t)
        val minCutoff = lerp(MAX_RESPONSIVE_CUTOFF, MAX_ZOOM_CUTOFF, smoothT)
        val beta = lerp(RESPONSIVE_BETA, MAX_ZOOM_BETA, smoothT)
        return FilterParams(minCutoff, beta, DERIVATIVE_CUTOFF)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)
    private data class Point(val x: Float, val y: Float)
    private data class FilterParams(val minCutoff: Float, val beta: Float, val derivativeCutoff: Float)

    private class LowPassFilter {
        private var initialized = false
        private var previous = 0f

        fun reset(value: Float) {
            initialized = true
            previous = value
        }

        fun filter(value: Float, alpha: Float): Float {
            if (!initialized) {
                reset(value)
                return value
            }
            val filtered = alpha * value + (1f - alpha) * previous
            previous = filtered
            return filtered
        }
    }

    private class OneEuroFilter {
        private val valueFilter = LowPassFilter()
        private val derivativeFilter = LowPassFilter()
        private var initialized = false
        private var previousRaw = 0f
        private var previousTimeNs = 0L

        fun reset(value: Float, timeNs: Long = 0L) {
            initialized = true
            previousRaw = value
            previousTimeNs = timeNs
            valueFilter.reset(value)
            derivativeFilter.reset(0f)
        }

        fun filter(value: Float, timeNs: Long, params: FilterParams): Float {
            if (!initialized) {
                reset(value, timeNs)
                return value
            }

            val dt = if (previousTimeNs > 0L && timeNs > previousTimeNs)
                ((timeNs - previousTimeNs).toDouble() / 1_000_000_000.0).toFloat()
            else
                DEFAULT_FRAME_DT

            val safeDt = dt.coerceIn(MIN_FILTER_DT, MAX_FILTER_DT)
            val derivative = (value - previousRaw) / safeDt
            val filteredDerivative = derivativeFilter.filter(
                derivative,
                alpha(params.derivativeCutoff, safeDt),
            )
            val cutoff = params.minCutoff + params.beta * abs(filteredDerivative)
            val filtered = valueFilter.filter(value, alpha(cutoff, safeDt))

            previousRaw = value
            previousTimeNs = timeNs
            return filtered
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1.0f / (2.0f * PI.toFloat() * cutoff.coerceAtLeast(0.001f))
            return 1.0f / (1.0f + tau / dt)
        }
    }

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val NS_PER_MS = 1_000_000L

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f

        // One Euro filter tuning. These values are deliberately scale-adaptive:
        // low zoom remains immediate, maximum zoom receives the strongest
        // micro-jitter suppression.
        private const val MAX_RESPONSIVE_CUTOFF = 10.0f
        private const val MAX_ZOOM_CUTOFF = 3.2f
        private const val RESPONSIVE_BETA = 0.030f
        private const val MAX_ZOOM_BETA = 0.055f
        private const val DERIVATIVE_CUTOFF = 1.0f
    }
}
