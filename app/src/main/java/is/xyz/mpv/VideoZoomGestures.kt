package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pinch-to-zoom + pan for mpv output.
 *
 * Important quality detail:
 *  - Unzoomed view uses a display-sized mpv-rendered compact surface, so mpv,
 *    not Android's TextureView compositor, performs the huge downscale. This
 *    avoids moire / false-color artifacts on high-frequency scans at 720p.
 *  - After the first mpv frame is ready, the unzoomed view is prepared with the
 *    same media-aspect fit used while zoomed. The buffer starts at the exact
 *    displayed content size, then grows in a few quality-safe levels as zoom
 *    increases and reaches source detail whenever the configured limits allow.
 *  - New-file and window-exit transitions are forced back to the plain mpv/base
 *    surface so Android never animates a transformed TextureView while entering
 *    or leaving the player.
 *  - Because the geometry does not switch at zoom start/end, Android never shows
 *    the one-frame shrink/stretch tear. Because the zoom buffer has no oversized
 *    black bars, it keeps full source detail in both matching and opposite
 *    phone/media orientations.
 *
 * We do not use mpv video-pan/video-zoom for finger movement.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private val renderTarget = target as? BaseMPVView

    private var viewWidth = 0f
    private var viewHeight = 0f

    /** currently displayed aspect ratio, including video-aspect-override. 0 => unknown */
    private var videoAspect = 0.0
    private var videoPixelWidth = 0
    private var videoPixelHeight = 0
    private var panscan = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * 0.22f))

    // Linear scale factor (1.0 = normal). Translation is stored as Double so large
    // 20x offsets do not lose sub-pixel precision before being sent to the View.
    private var scale = 1f
    private var tx = 0.0
    private var ty = 0.0

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

    private var tapStartTx = 0.0
    private var tapStartTy = 0.0

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()

    private var requestedRenderSurfaceMode = RenderSurfaceMode.BASE
    private var displayedRenderSurfaceMode = RenderSurfaceMode.BASE
    private var surfaceModeTransitionInFlight: RenderSurfaceMode? = null
    private var queuedRenderSurfaceUpdate = false
    private var requestedRenderSurfaceScale = 1.0

    private var pinchInProgress = false
    private var renderResizeGeneration = 0
    private var liveResizeScheduled = false
    private var pendingLiveZoomScale = 1.0
    private var lastLiveResizeRequestUptimeMs = Long.MIN_VALUE

    // Keep the startup/exit window transitions on the plain mpv surface. Once
    // MPVActivity has a stable first frame hidden behind the startup preview, it
    // enables the compact normal surface so zoom can start/stop without a tear.
    private var normalCompactSurfacePrepared = false

    // When a pinch returns close enough to normal size, finish it through the
    // same delayed reset path as double-tap. Calling reset() directly from
    // onScaleEnd still sees ScaleGestureDetector as in-progress on some devices,
    // which can keep the enlarged Android surface selected for that frame.
    private var pendingPinchDoubleTapReset = false

    // Coalesce view property updates to vsync. We do not animate here; we only avoid
    // writing View properties multiple times in one display frame.
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
                pendingPinchDoubleTapReset = false
                panActive = false
                canBeTap = false
                pinchInProgress = true

                normalCompactSurfacePrepared = true
                if (scale <= 1f + EPS) {
                    requestLiveRenderSurfaceUpdate(
                        zoomScale = 1.0,
                        immediate = true,
                    )
                }
                applyToView()

                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)

                if (newScale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    scale = 1f
                    tx = 0.0
                    ty = 0.0
                    pendingPinchDoubleTapReset = true
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    scheduleApply()
                    return true
                }

                pendingPinchDoubleTapReset = false
                if (newScale == oldScale)
                    return true

                // Keep pinch focus stable.
                // transform: screen = scale * content + translation
                val fx = detector.focusX.toDouble()
                val fy = detector.focusY.toDouble()
                val k = (newScale / oldScale).toDouble()
                tx = (k * tx) + ((1.0 - k) * fx)
                ty = (k * ty) + ((1.0 - k) * fy)
                scale = newScale

                clampTranslationToVideoContent()
                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                requestLiveRenderSurfaceUpdate(zoomScale = newScale.toDouble(), immediate = false)
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinchInProgress = false
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    scheduleSettledRenderSurfaceUpdate()
                }
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        refreshMetricsFromTarget()
        if (isZoomed() || scaleDetector.isInProgress) {
            clampTranslationToVideoContent()
            updateRenderSurfaceForCurrentState(force = true)
            scheduleApply()
        } else {
            updateRenderSurfaceForCurrentState(force = true)
            scheduleApply()
        }
    }

    fun setVideoAspect(aspect: Double?) {
        setVideoGeometry(
            aspect = aspect,
            pixelSize = videoPixelSizeOrNull(),
            panscanValue = panscan,
            prepareNormalSurface = false,
            immediate = false,
        )
    }

    fun setVideoPixelSize(size: Pair<Int, Int>?) {
        setVideoGeometry(
            aspect = videoAspect.takeIf { it > 0.001 },
            pixelSize = size,
            panscanValue = panscan,
            prepareNormalSurface = false,
            immediate = false,
        )
    }

    fun setPanscan(value: Double?) {
        setVideoGeometry(
            aspect = videoAspect.takeIf { it > 0.001 },
            pixelSize = videoPixelSizeOrNull(),
            panscanValue = value,
            prepareNormalSurface = false,
            immediate = false,
        )
    }

    fun setVideoGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
        prepareNormalSurface: Boolean = false,
        immediate: Boolean = false,
    ) {
        videoAspect = aspect ?: 0.0
        videoPixelWidth = pixelSize?.first ?: 0
        videoPixelHeight = pixelSize?.second ?: 0
        panscan = panscanValue ?: 0.0
        cancelPendingRenderSurfaceUpdates()

        if (prepareNormalSurface)
            normalCompactSurfacePrepared = true

        if (isZoomed() || scaleDetector.isInProgress)
            clampTranslationToVideoContent()

        updateRenderSurfaceForCurrentState(force = true)
        if (immediate)
            applyToView()
        else
            scheduleApply()
    }

    fun applyPredictedAspectMenuGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
    ) {
        setVideoGeometry(
            aspect = aspect,
            pixelSize = pixelSize,
            panscanValue = panscanValue,
            prepareNormalSurface = true,
            immediate = true,
        )
    }

    private fun videoPixelSizeOrNull(): Pair<Int, Int>? {
        if (videoPixelWidth <= 0 || videoPixelHeight <= 0)
            return null
        return videoPixelWidth to videoPixelHeight
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun onSurfaceTextureFrameAvailable() {
        val completedMode = surfaceModeTransitionInFlight ?: return
        displayedRenderSurfaceMode = completedMode
        surfaceModeTransitionInFlight = null
        clampTranslationToVideoContent()
        applyToView()

        if (queuedRenderSurfaceUpdate) {
            queuedRenderSurfaceUpdate = false
            updateRenderSurfaceForCurrentState(force = true)
        }
    }

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchDoubleTapReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        resetTransformState()

        // Return to the display-sized compact surface so mpv performs the final
        // downscale and the next zoom starts from the same visual geometry.
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun resetForNewFile() {
        resetTransformState()
        videoAspect = 0.0
        videoPixelWidth = 0
        videoPixelHeight = 0
        panscan = 0.0
        normalCompactSurfacePrepared = false
        cancelPendingRenderSurfaceUpdates()
        requestBaseRenderSurfaceSize(force = true)
        commitHiddenBaseRenderSurfaceMode()
        applyToView()
    }

    fun prepareForVisibleMedia() {
        if (normalCompactSurfacePrepared)
            return

        normalCompactSurfacePrepared = true
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun prepareForWindowExit() {
        resetTransformState()
        normalCompactSurfacePrepared = false
        cancelPendingRenderSurfaceUpdates()
        target.alpha = 0f
        requestBaseRenderSurfaceSize(force = true)
        commitHiddenBaseRenderSurfaceMode()
        applyToView()
    }

    private fun resetTransformState() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

        scale = 1f
        tx = 0.0
        ty = 0.0
        panFingerDown = false
        panActive = false
        canBeTap = false
        lastTapTime = 0L
        pendingPinchDoubleTapReset = false
        pinchInProgress = false
        cancelPendingRenderSurfaceUpdates()
        resetPanFilters(0f, 0f, SystemClock.uptimeMillis())
        target.alpha = 1f
    }

    private fun resetLikeDoubleTapAfterPinch() {
        target.post {
            if (scaleDetector.isInProgress) {
                resetLikeDoubleTapAfterPinch()
                return@post
            }

            if (!pendingPinchDoubleTapReset && scale > PINCH_DOUBLE_TAP_RESET_SCALE)
                return@post

            // This is intentionally the same reset action used by double-tap,
            // but deferred until the pinch detector has fully ended so surface
            // selection follows the smooth double-tap path.
            reset()
        }
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

        // Pointer transitions during pinch:
        // If one finger lifts and another remains down, rebase pan input so there is no jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            if (e.pointerCount >= 2) {
                val upIdx = e.actionIndex
                val remainIdx = if (upIdx == 0) 1 else 0
                val x = e.getX(remainIdx)
                val y = e.getY(remainIdx)
                downX = x
                downY = y
                lastPointerX = x
                lastPointerY = y
                lastPanX = x
                lastPanY = y
                downTime = SystemClock.uptimeMillis()
                resetPanFilters(x, y, downTime)

                // Keep the remaining finger as an active one-finger pan.
                // Previously this stayed false, so the following MOVE events were
                // consumed while zoomed but ignored until every finger was lifted
                // and a fresh ACTION_DOWN was received.
                panFingerDown = true
            }
            return true
        }

        // Multi-touch, or an active pinch, is handled only by ScaleGestureDetector.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            return true
        }

        if (!isZoomed())
            return pendingPinchDoubleTapReset

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastPointerX = e.x
                lastPointerY = e.y
                lastPanX = e.x
                lastPanY = e.y
                downTime = SystemClock.uptimeMillis()

                tapStartTx = tx
                tapStartTy = ty

                panFingerDown = true
                panActive = false
                canBeTap = true
                resetPanFilters(e.x, e.y, e.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panFingerDown)
                    return true

                // Android may batch several touch points into one MOVE. Processing them in order
                // prevents input bursts from becoming uneven pan steps.
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
                val moveDist = hypot(e.x - downX, e.y - downY)
                val wasTap = canBeTap && moveDist < touchSlop && (now - downTime) < DOUBLE_TAP_TIMEOUT

                panFingerDown = false
                panActive = false
                canBeTap = false

                if (!wasTap) {
                    lastTapTime = 0L
                    resetPanFilters(lastPointerX, lastPointerY, now)
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

                // Single tap: undo any tiny pan admitted below touch slop and let Activity
                // handle tap-to-toggle controls.
                tx = tapStartTx
                ty = tapStartTy
                clampTranslationToVideoContent()
                applyToView()

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

        val distFromDown = hypot(x - downX, y - downY)
        val gestureAge = SystemClock.uptimeMillis() - downTime

        // Keep double-tap reliable: a gesture remains a tap until normal Android tap slop
        // is crossed or the press is held long enough.
        if (canBeTap && (distFromDown >= touchSlop || gestureAge >= DOUBLE_TAP_TIMEOUT)) {
            canBeTap = false
            lastTapTime = 0L
        }

        if (!panActive) {
            if (distFromDown < panStartSlop)
                return

            panActive = true
            // Avoid the first slop-crossing jump.
            lastPanX = x
            lastPanY = y
            resetPanFilters(x, y, timeMs)
            return
        }

        val params = filterParamsForCurrentScale()
        val panX: Float
        val panY: Float
        if (params.enabled) {
            panX = panFilterX.filter(x, timeMs, params)
            panY = panFilterY.filter(y, timeMs, params)
        } else {
            panX = x
            panY = y
        }

        val dx = panX - lastPanX
        val dy = panY - lastPanY
        lastPanX = panX
        lastPanY = panY

        if (dx == 0f && dy == 0f)
            return

        tx += dx.toDouble()
        ty += dy.toDouble()
        clampTranslationToVideoContent()
        scheduleApply()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun resetPanFilters(x: Float, y: Float, timeMs: Long) {
        panFilterX.reset(x, timeMs)
        panFilterY.reset(y, timeMs)
        lastPanX = x
        lastPanY = y
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

        if (isPanscanActive())
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
            tx = 0.0
            ty = 0.0
            return
        }

        val c = contentRect()
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        tx = if (contentWScaled <= viewWidth + EPS) {
            (((viewWidth - contentWScaled) * 0.5f) - scale * c.ox).toDouble()
        } else {
            val minTx = (viewWidth - scale * (c.ox + c.w)).toDouble()
            val maxTx = (-scale * c.ox).toDouble()
            tx.coerceIn(minTx, maxTx)
        }

        ty = if (contentHScaled <= viewHeight + EPS) {
            (((viewHeight - contentHScaled) * 0.5f) - scale * c.oy).toDouble()
        } else {
            val minTy = (viewHeight - scale * (c.oy + c.h)).toDouble()
            val maxTy = (-scale * c.oy).toDouble()
            ty.coerceIn(minTy, maxTy)
        }
    }

    private fun applyToView() {
        val fit = renderSurfaceFitTransform()

        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale * fit.scaleX
        target.scaleY = scale * fit.scaleY
        target.translationX = (tx + scale * fit.translationX).toFloat()
        target.translationY = (ty + scale * fit.translationY).toFloat()
    }

    private fun renderSurfaceFitTransform(): SurfaceFitTransform {
        if (!displayedRenderSurfaceMode.usesMediaAspectFit || viewWidth <= 1f || viewHeight <= 1f)
            return SurfaceFitTransform.IDENTITY

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f)
            return SurfaceFitTransform.IDENTITY

        return SurfaceFitTransform(
            scaleX = c.w / viewWidth,
            scaleY = c.h / viewHeight,
            translationX = c.ox.toDouble(),
            translationY = c.oy.toDouble(),
        )
    }

    private fun updateRenderSurfaceForCurrentState(force: Boolean) {
        refreshMetricsFromTarget()
        val zooming = isZoomed() || pinchInProgress || scaleDetector.isInProgress

        if (!zooming) {
            if (isPanscanActive() || !normalCompactSurfacePrepared)
                requestBaseRenderSurfaceSize(force)
            else
                requestMediaAspectRenderSurfaceSize(scale = 1.0, force = force)
            return
        }

        val requiredScale = if (pinchInProgress || scaleDetector.isInProgress)
            liveBufferScaleForZoom(scale.toDouble())
        else
            settledBufferScaleForZoom(scale.toDouble())

        if (isPanscanActive())
            requestViewAspectRenderSurfaceSize(requiredScale, force)
        else
            requestMediaAspectRenderSurfaceSize(requiredScale, force)
    }

    private fun requestLiveRenderSurfaceUpdate(zoomScale: Double, immediate: Boolean) {
        pendingLiveZoomScale = max(pendingLiveZoomScale, zoomScale)
        val desired = liveBufferScaleForZoom(max(scale.toDouble(), pendingLiveZoomScale))
        val needsImmediateGrowth = desired >
            requestedRenderSurfaceScale * LIVE_IMMEDIATE_GROWTH_RATIO + BUFFER_SCALE_EPS
        if (immediate || needsImmediateGrowth) {
            liveResizeScheduled = false
            renderResizeGeneration += 1
            performLiveRenderSurfaceUpdate()
            return
        }
        if (liveResizeScheduled)
            return

        liveResizeScheduled = true
        val now = SystemClock.uptimeMillis()
        val elapsed = if (lastLiveResizeRequestUptimeMs == Long.MIN_VALUE)
            LIVE_RESIZE_MIN_INTERVAL_MS
        else
            now - lastLiveResizeRequestUptimeMs
        val delay = (LIVE_RESIZE_MIN_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        val generation = ++renderResizeGeneration
        target.postDelayed({
            if (generation != renderResizeGeneration)
                return@postDelayed
            liveResizeScheduled = false
            performLiveRenderSurfaceUpdate()
        }, delay)
    }

    private fun performLiveRenderSurfaceUpdate() {
        val requestedZoom = max(scale.toDouble(), pendingLiveZoomScale)
        pendingLiveZoomScale = 1.0
        lastLiveResizeRequestUptimeMs = SystemClock.uptimeMillis()

        val required = liveBufferScaleForZoom(requestedZoom)
        val expectedMode = if (isPanscanActive())
            RenderSurfaceMode.VIEW_ASPECT_DYNAMIC
        else
            RenderSurfaceMode.MEDIA_ASPECT_DYNAMIC
        if (required <= requestedRenderSurfaceScale + BUFFER_SCALE_EPS &&
            requestedRenderSurfaceMode == expectedMode
        ) return

        if (isPanscanActive())
            requestViewAspectRenderSurfaceSize(required, force = false)
        else
            requestMediaAspectRenderSurfaceSize(required, force = false)
    }

    private fun scheduleSettledRenderSurfaceUpdate() {
        pendingLiveZoomScale = 1.0
        liveResizeScheduled = false
        val generation = ++renderResizeGeneration
        target.postDelayed({
            if (generation == renderResizeGeneration && !pinchInProgress) {
                updateRenderSurfaceForCurrentState(force = false)
            }
        }, RENDER_RESIZE_AFTER_GESTURE_MS)
    }

    private fun cancelPendingRenderSurfaceUpdates() {
        renderResizeGeneration += 1
        liveResizeScheduled = false
        pendingLiveZoomScale = 1.0
        lastLiveResizeRequestUptimeMs = Long.MIN_VALUE
    }

    private fun liveBufferScaleForZoom(zoomScale: Double): Double {
        val maximum = maximumUsefulBufferScale()
        if (maximum <= 1.0 + BUFFER_SCALE_EPS)
            return 1.0

        val minimumTarget = max(INITIAL_LIVE_BUFFER_SCALE, zoomScale * LIVE_BUFFER_HEADROOM)
        return steppedBufferScale(minimumTarget, maximum)
    }

    private fun settledBufferScaleForZoom(zoomScale: Double): Double {
        val maximum = maximumUsefulBufferScale()
        if (maximum <= 1.0 + BUFFER_SCALE_EPS)
            return 1.0

        return ceilToStep(zoomScale.coerceAtMost(maximum), SETTLED_BUFFER_SCALE_STEP)
            .coerceIn(1.0, maximum)
    }

    private fun steppedBufferScale(required: Double, maximum: Double): Double {
        if (required >= maximum - BUFFER_SCALE_EPS)
            return maximum

        var level = INITIAL_LIVE_BUFFER_SCALE
        while (level + BUFFER_SCALE_EPS < required && level < maximum) {
            level = if (level < 2.0)
                2.0
            else
                level * LIVE_BUFFER_GROWTH_FACTOR
        }
        return min(level, maximum).coerceAtLeast(1.0)
    }

    private fun maximumUsefulBufferScale(): Double {
        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1)
            return 1.0
        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f)
            return 1.0

        val baseWidth = if (isPanscanActive()) viewWidth.toDouble() else c.w.toDouble()
        val baseHeight = if (isPanscanActive()) viewHeight.toDouble() else c.h.toDouble()
        return limitedRenderSurfaceScale(
            desired = originalDetailBufferScale(c),
            baseWidth = baseWidth,
            baseHeight = baseHeight,
        )
    }

    private fun requestBaseRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        if (!force && requestedRenderSurfaceMode == RenderSurfaceMode.BASE)
            return

        player.resetRenderSurfaceSize()
        requestedRenderSurfaceScale = 1.0
        markRenderSurfaceModeRequested(RenderSurfaceMode.BASE)
    }

    private fun requestViewAspectRenderSurfaceSize(scale: Double, force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()
        if (viewWidth <= 1f || viewHeight <= 1f) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val surface = safeRenderSurfaceSize(
            baseWidth = viewWidth.toDouble(),
            baseHeight = viewHeight.toDouble(),
            desiredScale = scale,
        )
        if (!force &&
            requestedRenderSurfaceMode == RenderSurfaceMode.VIEW_ASPECT_DYNAMIC &&
            abs(requestedRenderSurfaceScale - surface.scale) <= BUFFER_SCALE_EPS
        ) return

        player.setRenderSurfaceSize(surface.width, surface.height)
        requestedRenderSurfaceScale = surface.scale
        markRenderSurfaceModeRequested(RenderSurfaceMode.VIEW_ASPECT_DYNAMIC)
    }

    private fun requestMediaAspectRenderSurfaceSize(scale: Double, force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()
        if (viewWidth <= 1f || viewHeight <= 1f || videoAspect <= 0.001) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val surface = safeRenderSurfaceSize(
            baseWidth = c.w.toDouble(),
            baseHeight = c.h.toDouble(),
            desiredScale = scale,
        )
        if (!force &&
            requestedRenderSurfaceMode == RenderSurfaceMode.MEDIA_ASPECT_DYNAMIC &&
            abs(requestedRenderSurfaceScale - surface.scale) <= BUFFER_SCALE_EPS
        ) return

        player.setRenderSurfaceSize(surface.width, surface.height)
        requestedRenderSurfaceScale = surface.scale
        markRenderSurfaceModeRequested(RenderSurfaceMode.MEDIA_ASPECT_DYNAMIC)
    }

    private fun markRenderSurfaceModeRequested(mode: RenderSurfaceMode) {
        requestedRenderSurfaceMode = mode
        val transition = surfaceModeTransitionInFlight
        if (transition != null) {
            if (mode.usesMediaAspectFit != transition.usesMediaAspectFit)
                queuedRenderSurfaceUpdate = true
            return
        }

        if (mode.usesMediaAspectFit == displayedRenderSurfaceMode.usesMediaAspectFit) {
            displayedRenderSurfaceMode = mode
        } else {
            surfaceModeTransitionInFlight = mode
        }
    }

    private fun commitHiddenBaseRenderSurfaceMode() {
        requestedRenderSurfaceMode = RenderSurfaceMode.BASE
        displayedRenderSurfaceMode = RenderSurfaceMode.BASE
        surfaceModeTransitionInFlight = null
        queuedRenderSurfaceUpdate = false
        requestedRenderSurfaceScale = 1.0
    }

    private fun isPanscanActive(): Boolean = panscan > EPS.toDouble()

    private fun limitedRenderSurfaceScale(
        desired: Double,
        baseWidth: Double,
        baseHeight: Double,
    ): Double {
        val maxEdge = max(baseWidth, baseHeight).coerceAtLeast(1.0)
        val maxByEdge = MAX_RENDER_SURFACE_EDGE / maxEdge
        val maxByPixels = sqrt(
            MAX_RENDER_SURFACE_PIXELS / (baseWidth * baseHeight).coerceAtLeast(1.0),
        )
        return desired
            .coerceAtMost(maxByEdge)
            .coerceAtMost(maxByPixels)
            .coerceAtLeast(1.0)
    }

    private fun safeRenderSurfaceSize(
        baseWidth: Double,
        baseHeight: Double,
        desiredScale: Double,
    ): SurfaceSize {
        val limitedScale = limitedRenderSurfaceScale(desiredScale, baseWidth, baseHeight)
        var width = ceilToIntAtLeastOne(baseWidth * limitedScale)
        var height = ceilToIntAtLeastOne(baseHeight * limitedScale)

        val correction = min(
            min(
                MAX_RENDER_SURFACE_EDGE / width.toDouble(),
                MAX_RENDER_SURFACE_EDGE / height.toDouble(),
            ),
            sqrt(MAX_RENDER_SURFACE_PIXELS / (width.toDouble() * height.toDouble())),
        ).coerceAtMost(1.0)

        if (correction < 1.0) {
            width = floor(width.toDouble() * correction).coerceAtLeast(1.0).toInt()
            height = floor(height.toDouble() * correction).coerceAtLeast(1.0).toInt()
        }

        val actualScale = min(
            width.toDouble() / baseWidth.coerceAtLeast(1.0),
            height.toDouble() / baseHeight.coerceAtLeast(1.0),
        ).coerceAtLeast(1.0)
        return SurfaceSize(width, height, actualScale)
    }

    private fun originalDetailBufferScale(c: ContentRect): Double {
        val scaleX = videoPixelWidth.toDouble() / c.w.toDouble()
        val scaleY = videoPixelHeight.toDouble() / c.h.toDouble()
        return max(scaleX, scaleY).coerceAtLeast(1.0)
    }

    private fun ceilToStep(value: Double, step: Double): Double {
        return ceil(value / step) * step
    }

    private fun ceilToIntAtLeastOne(value: Double): Int {
        return ceil(value)
            .coerceAtLeast(1.0)
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
    }

    private fun filterParamsForCurrentScale(): FilterParams {
        if (scale < FILTER_START_SCALE)
            return FilterParams(enabled = false, minCutoff = 0f, beta = 0f, derivativeCutoff = 0f)

        val t = ((scale - FILTER_START_SCALE) / (MAX_SCALE - FILTER_START_SCALE)).coerceIn(0f, 1f)
        val smoothT = t * t * (3f - 2f * t)
        return FilterParams(
            enabled = true,
            minCutoff = lerp(FILTER_MIN_CUTOFF_AT_START, FILTER_MIN_CUTOFF_AT_MAX, smoothT),
            beta = lerp(FILTER_BETA_AT_START, FILTER_BETA_AT_MAX, smoothT),
            derivativeCutoff = FILTER_D_CUTOFF,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)
    private data class SurfaceSize(val width: Int, val height: Int, val scale: Double)
    private data class SurfaceFitTransform(
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Double,
        val translationY: Double,
    ) {
        companion object {
            val IDENTITY = SurfaceFitTransform(1f, 1f, 0.0, 0.0)
        }
    }

    private enum class RenderSurfaceMode(val usesMediaAspectFit: Boolean) {
        BASE(false),
        VIEW_ASPECT_DYNAMIC(false),
        MEDIA_ASPECT_DYNAMIC(true),
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
                ((timeMs - previousTimeMs).toFloat() / 1000f)
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
            val tau = 1.0f / (2.0f * PI.toFloat() * cutoff.coerceAtLeast(0.001f))
            return 1.0f / (1.0f + tau / dt)
        }
    }

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val PINCH_DOUBLE_TAP_RESET_SCALE = 1.001f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val MAX_RENDER_SURFACE_EDGE = 8192.0
        private const val MAX_RENDER_SURFACE_PIXELS = MAX_RENDER_SURFACE_EDGE * MAX_RENDER_SURFACE_EDGE
        private const val INITIAL_LIVE_BUFFER_SCALE = 1.5
        private const val LIVE_BUFFER_HEADROOM = 1.12
        private const val LIVE_BUFFER_GROWTH_FACTOR = 1.5
        private const val LIVE_IMMEDIATE_GROWTH_RATIO = 1.30
        private const val SETTLED_BUFFER_SCALE_STEP = 0.25
        private const val BUFFER_SCALE_EPS = 0.001
        private const val LIVE_RESIZE_MIN_INTERVAL_MS = 48L
        private const val RENDER_RESIZE_AFTER_GESTURE_MS = 140L

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f

        // Filtering is deliberately disabled at normal zoom. It only appears when
        // finger sensor noise becomes visible because the image is deeply magnified.
        private const val FILTER_START_SCALE = 10f
        private const val FILTER_MIN_CUTOFF_AT_START = 12f
        private const val FILTER_MIN_CUTOFF_AT_MAX = 6f
        private const val FILTER_BETA_AT_START = 0.020f
        private const val FILTER_BETA_AT_MAX = 0.050f
        private const val FILTER_D_CUTOFF = 1.0f
    }
}
