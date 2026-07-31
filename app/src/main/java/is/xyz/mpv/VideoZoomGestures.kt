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
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

internal class VideoZoomGestures(private val target: View) {

    private var viewWidth = 0f
    private var viewHeight = 0f

    // Keep the same surface-level zoom feel as the edited implementation, while
    // applying the result through mpv properties instead of transforming the View.
    private var scale = 1f
    private var mpvPanX = 0.0
    private var mpvPanY = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * 0.22f))

    private var downX = 0f
    private var downY = 0f
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var downTime = 0L

    private var panFingerDown = false
    private var panActive = false
    private var canBeTap = false

    private var tapStartPanX = 0.0
    private var tapStartPanY = 0.0

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var pendingPinchReset = false

    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()

    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        clampTranslation()
        applyToMpv()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                pendingPinchReset = false
                panActive = false
                canBeTap = false
                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                if (viewWidth <= 1f || viewHeight <= 1f) return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)

                if (newScale <= PINCH_RESET_SCALE) {
                    scale = MIN_SCALE
                    mpvPanX = 0.0
                    mpvPanY = 0.0
                    pendingPinchReset = true
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    scheduleApply()
                    return true
                }

                pendingPinchReset = false
                if (newScale == oldScale) return true

                // mpv zooms around the center. Express the focal point relative to that
                // center before updating pan so the image stays under the fingers.
                val cx = viewWidth / 2.0
                val cy = viewHeight / 2.0
                val dfx = (detector.focusX - cx) / viewWidth
                val dfy = (detector.focusY - cy) / viewHeight
                val k = (newScale / oldScale).toDouble()

                mpvPanX = dfx - k * (dfx - mpvPanX)
                mpvPanY = dfy - k * (dfy - mpvPanY)
                scale = newScale

                clampTranslation()
                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchReset || scale <= PINCH_RESET_SCALE) {
                    reset()
                } else {
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                }
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
    }

    fun isZoomed(): Boolean = scale > MIN_SCALE + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }
        scale = MIN_SCALE
        mpvPanX = 0.0
        mpvPanY = 0.0
        panFingerDown = false
        panActive = false
        canBeTap = false
        lastTapTime = 0L
        pendingPinchReset = false
        resetPanFilters(0f, 0f, SystemClock.uptimeMillis())
        applyToMpv()
    }

    /**
     * While zoomed, pinch/pan/double-tap are consumed. A single tap returns false
     * so MPVActivity can keep its existing tap-to-toggle-controls behavior.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        refreshMetricsFromTarget()
        scaleDetector.onTouchEvent(e)

        // Continue one-finger panning smoothly after the other pinch finger lifts.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            if (e.pointerCount >= 2) {
                val upIndex = e.actionIndex
                val remainingIndex = if (upIndex == 0) 1 else 0
                val x = e.getX(remainingIndex)
                val y = e.getY(remainingIndex)
                downX = x
                downY = y
                lastPointerX = x
                lastPointerY = y
                downTime = SystemClock.uptimeMillis()
                resetPanFilters(x, y, downTime)
                panFingerDown = true
            }
            return true
        }

        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            return true
        }

        if (!isZoomed()) return pendingPinchReset

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastPointerX = e.x
                lastPointerY = e.y
                downTime = SystemClock.uptimeMillis()

                tapStartPanX = mpvPanX
                tapStartPanY = mpvPanY

                panFingerDown = true
                panActive = false
                canBeTap = true
                resetPanFilters(e.x, e.y, e.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panFingerDown) return true

                for (i in 0 until e.historySize) {
                    processPanSample(
                        e.getHistoricalX(0, i),
                        e.getHistoricalY(0, i),
                        e.getHistoricalEventTime(i),
                    )
                }
                processPanSample(e.x, e.y, e.eventTime)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val moveDistance = hypot(e.x - downX, e.y - downY)
                val wasTap = canBeTap &&
                    moveDistance < touchSlop &&
                    now - downTime < DOUBLE_TAP_TIMEOUT

                panFingerDown = false
                panActive = false
                canBeTap = false

                if (!wasTap) {
                    lastTapTime = 0L
                    resetPanFilters(lastPointerX, lastPointerY, now)
                    return true
                }

                // Match the edited project: double-tap anywhere while zoomed zooms out fully.
                val elapsed = now - lastTapTime
                val tapDistance = hypot(e.x - lastTapX, e.y - lastTapY)
                if (lastTapTime != 0L &&
                    elapsed < DOUBLE_TAP_TIMEOUT &&
                    tapDistance < touchSlop * 3f
                ) {
                    reset()
                    lastTapTime = 0L
                    return true
                }

                // Undo tiny movement admitted below normal tap slop, then let the Activity
                // process the tap as a controls toggle.
                mpvPanX = tapStartPanX
                mpvPanY = tapStartPanY
                clampTranslation()
                applyToMpv()

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                resetPanFilters(e.x, e.y, now)
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                panFingerDown = false
                panActive = false
                canBeTap = false
                resetPanFilters(lastPointerX, lastPointerY, SystemClock.uptimeMillis())
                return true
            }
        }

        return true
    }

    private fun processPanSample(x: Float, y: Float, timeMs: Long) {
        lastPointerX = x
        lastPointerY = y

        val distanceFromDown = hypot(x - downX, y - downY)
        val gestureAge = SystemClock.uptimeMillis() - downTime
        if (canBeTap &&
            (distanceFromDown >= touchSlop || gestureAge >= DOUBLE_TAP_TIMEOUT)
        ) {
            canBeTap = false
            lastTapTime = 0L
        }

        if (!panActive) {
            if (distanceFromDown < panStartSlop) return
            panActive = true
            resetPanFilters(x, y, timeMs)
            return
        }

        val params = filterParamsForCurrentScale()
        val panX = if (params.enabled) panFilterX.filter(x, timeMs, params) else x
        val panY = if (params.enabled) panFilterY.filter(y, timeMs, params) else y

        val dx = panX - lastPanX
        val dy = panY - lastPanY
        lastPanX = panX
        lastPanY = panY

        if (dx == 0f && dy == 0f) return

        // Preserve the edited project's direct "image follows finger" feel, but store
        // movement in the fractional coordinate system used by mpv.
        mpvPanX += dx / viewWidth
        mpvPanY += dy / viewHeight
        clampTranslation()
        scheduleApply()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun refreshMetricsFromTarget() {
        val width = target.width
        val height = target.height
        if (width > 1 && height > 1) {
            viewWidth = width.toFloat()
            viewHeight = height.toFloat()
        }
    }

    private fun clampTranslation() {
        if (scale <= MIN_SCALE) {
            mpvPanX = 0.0
            mpvPanY = 0.0
            return
        }

        val maxPan = (scale - MIN_SCALE) / 2.0
        mpvPanX = mpvPanX.coerceIn(-maxPan, maxPan)
        mpvPanY = mpvPanY.coerceIn(-maxPan, maxPan)
    }

    private fun applyToMpv() {
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val mpvZoom = if (scale <= MIN_SCALE) 0.0 else log2(scale.toDouble())
        try {
            MPVLib.setPropertyDouble("video-zoom", mpvZoom)
            MPVLib.setPropertyDouble("video-pan-x", mpvPanX)
            MPVLib.setPropertyDouble("video-pan-y", mpvPanY)
        } catch (_: Exception) {
            // The player can be temporarily unavailable during startup/shutdown.
        }
    }

    private fun resetPanFilters(x: Float, y: Float, timeMs: Long) {
        panFilterX.reset(x, timeMs)
        panFilterY.reset(y, timeMs)
        lastPanX = x
        lastPanY = y
    }

    private fun filterParamsForCurrentScale(): FilterParams {
        if (scale < FILTER_START_SCALE)
            return FilterParams(false, 0f, 0f, 0f)

        val t = ((scale - FILTER_START_SCALE) / (MAX_SCALE - FILTER_START_SCALE))
            .coerceIn(0f, 1f)
        val smoothT = t * t * (3f - 2f * t)
        return FilterParams(
            enabled = true,
            minCutoff = lerp(FILTER_MIN_CUTOFF_AT_START, FILTER_MIN_CUTOFF_AT_MAX, smoothT),
            beta = lerp(FILTER_BETA_AT_START, FILTER_BETA_AT_MAX, smoothT),
            derivativeCutoff = FILTER_D_CUTOFF,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class FilterParams(
        val enabled: Boolean,
        val minCutoff: Float,
        val beta: Float,
        val derivativeCutoff: Float,
    )

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
        private var previousTimeMs = 0L

        fun reset(value: Float, timeMs: Long) {
            initialized = true
            previousRaw = value
            previousTimeMs = timeMs
            valueFilter.reset(value)
            derivativeFilter.reset(0f)
        }

        fun filter(value: Float, timeMs: Long, params: FilterParams): Float {
            if (!initialized) {
                reset(value, timeMs)
                return value
            }

            val dt = if (previousTimeMs > 0L && timeMs > previousTimeMs)
                (timeMs - previousTimeMs).toFloat() / 1000f
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
            previousTimeMs = timeMs
            return filtered
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff.coerceAtLeast(0.001f))
            return 1f / (1f + tau / dt)
        }
    }

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val PINCH_RESET_SCALE = 1.001f
        private const val DOUBLE_TAP_TIMEOUT = 300L

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f

        private const val FILTER_START_SCALE = 10f
        private const val FILTER_MIN_CUTOFF_AT_START = 12f
        private const val FILTER_MIN_CUTOFF_AT_MAX = 6f
        private const val FILTER_BETA_AT_START = 0.020f
        private const val FILTER_BETA_AT_MAX = 0.050f
        private const val FILTER_D_CUTOFF = 1.0f
    }
}
