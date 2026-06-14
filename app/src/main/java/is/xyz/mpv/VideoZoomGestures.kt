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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * High quality pinch-to-zoom + pan for the mpv TextureView.
 *
 * The class intentionally keeps touch input, viewport math and SurfaceTexture
 * buffer sizing separate:
 *  - [ZoomViewport] owns only scale/translation and boundary clamping.
 *  - [PanTracker] converts single-pointer touch events into stable pan/tap results.
 *  - [RenderSurfaceCoordinator] decides when mpv should render into the plain
 *    view-sized surface, the normal media-aspect compact surface, or the
 *    original-detail media-aspect surface used while zoomed.
 *
 * This preserves the fork's visible behavior while making the fragile zoom path
 * explicit: unzoomed playback uses a compact mpv-rendered surface, zoomed
 * playback upgrades the same on-screen geometry to an original-detail buffer,
 * and window/file transitions return to the plain base surface.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private val renderTarget = target as? BaseMPVView
    private val metrics = VideoMetrics()
    private val viewport = ZoomViewport()
    private val renderSurfaces = RenderSurfaceCoordinator(renderTarget, metrics)

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * PAN_START_SLOP_FACTOR))
    private val panTracker = PanTracker(touchSlop, panStartSlop)

    private val choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private var pendingPinchReset = false

    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        viewport.clamp(metrics)
        applyToView()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                panTracker.cancel(keepLastTap = false)
                pendingPinchReset = false

                // Arm the compact media-aspect geometry before the first visible
                // pinch step, then immediately upgrade its backing buffer.
                renderSurfaces.prepareNormalSurface()
                renderSurfaces.update(zoomActive = true, force = true)
                applyToView()

                panTracker.reseedFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                if (!metrics.hasUsableViewSize)
                    return true

                val shouldReset = viewport.zoomAround(
                    focusX = detector.focusX.toDouble(),
                    focusY = detector.focusY.toDouble(),
                    factor = detector.scaleFactor,
                    minScale = MIN_SCALE,
                    maxScale = MAX_SCALE,
                    resetScale = PINCH_RESET_SCALE,
                    metrics = metrics,
                )

                if (shouldReset) {
                    pendingPinchReset = true
                    panTracker.reseedFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    scheduleApply()
                    return true
                }

                pendingPinchReset = false
                panTracker.reseedFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                renderSurfaces.update(zoomActive = true, force = false)
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchReset || viewport.scale <= PINCH_RESET_SCALE) {
                    pendingPinchReset = true
                    resetAfterScaleDetectorFinishes()
                } else {
                    panTracker.reseedFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    renderSurfaces.update(zoomActive = true, force = true)
                }
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        metrics.setViewSize(width, height)
        refreshMetricsFromTarget()
        viewport.clamp(metrics)
        renderSurfaces.update(zoomActive = zoomSurfaceNeeded(), force = true)
        scheduleApply()
    }

    fun setVideoAspect(aspect: Double?) {
        metrics.videoAspect = aspect ?: 0.0
        viewport.clamp(metrics)
        renderSurfaces.update(zoomActive = zoomSurfaceNeeded(), force = true)
        scheduleApply()
    }

    fun setVideoPixelSize(size: Pair<Int, Int>?) {
        metrics.setVideoPixelSize(size)
        renderSurfaces.update(zoomActive = zoomSurfaceNeeded(), force = true)
        scheduleApply()
    }

    fun isZoomed(): Boolean = viewport.isZoomed

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return viewport.isZoomed || pendingPinchReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        resetTransformState()
        renderSurfaces.update(zoomActive = false, force = true)
        applyToView()
    }

    fun resetForNewFile() {
        resetTransformState()
        metrics.clearVideo()
        renderSurfaces.resetForNewFile()
        applyToView()
    }

    fun prepareForVisibleMedia() {
        renderSurfaces.prepareNormalSurface()
        renderSurfaces.update(zoomActive = zoomSurfaceNeeded(), force = true)
        applyToView()
    }

    fun prepareForWindowExit() {
        resetTransformState()
        renderSurfaces.prepareForWindowExit()
        target.alpha = 0f
        applyToView()
    }

    /**
     * @return true if the zoom layer consumed the event. A single tap while
     * zoomed returns false so MPVActivity can still toggle controls.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        refreshMetricsFromTarget()
        scaleDetector.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && viewport.isZoomed) {
            panTracker.rebaseToRemainingPointer(e)
            return true
        }

        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            panTracker.cancel(keepLastTap = false)
            return true
        }

        if (!viewport.isZoomed)
            return pendingPinchReset

        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panTracker.start(e, viewport)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                panTracker.move(e, viewport.scale) { dx, dy ->
                    viewport.panBy(dx.toDouble(), dy.toDouble())
                    viewport.clamp(metrics)
                    scheduleApply()
                }
                true
            }

            MotionEvent.ACTION_UP -> handlePointerUp(e)

            MotionEvent.ACTION_CANCEL -> {
                panTracker.cancel(keepLastTap = false)
                true
            }

            else -> true
        }
    }

    private fun handlePointerUp(e: MotionEvent): Boolean {
        return when (val result = panTracker.finish(e)) {
            PanResult.DoubleTap -> {
                reset()
                true
            }

            is PanResult.SingleTap -> {
                viewport.setTranslation(result.startTx, result.startTy)
                viewport.clamp(metrics)
                applyToView()
                false
            }

            PanResult.DragOrCancel -> true
        }
    }

    private fun resetTransformState() {
        cancelScheduledApply()
        viewport.reset()
        panTracker.resetAll(SystemClock.uptimeMillis())
        pendingPinchReset = false
        target.alpha = 1f
    }

    private fun resetAfterScaleDetectorFinishes() {
        target.post {
            if (scaleDetector.isInProgress) {
                resetAfterScaleDetectorFinishes()
                return@post
            }

            if (!pendingPinchReset && viewport.scale > PINCH_RESET_SCALE)
                return@post

            reset()
        }
    }

    private fun zoomSurfaceNeeded(): Boolean {
        return viewport.isZoomed || scaleDetector.isInProgress
    }

    private fun scheduleApply() {
        if (applyScheduled)
            return

        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun cancelScheduledApply() {
        if (!applyScheduled)
            return

        choreographer.removeFrameCallback(frameCallback)
        applyScheduled = false
    }

    private fun refreshMetricsFromTarget() {
        metrics.takeViewSizeFrom(target)
    }

    private fun applyToView() {
        val fit = renderSurfaces.fitTransform()
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = viewport.scale * fit.scaleX
        target.scaleY = viewport.scale * fit.scaleY
        target.translationX = (viewport.tx + viewport.scale * fit.translationX).toFloat()
        target.translationY = (viewport.ty + viewport.scale * fit.translationY).toFloat()
    }

    private class ZoomViewport {
        var scale = 1f
            private set
        var tx = 0.0
            private set
        var ty = 0.0
            private set

        val isZoomed: Boolean
            get() = scale > 1f + EPS

        fun reset() {
            scale = 1f
            tx = 0.0
            ty = 0.0
        }

        fun setTranslation(x: Double, y: Double) {
            tx = x
            ty = y
        }

        fun panBy(dx: Double, dy: Double) {
            tx += dx
            ty += dy
        }

        /**
         * Zoom around a screen-space focus point.
         *
         * @return true when the scale reached the explicit reset threshold.
         */
        fun zoomAround(
            focusX: Double,
            focusY: Double,
            factor: Float,
            minScale: Float,
            maxScale: Float,
            resetScale: Float,
            metrics: VideoMetrics,
        ): Boolean {
            val oldScale = scale
            val newScale = (oldScale * factor).coerceIn(minScale, maxScale)

            if (newScale <= resetScale) {
                reset()
                return true
            }

            if (newScale == oldScale)
                return false

            val ratio = newScale.toDouble() / oldScale.toDouble()
            tx = ratio * tx + (1.0 - ratio) * focusX
            ty = ratio * ty + (1.0 - ratio) * focusY
            scale = newScale
            clamp(metrics)
            return false
        }

        fun clamp(metrics: VideoMetrics) {
            if (!metrics.hasUsableViewSize)
                return

            if (scale <= 1f + EPS) {
                tx = 0.0
                ty = 0.0
                return
            }

            val rect = metrics.contentRect()
            val scaledW = scale * rect.width
            val scaledH = scale * rect.height

            tx = if (scaledW <= metrics.viewWidth + EPS) {
                (((metrics.viewWidth - scaledW) * 0.5f) - scale * rect.left).toDouble()
            } else {
                val minTx = (metrics.viewWidth - scale * (rect.left + rect.width)).toDouble()
                val maxTx = (-scale * rect.left).toDouble()
                tx.coerceIn(minTx, maxTx)
            }

            ty = if (scaledH <= metrics.viewHeight + EPS) {
                (((metrics.viewHeight - scaledH) * 0.5f) - scale * rect.top).toDouble()
            } else {
                val minTy = (metrics.viewHeight - scale * (rect.top + rect.height)).toDouble()
                val maxTy = (-scale * rect.top).toDouble()
                ty.coerceIn(minTy, maxTy)
            }
        }
    }

    private class VideoMetrics {
        var viewWidth = 0f
            private set
        var viewHeight = 0f
            private set
        var videoAspect = 0.0
        var videoPixelWidth = 0
            private set
        var videoPixelHeight = 0
            private set

        val hasUsableViewSize: Boolean
            get() = viewWidth > 1f && viewHeight > 1f

        val hasVideoPixels: Boolean
            get() = videoPixelWidth > 1 && videoPixelHeight > 1

        fun setViewSize(width: Float, height: Float) {
            if (width > 1f && height > 1f) {
                viewWidth = width
                viewHeight = height
            }
        }

        fun takeViewSizeFrom(view: View) {
            val w = view.width
            val h = view.height
            if (w > 1 && h > 1)
                setViewSize(w.toFloat(), h.toFloat())
        }

        fun setVideoPixelSize(size: Pair<Int, Int>?) {
            videoPixelWidth = size?.first ?: 0
            videoPixelHeight = size?.second ?: 0
        }

        fun clearVideo() {
            videoAspect = 0.0
            videoPixelWidth = 0
            videoPixelHeight = 0
        }

        fun contentRect(): ContentRect {
            if (!hasUsableViewSize)
                return ContentRect(0f, 0f, viewWidth, viewHeight)

            val aspect = if (videoAspect > 0.001) videoAspect.toFloat() else viewWidth / viewHeight
            val viewAspect = viewWidth / viewHeight
            val contentW: Float
            val contentH: Float
            if (aspect > viewAspect) {
                contentW = viewWidth
                contentH = viewWidth / aspect
            } else {
                contentH = viewHeight
                contentW = viewHeight * aspect
            }

            return ContentRect(
                left = (viewWidth - contentW) * 0.5f,
                top = (viewHeight - contentH) * 0.5f,
                width = contentW,
                height = contentH,
            )
        }
    }

    private class RenderSurfaceCoordinator(
        private val player: BaseMPVView?,
        private val metrics: VideoMetrics,
    ) {
        private var mode = RenderSurfaceMode.BASE
        private var normalSurfacePrepared = false
        private var lastWidth = 0
        private var lastHeight = 0

        fun prepareNormalSurface() {
            normalSurfacePrepared = true
        }

        fun resetForNewFile() {
            normalSurfacePrepared = false
            requestBase(force = true)
        }

        fun prepareForWindowExit() {
            normalSurfacePrepared = false
            requestBase(force = true)
        }

        fun update(zoomActive: Boolean, force: Boolean) {
            when {
                zoomActive -> requestOriginalDetail(force)
                normalSurfacePrepared -> requestMediaAspectBase(force)
                else -> requestBase(force)
            }
        }

        fun fitTransform(): SurfaceFitTransform {
            if (!mode.usesMediaAspectFit || !metrics.hasUsableViewSize)
                return SurfaceFitTransform.IDENTITY

            val rect = metrics.contentRect()
            if (rect.width <= 1f || rect.height <= 1f)
                return SurfaceFitTransform.IDENTITY

            return SurfaceFitTransform(
                scaleX = rect.width / metrics.viewWidth,
                scaleY = rect.height / metrics.viewHeight,
                translationX = rect.left.toDouble(),
                translationY = rect.top.toDouble(),
            )
        }

        private fun requestBase(force: Boolean) {
            val target = player ?: return
            if (!force && mode == RenderSurfaceMode.BASE)
                return

            mode = RenderSurfaceMode.BASE
            lastWidth = 0
            lastHeight = 0
            target.resetRenderSurfaceSize()
        }

        private fun requestMediaAspectBase(force: Boolean) {
            if (!metrics.hasUsableViewSize || metrics.videoAspect <= 0.001) {
                requestBase(force = true)
                return
            }

            val rect = metrics.contentRect()
            if (rect.width <= 1f || rect.height <= 1f) {
                requestBase(force = true)
                return
            }

            requestSurfaceSize(
                mode = RenderSurfaceMode.MEDIA_ASPECT_BASE,
                width = ceilToIntAtLeastOne(rect.width.toDouble()),
                height = ceilToIntAtLeastOne(rect.height.toDouble()),
                force = force,
            )
        }

        private fun requestOriginalDetail(force: Boolean) {
            if (!metrics.hasUsableViewSize || !metrics.hasVideoPixels) {
                requestBase(force = true)
                return
            }

            val rect = metrics.contentRect()
            if (rect.width <= 1f || rect.height <= 1f) {
                requestBase(force = true)
                return
            }

            val scale = originalDetailBufferScale(rect)
            val requestedWidth = rect.width.toDouble() * scale
            val requestedHeight = rect.height.toDouble() * scale
            val capped = capSurfaceSize(requestedWidth, requestedHeight)

            requestSurfaceSize(
                mode = RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL,
                width = capped.first,
                height = capped.second,
                force = force,
            )
        }

        private fun requestSurfaceSize(mode: RenderSurfaceMode, width: Int, height: Int, force: Boolean) {
            val target = player ?: return
            if (!force && this.mode == mode && lastWidth == width && lastHeight == height)
                return

            this.mode = mode
            lastWidth = width
            lastHeight = height
            target.setRenderSurfaceSize(width, height)
        }

        private fun originalDetailBufferScale(rect: ContentRect): Double {
            val scaleX = metrics.videoPixelWidth.toDouble() / rect.width.toDouble()
            val scaleY = metrics.videoPixelHeight.toDouble() / rect.height.toDouble()
            return max(scaleX, scaleY).coerceAtLeast(1.0)
        }

        private fun capSurfaceSize(width: Double, height: Double): Pair<Int, Int> {
            val longest = max(width, height).coerceAtLeast(1.0)
            val capScale = min(1.0, MAX_RENDER_SURFACE_EDGE / longest)
            return Pair(
                ceilToIntAtLeastOne(width * capScale),
                ceilToIntAtLeastOne(height * capScale),
            )
        }

        private fun ceilToIntAtLeastOne(value: Double): Int {
            return ceil(value)
                .coerceAtLeast(1.0)
                .coerceAtMost(Int.MAX_VALUE.toDouble())
                .toInt()
        }
    }

    private class PanTracker(
        private val touchSlop: Float,
        private val panStartSlop: Float,
    ) {
        private var activePointerId = NO_POINTER
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

        fun start(event: MotionEvent, viewport: ZoomViewport) {
            activePointerId = event.getPointerId(event.actionIndex.coerceAtLeast(0))
            downX = event.x
            downY = event.y
            lastPointerX = event.x
            lastPointerY = event.y
            lastPanX = event.x
            lastPanY = event.y
            downTime = SystemClock.uptimeMillis()
            panFingerDown = true
            panActive = false
            canBeTap = true
            tapStartTx = viewport.tx
            tapStartTy = viewport.ty
            reseedFilters(event.x, event.y, event.eventTime)
        }

        fun move(event: MotionEvent, scale: Float, onPan: (Float, Float) -> Unit) {
            if (!panFingerDown)
                return

            val index = activeIndexOrFirst(event)
            for (i in 0 until event.historySize) {
                processPanSample(
                    x = event.getHistoricalX(index, i),
                    y = event.getHistoricalY(index, i),
                    timeMs = event.getHistoricalEventTime(i),
                    scale = scale,
                    onPan = onPan,
                )
            }
            processPanSample(
                x = event.getX(index),
                y = event.getY(index),
                timeMs = event.eventTime,
                scale = scale,
                onPan = onPan,
            )
        }

        fun finish(event: MotionEvent): PanResult {
            val index = activeIndexOrFirst(event)
            val x = event.getX(index)
            val y = event.getY(index)
            val now = SystemClock.uptimeMillis()
            val moveDist = hypot(x - downX, y - downY)
            val wasTap = canBeTap && moveDist < touchSlop && (now - downTime) < DOUBLE_TAP_TIMEOUT

            panFingerDown = false
            panActive = false
            canBeTap = false
            activePointerId = NO_POINTER

            if (!wasTap) {
                lastTapTime = 0L
                reseedFilters(lastPointerX, lastPointerY, now)
                return PanResult.DragOrCancel
            }

            val dt = now - lastTapTime
            val dist = hypot(x - lastTapX, y - lastTapY)
            if (lastTapTime != 0L && dt < DOUBLE_TAP_TIMEOUT && dist < touchSlop * DOUBLE_TAP_SLOP_MULTIPLIER) {
                lastTapTime = 0L
                return PanResult.DoubleTap
            }

            lastTapTime = now
            lastTapX = x
            lastTapY = y
            reseedFilters(x, y, now)
            return PanResult.SingleTap(tapStartTx, tapStartTy)
        }

        fun rebaseToRemainingPointer(event: MotionEvent) {
            val remainingIndex = remainingPointerIndex(event)
            if (remainingIndex == null) {
                cancel(keepLastTap = false)
                return
            }

            val x = event.getX(remainingIndex)
            val y = event.getY(remainingIndex)
            val now = SystemClock.uptimeMillis()
            activePointerId = event.getPointerId(remainingIndex)
            downX = x
            downY = y
            lastPointerX = x
            lastPointerY = y
            panFingerDown = true
            panActive = false
            canBeTap = false
            downTime = now
            reseedFilters(x, y, now)
        }

        fun reseedFilters(x: Float, y: Float, timeMs: Long) {
            panFilterX.reset(x, timeMs)
            panFilterY.reset(y, timeMs)
            lastPanX = x
            lastPanY = y
        }

        fun cancel(keepLastTap: Boolean) {
            if (!keepLastTap)
                lastTapTime = 0L

            panFingerDown = false
            panActive = false
            canBeTap = false
            activePointerId = NO_POINTER
            reseedFilters(lastPointerX, lastPointerY, SystemClock.uptimeMillis())
        }

        fun resetAll(timeMs: Long) {
            activePointerId = NO_POINTER
            downX = 0f
            downY = 0f
            lastPointerX = 0f
            lastPointerY = 0f
            lastPanX = 0f
            lastPanY = 0f
            downTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            tapStartTx = 0.0
            tapStartTy = 0.0
            lastTapTime = 0L
            panFilterX.reset(0f, timeMs)
            panFilterY.reset(0f, timeMs)
        }

        private fun processPanSample(
            x: Float,
            y: Float,
            timeMs: Long,
            scale: Float,
            onPan: (Float, Float) -> Unit,
        ) {
            lastPointerX = x
            lastPointerY = y

            val distFromDown = hypot(x - downX, y - downY)
            val gestureAge = SystemClock.uptimeMillis() - downTime
            if (canBeTap && (distFromDown >= touchSlop || gestureAge >= DOUBLE_TAP_TIMEOUT)) {
                canBeTap = false
                lastTapTime = 0L
            }

            if (!panActive) {
                if (distFromDown < panStartSlop)
                    return

                panActive = true
                reseedFilters(x, y, timeMs)
                return
            }

            val filter = filterParams(scale)
            val panX: Float
            val panY: Float
            if (filter.enabled) {
                panX = panFilterX.filter(x, timeMs, filter)
                panY = panFilterY.filter(y, timeMs, filter)
            } else {
                panX = x
                panY = y
            }

            val dx = panX - lastPanX
            val dy = panY - lastPanY
            lastPanX = panX
            lastPanY = panY

            if (dx != 0f || dy != 0f)
                onPan(dx, dy)
        }

        private fun activeIndexOrFirst(event: MotionEvent): Int {
            val index = if (activePointerId != NO_POINTER) event.findPointerIndex(activePointerId) else -1
            if (index >= 0)
                return index

            activePointerId = event.getPointerId(0)
            return 0
        }

        private fun remainingPointerIndex(event: MotionEvent): Int? {
            if (event.pointerCount <= 1)
                return null

            val upIndex = event.actionIndex
            for (i in 0 until event.pointerCount) {
                if (i != upIndex)
                    return i
            }
            return null
        }
    }

    private sealed class PanResult {
        data class SingleTap(val startTx: Double, val startTy: Double) : PanResult()
        object DoubleTap : PanResult()
        object DragOrCancel : PanResult()
    }

    private data class ContentRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

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
        MEDIA_ASPECT_BASE(true),
        MEDIA_ASPECT_ORIGINAL(true),
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
            val tau = 1.0f / (2.0f * PI.toFloat() * cutoff.coerceAtLeast(0.001f))
            return 1.0f / (1.0f + tau / dt)
        }
    }

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val PINCH_RESET_SCALE = 1.001f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val DOUBLE_TAP_SLOP_MULTIPLIER = 3f
        private const val PAN_START_SLOP_FACTOR = 0.22f
        private const val MAX_RENDER_SURFACE_EDGE = 8192.0
        private const val NO_POINTER = -1

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f

        private const val FILTER_START_SCALE = 10f
        private const val FILTER_MIN_CUTOFF_AT_START = 12f
        private const val FILTER_MIN_CUTOFF_AT_MAX = 6f
        private const val FILTER_BETA_AT_START = 0.020f
        private const val FILTER_BETA_AT_MAX = 0.050f
        private const val FILTER_D_CUTOFF = 1.0f

        private fun filterParams(scale: Float): FilterParams {
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
    }
}
