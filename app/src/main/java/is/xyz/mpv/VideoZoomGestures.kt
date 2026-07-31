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

    private var scale = MIN_SCALE
    private var mpvPanX = 0.0
    private var mpvPanY = 0.0

    // Unfiltered target pan. The visible pan can be filtered at very high zoom,
    // while this value continues to track the finger's exact mapped position.
    private var rawMpvPanX = 0.0
    private var rawMpvPanY = 0.0

    private var drawnVideoRect = DrawnVideoRect.EMPTY
    private var geometryDirty = true

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * 0.22f))

    private var downX = 0f
    private var downY = 0f
    private var lastPointerX = 0f
    private var lastPointerY = 0f
    private var downTime = 0L

    private var panFingerDown = false
    private var panActive = false
    private var canBeTap = false

    private var tapStartPanX = 0.0
    private var tapStartPanY = 0.0
    private var tapStartRawPanX = 0.0
    private var tapStartRawPanY = 0.0

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var pendingPinchReset = false

    // The original One Euro filter is retained. It filters the mapped pan in
    // screen-pixel space, then the result is converted back to MPV coordinates.
    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()

    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        ensureDrawnVideoRect()
        clampTranslation()
        applyToMpv()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                geometryDirty = true
                ensureDrawnVideoRect()

                lastTapTime = 0L
                pendingPinchReset = false
                panActive = false
                canBeTap = false
                rawMpvPanX = mpvPanX
                rawMpvPanY = mpvPanY
                resetPanFiltersToCurrentPan(SystemClock.uptimeMillis())
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                ensureDrawnVideoRect()
                if (!drawnVideoRect.isValid) return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)

                if (newScale <= PINCH_RESET_SCALE) {
                    scale = MIN_SCALE
                    mpvPanX = 0.0
                    mpvPanY = 0.0
                    rawMpvPanX = 0.0
                    rawMpvPanY = 0.0
                    pendingPinchReset = true
                    resetPanFiltersToCurrentPan(SystemClock.uptimeMillis())
                    scheduleApply()
                    return true
                }

                pendingPinchReset = false
                if (abs(newScale - oldScale) <= SCALE_EPS) return true

                // Preserve the exact source pixel under the pinch focus. MPV's pan
                // is a fraction of the fully scaled video, so solve the full MPV
                // placement equation before and after the scale change.
                mpvPanX = focalPanAfterScale(
                    focus = detector.focusX.toDouble(),
                    oldScale = oldScale.toDouble(),
                    newScale = newScale.toDouble(),
                    oldPan = mpvPanX,
                    axis = drawnVideoRect.horizontalAxis(),
                )
                mpvPanY = focalPanAfterScale(
                    focus = detector.focusY.toDouble(),
                    oldScale = oldScale.toDouble(),
                    newScale = newScale.toDouble(),
                    oldPan = mpvPanY,
                    axis = drawnVideoRect.verticalAxis(),
                )

                scale = newScale
                rawMpvPanX = mpvPanX
                rawMpvPanY = mpvPanY
                clampTranslation()
                resetPanFiltersToCurrentPan(SystemClock.uptimeMillis())
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchReset || scale <= PINCH_RESET_SCALE) {
                    reset()
                } else {
                    rawMpvPanX = mpvPanX
                    rawMpvPanY = mpvPanY
                    resetPanFiltersToCurrentPan(SystemClock.uptimeMillis())
                }
            }
        },
    )

    fun setMetrics(width: Float, height: Float) {
        val changed = abs(viewWidth - width) > METRIC_EPS || abs(viewHeight - height) > METRIC_EPS
        viewWidth = width
        viewHeight = height

        if (changed) {
            geometryDirty = true
            ensureDrawnVideoRect()
            if (isZoomed() || scaleDetector.isInProgress) {
                clampTranslation()
                resetPanFiltersToCurrentPan(SystemClock.uptimeMillis())
                scheduleApply()
            }
        }
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
        rawMpvPanX = 0.0
        rawMpvPanY = 0.0

        panFingerDown = false
        panActive = false
        canBeTap = false
        lastTapTime = 0L
        pendingPinchReset = false

        geometryDirty = true
        resetPanFiltersToCurrentPan(SystemClock.uptimeMillis())
        applyToMpv()
    }

    /**
     * While zoomed, pinch/pan/double-tap are consumed. A single tap returns false
     * so MPVActivity can keep its existing tap-to-toggle-controls behavior.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        refreshMetricsFromTarget()
        scaleDetector.onTouchEvent(e)

        // Continue one-finger panning without a jump after the other pinch finger lifts.
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

                rawMpvPanX = mpvPanX
                rawMpvPanY = mpvPanY
                resetPanFiltersToCurrentPan(downTime)
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
                geometryDirty = true
                ensureDrawnVideoRect()

                downX = e.x
                downY = e.y
                lastPointerX = e.x
                lastPointerY = e.y
                downTime = SystemClock.uptimeMillis()

                tapStartPanX = mpvPanX
                tapStartPanY = mpvPanY
                tapStartRawPanX = rawMpvPanX
                tapStartRawPanY = rawMpvPanY

                panFingerDown = true
                panActive = false
                canBeTap = true
                resetPanFiltersToCurrentPan(e.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panFingerDown) return true

                // Process Android's batched samples in chronological order, so one
                // display frame receives the complete continuous finger path.
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
                    rawMpvPanX = mpvPanX
                    rawMpvPanY = mpvPanY
                    resetPanFiltersToCurrentPan(now)
                    return true
                }

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

                // Undo tiny motion that stayed inside Android's tap slop, then let
                // the Activity process this as its normal controls-toggle tap.
                mpvPanX = tapStartPanX
                mpvPanY = tapStartPanY
                rawMpvPanX = tapStartRawPanX
                rawMpvPanY = tapStartRawPanY
                clampTranslation()
                applyToMpv()

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                resetPanFiltersToCurrentPan(now)
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                panFingerDown = false
                panActive = false
                canBeTap = false
                rawMpvPanX = mpvPanX
                rawMpvPanY = mpvPanY
                resetPanFiltersToCurrentPan(SystemClock.uptimeMillis())
                return true
            }
        }

        return true
    }

    private fun processPanSample(x: Float, y: Float, timeMs: Long) {
        val dx = x - lastPointerX
        val dy = y - lastPointerY
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
            rawMpvPanX = mpvPanX
            rawMpvPanY = mpvPanY
            resetPanFiltersToCurrentPan(timeMs)
            return
        }

        if (dx == 0f && dy == 0f) return

        ensureDrawnVideoRect()
        if (!drawnVideoRect.isValid) return

        val scaledVideoWidth = drawnVideoRect.width * scale.toDouble()
        val scaledVideoHeight = drawnVideoRect.height * scale.toDouble()
        if (scaledVideoWidth <= GEOMETRY_EPS || scaledVideoHeight <= GEOMETRY_EPS) return

        // Exact MPV mapping: one finger pixel becomes one displayed-video pixel.
        rawMpvPanX += dx.toDouble() / scaledVideoWidth
        rawMpvPanY += dy.toDouble() / scaledVideoHeight
        clampRawTranslation()

        val params = filterParamsForCurrentScale()

        // Keep the original filter tuning in pixel units. The raw MPV pan is first
        // mapped to its exact on-screen displacement, filtered, then divided by the
        // same scaled-video dimension to return to MPV's fractional coordinate.
        val rawMappedX = (rawMpvPanX * scaledVideoWidth).toFloat()
        val rawMappedY = (rawMpvPanY * scaledVideoHeight).toFloat()
        val mappedX = if (params.enabled)
            panFilterX.filter(rawMappedX, timeMs, params)
        else
            rawMappedX
        val mappedY = if (params.enabled)
            panFilterY.filter(rawMappedY, timeMs, params)
        else
            rawMappedY

        mpvPanX = mappedX.toDouble() / scaledVideoWidth
        mpvPanY = mappedY.toDouble() / scaledVideoHeight

        val beforeClampX = mpvPanX
        val beforeClampY = mpvPanY
        clampVisibleTranslation()

        // If filtering reached a hard boundary, synchronize its state with the
        // clamped result so the edge does not feel sticky on the next direction change.
        if (abs(beforeClampX - mpvPanX) > PAN_EPS || abs(beforeClampY - mpvPanY) > PAN_EPS) {
            rawMpvPanX = mpvPanX
            rawMpvPanY = mpvPanY
            resetPanFiltersToCurrentPan(timeMs)
        }

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
        if (width <= 1 || height <= 1) return

        val newWidth = width.toFloat()
        val newHeight = height.toFloat()
        if (abs(viewWidth - newWidth) > METRIC_EPS || abs(viewHeight - newHeight) > METRIC_EPS) {
            viewWidth = newWidth
            viewHeight = newHeight
            geometryDirty = true
        }
    }

    private fun ensureDrawnVideoRect() {
        if (!geometryDirty && drawnVideoRect.isValid) return
        drawnVideoRect = calculateDrawnVideoRect()
        geometryDirty = false
    }

    /**
     * Reproduces MPV's base video rectangle calculation before video-zoom and
     * video-pan are applied. It accounts for corrected media aspect, rotation,
     * letterboxing/pillarboxing, panscan, unscaled mode, video margins, alignment,
     * and static video-scale-x/y.
     */
    private fun calculateDrawnVideoRect(): DrawnVideoRect {
        val fullWidth = viewWidth.toDouble()
        val fullHeight = viewHeight.toDouble()
        if (fullWidth <= 1.0 || fullHeight <= 1.0) return DrawnVideoRect.EMPTY

        val keepAspect = getBooleanProperty("keepaspect") ?: true

        val leftMargin = if (keepAspect)
            marginPixels("video-margin-ratio-left", fullWidth)
        else
            0.0
        val rightMargin = if (keepAspect)
            marginPixels("video-margin-ratio-right", fullWidth)
        else
            0.0
        val topMargin = if (keepAspect)
            marginPixels("video-margin-ratio-top", fullHeight)
        else
            0.0
        val bottomMargin = if (keepAspect)
            marginPixels("video-margin-ratio-bottom", fullHeight)
        else
            0.0

        val safeHorizontalMargins = sanitizeMargins(leftMargin, rightMargin, fullWidth)
        val safeVerticalMargins = sanitizeMargins(topMargin, bottomMargin, fullHeight)

        val windowLeft = safeHorizontalMargins.first
        val windowTop = safeVerticalMargins.first
        val videoWindowWidth = fullWidth - safeHorizontalMargins.first - safeHorizontalMargins.second
        val videoWindowHeight = fullHeight - safeVerticalMargins.first - safeVerticalMargins.second

        if (videoWindowWidth <= GEOMETRY_EPS || videoWindowHeight <= GEOMETRY_EPS) {
            return DrawnVideoRect.EMPTY
        }

        val alignX = ((getDoubleProperty("video-align-x") ?: 0.0).coerceIn(-1.0, 1.0) + 1.0) / 2.0
        val alignY = ((getDoubleProperty("video-align-y") ?: 0.0).coerceIn(-1.0, 1.0) + 1.0) / 2.0

        if (!keepAspect) {
            return DrawnVideoRect(
                left = windowLeft,
                top = windowTop,
                width = videoWindowWidth,
                height = videoWindowHeight,
                viewWidth = fullWidth,
                viewHeight = fullHeight,
                windowLeft = windowLeft,
                windowTop = windowTop,
                windowWidth = videoWindowWidth,
                windowHeight = videoWindowHeight,
                alignX = alignX,
                alignY = alignY,
            )
        }

        var sourceWidth = (getIntProperty("video-params/w") ?: 0).toDouble()
        var sourceHeight = (getIntProperty("video-params/h") ?: 0).toDouble()
        var displayWidth = (getIntProperty("video-params/dw") ?: 0).toDouble()
        var displayHeight = (getIntProperty("video-params/dh") ?: 0).toDouble()

        val rotation = positiveModulo(getIntProperty("video-params/rotate") ?: 0, 360)
        if (rotation % 180 == 90) {
            val sourceSwap = sourceWidth
            sourceWidth = sourceHeight
            sourceHeight = sourceSwap

            val displaySwap = displayWidth
            displayWidth = displayHeight
            displayHeight = displaySwap
        }

        if (displayWidth <= GEOMETRY_EPS || displayHeight <= GEOMETRY_EPS) {
            var aspect = getDoubleProperty("video-params/aspect") ?: 0.0
            if (rotation % 180 == 90 && aspect > GEOMETRY_EPS) aspect = 1.0 / aspect

            if (aspect > GEOMETRY_EPS) {
                displayWidth = aspect
                displayHeight = 1.0
            } else {
                displayWidth = videoWindowWidth
                displayHeight = videoWindowHeight
            }
        }

        if (sourceWidth <= GEOMETRY_EPS || sourceHeight <= GEOMETRY_EPS) {
            sourceWidth = displayWidth
            sourceHeight = displayHeight
        }

        val panscan = (getDoubleProperty("panscan") ?: 0.0).coerceIn(0.0, 1.0)
        val unscaledMode = when (getStringProperty("video-unscaled")?.lowercase()) {
            "yes" -> 1
            "downscale-big" -> 2
            else -> 0
        }

        val monitorPixelAspect = (getDoubleProperty("monitorpixelaspect") ?: 1.0)
            .takeIf { it > GEOMETRY_EPS } ?: 1.0

        val baseSize = calculateMpvPanscanSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            unscaledMode = unscaledMode,
            windowWidth = videoWindowWidth,
            windowHeight = videoWindowHeight,
            monitorPixelAspect = monitorPixelAspect,
            panscan = panscan,
        )

        val staticScaleX = (getDoubleProperty("video-scale-x") ?: 1.0)
            .coerceAtLeast(MIN_STATIC_VIDEO_SCALE)
        val staticScaleY = (getDoubleProperty("video-scale-y") ?: 1.0)
            .coerceAtLeast(MIN_STATIC_VIDEO_SCALE)

        val drawnWidth = max(1.0, baseSize.width * staticScaleX)
        val drawnHeight = max(1.0, baseSize.height * staticScaleY)
        val left = windowLeft + (videoWindowWidth - drawnWidth) * alignX
        val top = windowTop + (videoWindowHeight - drawnHeight) * alignY

        return DrawnVideoRect(
            left = left,
            top = top,
            width = drawnWidth,
            height = drawnHeight,
            viewWidth = fullWidth,
            viewHeight = fullHeight,
            windowLeft = windowLeft,
            windowTop = windowTop,
            windowWidth = videoWindowWidth,
            windowHeight = videoWindowHeight,
            alignX = alignX,
            alignY = alignY,
        )
    }

    /** Port of MPV's aspect_calc_panscan() using doubles to avoid touch jitter. */
    private fun calculateMpvPanscanSize(
        sourceWidth: Double,
        sourceHeight: Double,
        displayWidth: Double,
        displayHeight: Double,
        unscaledMode: Int,
        windowWidth: Double,
        windowHeight: Double,
        monitorPixelAspect: Double,
        panscan: Double,
    ): VideoSize {
        var fittedWidth = windowWidth
        var fittedHeight = windowWidth / displayWidth * displayHeight / monitorPixelAspect

        if (fittedHeight > windowHeight || fittedHeight < sourceHeight) {
            val candidateWidth = windowHeight / displayHeight * displayWidth * monitorPixelAspect
            if (candidateWidth <= windowWidth) {
                fittedHeight = windowHeight
                fittedWidth = candidateWidth
            }
        }

        var panscanArea = windowHeight - fittedHeight
        var panscanWidthFactor = fittedWidth / max(fittedHeight, 1.0)
        var panscanHeightFactor = 1.0

        if (abs(panscanArea) <= GEOMETRY_EPS) {
            panscanArea = windowWidth - fittedWidth
            panscanWidthFactor = 1.0
            panscanHeightFactor = fittedHeight / max(fittedWidth, 1.0)
        }

        if (unscaledMode != 0) {
            panscanArea = 0.0
            if (unscaledMode != 2 ||
                (displayWidth <= windowWidth && displayHeight <= windowHeight)
            ) {
                fittedWidth = displayWidth * monitorPixelAspect
                fittedHeight = displayHeight
            }
        }

        return VideoSize(
            width = fittedWidth + panscanArea * panscan * panscanWidthFactor,
            height = fittedHeight + panscanArea * panscan * panscanHeightFactor,
        )
    }

    private fun focalPanAfterScale(
        focus: Double,
        oldScale: Double,
        newScale: Double,
        oldPan: Double,
        axis: AxisGeometry,
    ): Double {
        if (!axis.isValid || oldScale <= 0.0 || newScale <= 0.0) return oldPan

        val oldScaledSize = axis.baseSize * oldScale
        val newScaledSize = axis.baseSize * newScale
        if (oldScaledSize <= GEOMETRY_EPS || newScaledSize <= GEOMETRY_EPS) return oldPan

        val oldStart = axis.startWithoutPan(oldScaledSize) + oldPan * oldScaledSize
        val sourceFraction = (focus - oldStart) / oldScaledSize
        val desiredNewStart = focus - sourceFraction * newScaledSize
        val newPan = (desiredNewStart - axis.startWithoutPan(newScaledSize)) / newScaledSize

        return clampPanForAxis(newPan, newScale, axis)
    }

    private fun clampTranslation() {
        clampRawTranslation()
        clampVisibleTranslation()
    }

    private fun clampRawTranslation() {
        if (!drawnVideoRect.isValid || scale <= MIN_SCALE + SCALE_EPS) {
            rawMpvPanX = 0.0
            rawMpvPanY = 0.0
            return
        }

        rawMpvPanX = clampPanForAxis(
            rawMpvPanX,
            scale.toDouble(),
            drawnVideoRect.horizontalAxis(),
        )
        rawMpvPanY = clampPanForAxis(
            rawMpvPanY,
            scale.toDouble(),
            drawnVideoRect.verticalAxis(),
        )
    }

    private fun clampVisibleTranslation() {
        if (!drawnVideoRect.isValid || scale <= MIN_SCALE + SCALE_EPS) {
            mpvPanX = 0.0
            mpvPanY = 0.0
            return
        }

        mpvPanX = clampPanForAxis(
            mpvPanX,
            scale.toDouble(),
            drawnVideoRect.horizontalAxis(),
        )
        mpvPanY = clampPanForAxis(
            mpvPanY,
            scale.toDouble(),
            drawnVideoRect.verticalAxis(),
        )
    }

    /**
     * Keeps the full scaled-video rectangle covering the physical View on an axis.
     * If it is smaller than the View, its center is locked to the View center.
     */
    private fun clampPanForAxis(pan: Double, currentScale: Double, axis: AxisGeometry): Double {
        if (!axis.isValid) return 0.0

        val scaledSize = axis.baseSize * currentScale
        if (scaledSize <= GEOMETRY_EPS) return 0.0

        val noPanStart = axis.startWithoutPan(scaledSize)
        if (scaledSize <= axis.viewSize + GEOMETRY_EPS) {
            val centeredStart = (axis.viewSize - scaledSize) / 2.0
            return (centeredStart - noPanStart) / scaledSize
        }

        val minimumStart = axis.viewSize - scaledSize
        val maximumStart = 0.0
        val minimumPan = (minimumStart - noPanStart) / scaledSize
        val maximumPan = (maximumStart - noPanStart) / scaledSize
        return pan.coerceIn(minimumPan, maximumPan)
    }

    private fun applyToMpv() {
        val mpvZoom = if (scale <= MIN_SCALE + SCALE_EPS) 0.0 else log2(scale.toDouble())
        try {
            MPVLib.setPropertyDouble("video-zoom", mpvZoom)
            MPVLib.setPropertyDouble("video-pan-x", mpvPanX)
            MPVLib.setPropertyDouble("video-pan-y", mpvPanY)
        } catch (_: Exception) {
            // MPV can be temporarily unavailable during startup or shutdown.
        }
    }

    private fun resetPanFiltersToCurrentPan(timeMs: Long) {
        ensureDrawnVideoRect()
        val scaledWidth = drawnVideoRect.width * scale.toDouble()
        val scaledHeight = drawnVideoRect.height * scale.toDouble()
        val mappedX = if (scaledWidth > GEOMETRY_EPS) (mpvPanX * scaledWidth).toFloat() else 0f
        val mappedY = if (scaledHeight > GEOMETRY_EPS) (mpvPanY * scaledHeight).toFloat() else 0f
        panFilterX.reset(mappedX, timeMs)
        panFilterY.reset(mappedY, timeMs)
    }

    private fun filterParamsForCurrentScale(): FilterParams {
        if (scale < FILTER_START_SCALE) {
            return FilterParams(false, 0f, 0f, 0f)
        }

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

    private fun marginPixels(property: String, dimension: Double): Double {
        val ratio = (getDoubleProperty(property) ?: 0.0).coerceIn(0.0, 1.0)
        return ratio * dimension
    }

    private fun sanitizeMargins(first: Double, second: Double, dimension: Double): Pair<Double, Double> {
        val safeFirst = first.coerceIn(0.0, dimension)
        val safeSecond = second.coerceIn(0.0, dimension)
        return if (safeFirst + safeSecond >= dimension) {
            0.0 to max(0.0, dimension - 1.0)
        } else {
            safeFirst to safeSecond
        }
    }

    private fun getIntProperty(name: String): Int? = try {
        MPVLib.getPropertyInt(name)
    } catch (_: Exception) {
        null
    }

    private fun getDoubleProperty(name: String): Double? = try {
        MPVLib.getPropertyDouble(name)
    } catch (_: Exception) {
        null
    }

    private fun getBooleanProperty(name: String): Boolean? = try {
        MPVLib.getPropertyBoolean(name)
    } catch (_: Exception) {
        null
    }

    private fun getStringProperty(name: String): String? = try {
        MPVLib.getPropertyString(name)
    } catch (_: Exception) {
        null
    }

    private fun positiveModulo(value: Int, modulus: Int): Int {
        val result = value % modulus
        return if (result < 0) result + modulus else result
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class VideoSize(val width: Double, val height: Double)

    private data class AxisGeometry(
        val viewSize: Double,
        val windowStart: Double,
        val windowSize: Double,
        val baseSize: Double,
        val align: Double,
    ) {
        val isValid: Boolean
            get() = viewSize > GEOMETRY_EPS &&
                windowSize > GEOMETRY_EPS &&
                baseSize > GEOMETRY_EPS

        fun startWithoutPan(scaledSize: Double): Double {
            return windowStart + (windowSize - scaledSize) * align
        }
    }

    private data class DrawnVideoRect(
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
        val viewWidth: Double,
        val viewHeight: Double,
        val windowLeft: Double,
        val windowTop: Double,
        val windowWidth: Double,
        val windowHeight: Double,
        val alignX: Double,
        val alignY: Double,
    ) {
        val isValid: Boolean
            get() = width > GEOMETRY_EPS &&
                height > GEOMETRY_EPS &&
                viewWidth > GEOMETRY_EPS &&
                viewHeight > GEOMETRY_EPS

        fun horizontalAxis(): AxisGeometry = AxisGeometry(
            viewSize = viewWidth,
            windowStart = windowLeft,
            windowSize = windowWidth,
            baseSize = width,
            align = alignX,
        )

        fun verticalAxis(): AxisGeometry = AxisGeometry(
            viewSize = viewHeight,
            windowStart = windowTop,
            windowSize = windowHeight,
            baseSize = height,
            align = alignY,
        )

        companion object {
            val EMPTY = DrawnVideoRect(
                left = 0.0,
                top = 0.0,
                width = 0.0,
                height = 0.0,
                viewWidth = 0.0,
                viewHeight = 0.0,
                windowLeft = 0.0,
                windowTop = 0.0,
                windowWidth = 0.0,
                windowHeight = 0.0,
                alignX = 0.5,
                alignY = 0.5,
            )
        }
    }

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
        private const val SCALE_EPS = 0.000001f
        private const val METRIC_EPS = 0.5f
        private const val GEOMETRY_EPS = 0.000001
        private const val PAN_EPS = 0.000000001

        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val MIN_STATIC_VIDEO_SCALE = 0.000001
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
