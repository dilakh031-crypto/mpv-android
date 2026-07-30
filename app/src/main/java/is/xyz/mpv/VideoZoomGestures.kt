package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pinch zoom and pan for the app-owned Android renderer.
 *
 * The visible Surface never changes size. Gesture samples only update the
 * final compositor transform; mpv prepares a filtered, higher-detail FBO on a
 * separate shared GL context and the native renderer swaps it atomically when
 * it is complete.
 */
internal class VideoZoomGestures(
    private val target: MPVView,
    private val onSingleTap: () -> Unit,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    private var videoAspect = 0.0
    private var videoPixelWidth = 0
    private var videoPixelHeight = 0
    private var panscan = 0.0
    private var geometrySerial = 1L

    private var scale = 1f
    private var translationX = 0.0
    private var translationY = 0.0

    private val touchSlop =
        ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
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
    private var tapStartTranslationX = 0.0
    private var tapStartTranslationY = 0.0

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingSingleTap: Runnable? = null
    private var pendingPinchReset = false

    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()

    private val choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        refreshMetricsFromTarget()
        clampTranslationToVideoContent()
        applyToRenderer()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                pendingPinchReset = false
                panActive = false
                canBeTap = false
                resetPanFilters(
                    detector.focusX,
                    detector.focusY,
                    SystemClock.uptimeMillis(),
                )
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val newScale =
                    (oldScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)

                if (newScale <= PINCH_RESET_SCALE) {
                    scale = 1f
                    translationX = 0.0
                    translationY = 0.0
                    pendingPinchReset = true
                    resetPanFilters(
                        detector.focusX,
                        detector.focusY,
                        SystemClock.uptimeMillis(),
                    )
                    scheduleApply()
                    return true
                }

                pendingPinchReset = false
                if (newScale == oldScale)
                    return true

                // Keep the content point under the detector focus stationary.
                val ratio = (newScale / oldScale).toDouble()
                val focusX = detector.focusX.toDouble()
                val focusY = detector.focusY.toDouble()
                translationX = ratio * translationX + (1.0 - ratio) * focusX
                translationY = ratio * translationY + (1.0 - ratio) * focusY
                scale = newScale

                clampTranslationToVideoContent()
                resetPanFilters(
                    detector.focusX,
                    detector.focusY,
                    SystemClock.uptimeMillis(),
                )
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchReset || scale <= PINCH_RESET_SCALE) {
                    pendingPinchReset = true
                    resetAfterPinchEnds()
                } else {
                    resetPanFilters(
                        detector.focusX,
                        detector.focusY,
                        SystemClock.uptimeMillis(),
                    )
                    // isInProgress is false when this callback returns, so the
                    // next submission requests the exact settled resolution.
                    scheduleApply()
                }
            }
        },
    )

    fun setMetrics(width: Float, height: Float) {
        if (!width.isFinite() || !height.isFinite())
            return

        val safeWidth = width.coerceAtLeast(1f)
        val safeHeight = height.coerceAtLeast(1f)
        val changed = safeWidth != viewWidth || safeHeight != viewHeight
        viewWidth = safeWidth
        viewHeight = safeHeight
        if (changed)
            geometrySerial++
        clampTranslationToVideoContent()
        scheduleApply()
    }

    fun syncFromPlayer(
        forceGeneration: Boolean = false,
        immediate: Boolean = false,
    ) {
        val aspect = target.getEffectiveVideoAspect() ?: 0.0
        val pixels = target.getVideoPixelSize()
        val nextPixelWidth = pixels?.first ?: 0
        val nextPixelHeight = pixels?.second ?: 0
        val nextPanscan = target.getPanscan()

        val changed =
            abs(aspect - videoAspect) > GEOMETRY_EPS ||
            nextPixelWidth != videoPixelWidth ||
            nextPixelHeight != videoPixelHeight ||
            abs(nextPanscan - panscan) > GEOMETRY_EPS

        videoAspect = aspect
        videoPixelWidth = nextPixelWidth
        videoPixelHeight = nextPixelHeight
        panscan = nextPanscan
        if (changed || forceGeneration)
            geometrySerial++

        clampTranslationToVideoContent()
        if (immediate)
            applyImmediately()
        else
            scheduleApply()
    }

    fun resetForNewFile() {
        resetTransform()
        videoAspect = 0.0
        videoPixelWidth = 0
        videoPixelHeight = 0
        panscan = 0.0
        geometrySerial++
        applyImmediately()
    }

    fun prepareForWindowExit() {
        resetTransform()
        geometrySerial++
        applyImmediately()
    }

    fun release() {
        cancelPendingSingleTap()
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(event: MotionEvent): Boolean {
        return isZoomed() ||
            pendingPinchReset ||
            scaleDetector.isInProgress ||
            event.pointerCount > 1
    }

    /**
     * Returns true while zoom owns the event. A stationary one-finger tap while
     * zoomed returns false on ACTION_UP so MPVActivity can toggle its controls.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        refreshMetricsFromTarget()
        scaleDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            if (event.pointerCount >= 2) {
                val lifted = event.actionIndex
                val remaining = if (lifted == 0) 1 else 0
                val x = event.getX(remaining)
                val y = event.getY(remaining)
                downX = x
                downY = y
                lastPointerX = x
                lastPointerY = y
                lastPanX = x
                lastPanY = y
                downTime = SystemClock.uptimeMillis()
                resetPanFilters(x, y, downTime)
                panFingerDown = true
            }
            return true
        }

        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            return true
        }

        if (!isZoomed())
            return pendingPinchReset

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (lastTapTime != 0L &&
                    SystemClock.uptimeMillis() - lastTapTime <
                        DOUBLE_TAP_TIMEOUT
                ) {
                    // A possible second tap has started. Keep the timestamp for
                    // recognition, but prevent the first tap from flashing UI.
                    cancelPendingSingleTap(clearTapTime = false)
                }
                downX = event.x
                downY = event.y
                lastPointerX = event.x
                lastPointerY = event.y
                lastPanX = event.x
                lastPanY = event.y
                downTime = SystemClock.uptimeMillis()
                tapStartTranslationX = translationX
                tapStartTranslationY = translationY
                panFingerDown = true
                panActive = false
                canBeTap = true
                resetPanFilters(event.x, event.y, event.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panFingerDown)
                    return true

                for (i in 0 until event.historySize) {
                    processPanSample(
                        event.getHistoricalX(0, i),
                        event.getHistoricalY(0, i),
                        event.getHistoricalEventTime(i),
                    )
                }
                processPanSample(event.x, event.y, event.eventTime)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val distance = hypot(event.x - downX, event.y - downY)
                val wasTap =
                    canBeTap &&
                    distance < touchSlop &&
                    now - downTime < DOUBLE_TAP_TIMEOUT

                panFingerDown = false
                panActive = false
                canBeTap = false

                if (!wasTap) {
                    lastTapTime = 0L
                    resetPanFilters(lastPointerX, lastPointerY, now)
                    return true
                }

                val tapInterval = now - lastTapTime
                val tapDistance = hypot(event.x - lastTapX, event.y - lastTapY)
                if (lastTapTime != 0L &&
                    tapInterval < DOUBLE_TAP_TIMEOUT &&
                    tapDistance < touchSlop * 3f
                ) {
                    cancelPendingSingleTap()
                    reset()
                    return true
                }

                // Undo tiny movement below touch slop. Let Activity handle the tap.
                translationX = tapStartTranslationX
                translationY = tapStartTranslationY
                clampTranslationToVideoContent()
                applyImmediately()
                lastTapTime = now
                lastTapX = event.x
                lastTapY = event.y
                resetPanFilters(event.x, event.y, now)
                scheduleSingleTap(now)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                panFingerDown = false
                panActive = false
                canBeTap = false
                resetPanFilters(
                    lastPointerX,
                    lastPointerY,
                    SystemClock.uptimeMillis(),
                )
                return true
            }
        }
        return true
    }

    private fun reset() {
        resetTransform()
        applyImmediately()
    }

    private fun resetTransform() {
        cancelPendingSingleTap()
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }
        scale = 1f
        translationX = 0.0
        translationY = 0.0
        panFingerDown = false
        panActive = false
        canBeTap = false
        lastTapTime = 0L
        pendingPinchReset = false
        resetPanFilters(0f, 0f, SystemClock.uptimeMillis())
    }

    private fun scheduleSingleTap(tapTime: Long) {
        cancelPendingSingleTap(clearTapTime = false)
        val callback = Runnable {
            pendingSingleTap = null
            if (lastTapTime == tapTime && isZoomed()) {
                lastTapTime = 0L
                onSingleTap()
            }
        }
        pendingSingleTap = callback
        target.postDelayed(callback, DOUBLE_TAP_TIMEOUT)
    }

    private fun cancelPendingSingleTap(clearTapTime: Boolean = true) {
        pendingSingleTap?.let { target.removeCallbacks(it) }
        pendingSingleTap = null
        if (clearTapTime)
            lastTapTime = 0L
    }

    private fun resetAfterPinchEnds() {
        target.post {
            if (scaleDetector.isInProgress) {
                resetAfterPinchEnds()
                return@post
            }
            if (!pendingPinchReset && scale > PINCH_RESET_SCALE)
                return@post
            reset()
        }
    }

    private fun processPanSample(x: Float, y: Float, timeMs: Long) {
        lastPointerX = x
        lastPointerY = y

        val distance = hypot(x - downX, y - downY)
        val age = SystemClock.uptimeMillis() - downTime
        if (canBeTap &&
            (distance >= touchSlop || age >= DOUBLE_TAP_TIMEOUT)
        ) {
            canBeTap = false
            lastTapTime = 0L
        }

        if (!panActive) {
            if (distance < panStartSlop)
                return
            panActive = true
            lastPanX = x
            lastPanY = y
            resetPanFilters(x, y, timeMs)
            return
        }

        val params = filterParamsForCurrentScale()
        val filteredX =
            if (params.enabled) panFilterX.filter(x, timeMs, params) else x
        val filteredY =
            if (params.enabled) panFilterY.filter(y, timeMs, params) else y
        val dx = filteredX - lastPanX
        val dy = filteredY - lastPanY
        lastPanX = filteredX
        lastPanY = filteredY
        if (dx == 0f && dy == 0f)
            return

        translationX += dx.toDouble()
        translationY += dy.toDouble()
        clampTranslationToVideoContent()
        scheduleApply()
    }

    private fun scheduleApply() {
        if (applyScheduled)
            return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun applyImmediately() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }
        refreshMetricsFromTarget()
        clampTranslationToVideoContent()
        applyToRenderer()
    }

    private fun refreshMetricsFromTarget() {
        if (target.width > 1 && target.height > 1) {
            val nextWidth = target.width.toFloat()
            val nextHeight = target.height.toFloat()
            if (nextWidth != viewWidth || nextHeight != viewHeight) {
                viewWidth = nextWidth
                viewHeight = nextHeight
                geometrySerial++
            }
        }
    }

    private fun contentRect(): ContentRect {
        val width = viewWidth.coerceAtLeast(1f)
        val height = viewHeight.coerceAtLeast(1f)
        if (isPanscanActive())
            return ContentRect(0f, 0f, width, height)

        val aspect =
            if (videoAspect > GEOMETRY_EPS) videoAspect.toFloat()
            else width / height
        val viewAspect = width / height
        val contentWidth: Float
        val contentHeight: Float
        if (aspect > viewAspect) {
            contentWidth = width
            contentHeight = width / aspect
        } else {
            contentHeight = height
            contentWidth = height * aspect
        }
        return ContentRect(
            (width - contentWidth) * 0.5f,
            (height - contentHeight) * 0.5f,
            contentWidth,
            contentHeight,
        )
    }

    private fun clampTranslationToVideoContent() {
        if (viewWidth <= 1f || viewHeight <= 1f)
            return
        if (scale <= 1f + EPS) {
            translationX = 0.0
            translationY = 0.0
            return
        }

        val content = contentRect()
        val scaledWidth = scale * content.width
        val scaledHeight = scale * content.height

        translationX = if (scaledWidth <= viewWidth + EPS) {
            (
                (viewWidth - scaledWidth) * 0.5f -
                    scale * content.offsetX
            ).toDouble()
        } else {
            val minimum =
                (viewWidth - scale * (content.offsetX + content.width)).toDouble()
            val maximum = (-scale * content.offsetX).toDouble()
            translationX.coerceIn(minimum, maximum)
        }

        translationY = if (scaledHeight <= viewHeight + EPS) {
            (
                (viewHeight - scaledHeight) * 0.5f -
                    scale * content.offsetY
            ).toDouble()
        } else {
            val minimum =
                (viewHeight - scale * (content.offsetY + content.height)).toDouble()
            val maximum = (-scale * content.offsetY).toDouble()
            translationY.coerceIn(minimum, maximum)
        }
    }

    private fun applyToRenderer() {
        if (viewWidth <= 1f || viewHeight <= 1f)
            return

        val request = renderRequest()
        target.submitRenderState(
            request.width,
            request.height,
            ceilToInt(viewWidth.toDouble()),
            ceilToInt(viewHeight.toDouble()),
            scale,
            translationX.toFloat(),
            translationY.toFloat(),
            request.fitScaleX,
            request.fitScaleY,
            request.fitTranslationX,
            request.fitTranslationY,
            geometrySerial,
        )
    }

    private fun renderRequest(): RenderRequest {
        val viewPixelWidth = ceilToInt(viewWidth.toDouble())
        val viewPixelHeight = ceilToInt(viewHeight.toDouble())

        // At normal size mpv renders the complete view-sized frame, including
        // letterbox bars. Thus dscale/scale operate at the exact final size.
        if (!isZoomed()) {
            return RenderRequest(
                viewPixelWidth,
                viewPixelHeight,
                1f,
                1f,
                0f,
                0f,
            )
        }

        val content = contentRect()
        val nativeScale = nativeDetailScale(content)
        val requiredScale = min(scale.toDouble(), nativeScale)
        val renderScale =
            if (scaleDetector.isInProgress)
                quantizeQualityScale(requiredScale, nativeScale)
            else
                requiredScale

        if (isPanscanActive()) {
            return RenderRequest(
                ceilToInt(viewWidth.toDouble() * renderScale),
                ceilToInt(viewHeight.toDouble() * renderScale),
                1f,
                1f,
                0f,
                0f,
            )
        }

        return RenderRequest(
            ceilToInt(content.width.toDouble() * renderScale),
            ceilToInt(content.height.toDouble() * renderScale),
            content.width / viewWidth,
            content.height / viewHeight,
            content.offsetX,
            content.offsetY,
        )
    }

    private fun nativeDetailScale(content: ContentRect): Double {
        if (videoPixelWidth <= 1 || videoPixelHeight <= 1)
            return MAX_SCALE.toDouble()

        val displayedWidth: Double
        val displayedHeight: Double
        if (isPanscanActive()) {
            val aspect =
                if (videoAspect > GEOMETRY_EPS) videoAspect
                else videoPixelWidth.toDouble() / videoPixelHeight.toDouble()
            val viewAspect = viewWidth.toDouble() / viewHeight.toDouble()
            if (aspect > viewAspect) {
                displayedHeight = viewHeight.toDouble()
                displayedWidth = displayedHeight * aspect
            } else {
                displayedWidth = viewWidth.toDouble()
                displayedHeight = displayedWidth / aspect
            }
        } else {
            displayedWidth = content.width.toDouble()
            displayedHeight = content.height.toDouble()
        }

        return max(
            videoPixelWidth.toDouble() / displayedWidth.coerceAtLeast(1.0),
            videoPixelHeight.toDouble() / displayedHeight.coerceAtLeast(1.0),
        ).coerceAtLeast(1.0)
    }

    private fun quantizeQualityScale(required: Double, maximum: Double): Double {
        if (required <= 1.0)
            return 1.0
        val exponent = ceil(ln(required) / ln(QUALITY_STEP))
        return QUALITY_STEP.pow(exponent).coerceAtMost(maximum)
    }

    private fun isPanscanActive(): Boolean = panscan > GEOMETRY_EPS

    private fun ceilToInt(value: Double): Int {
        return ceil(value)
            .coerceAtLeast(1.0)
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
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

        val t =
            ((scale - FILTER_START_SCALE) / (MAX_SCALE - FILTER_START_SCALE))
                .coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return FilterParams(
            true,
            lerp(FILTER_MIN_CUTOFF_AT_START, FILTER_MIN_CUTOFF_AT_MAX, smooth),
            lerp(FILTER_BETA_AT_START, FILTER_BETA_AT_MAX, smooth),
            FILTER_D_CUTOFF,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t

    private data class ContentRect(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
    )

    private data class RenderRequest(
        val width: Int,
        val height: Int,
        val fitScaleX: Float,
        val fitScaleY: Float,
        val fitTranslationX: Float,
        val fitTranslationY: Float,
    )

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

            val elapsed =
                if (previousTimeMs > 0L && timeMs > previousTimeMs)
                    (timeMs - previousTimeMs).toFloat() / 1000f
                else
                    DEFAULT_FRAME_DT
            val dt = elapsed.coerceIn(MIN_FILTER_DT, MAX_FILTER_DT)
            val derivative = (value - previousRaw) / dt
            val filteredDerivative = derivativeFilter.filter(
                derivative,
                alpha(params.derivativeCutoff, dt),
            )
            val cutoff =
                params.minCutoff + params.beta * abs(filteredDerivative)
            val result = valueFilter.filter(value, alpha(cutoff, dt))

            previousRaw = value
            previousTimeMs = timeMs
            return result
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val timeConstant =
                1f / (2f * PI.toFloat() * cutoff.coerceAtLeast(0.001f))
            return 1f / (1f + timeConstant / dt)
        }
    }

    companion object {
        private const val EPS = 0.001f
        private const val GEOMETRY_EPS = 0.000001
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val PINCH_RESET_SCALE = 1.001f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val QUALITY_STEP = 1.25

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f
        private const val FILTER_START_SCALE = 10f
        private const val FILTER_MIN_CUTOFF_AT_START = 12f
        private const val FILTER_MIN_CUTOFF_AT_MAX = 6f
        private const val FILTER_BETA_AT_START = 0.020f
        private const val FILTER_BETA_AT_MAX = 0.050f
        private const val FILTER_D_CUTOFF = 1f
    }
}
