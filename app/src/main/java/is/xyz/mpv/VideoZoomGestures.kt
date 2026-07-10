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
import kotlin.math.sqrt

/**
 * Pinch-to-zoom + pan for mpv output.
 *
 * Important quality detail:
 *  - The render surface always keeps the same aspect as the player view. mpv
 *    performs the media-aspect fit inside that surface, so changing aspect ratio
 *    is a single mpv frame change rather than a second Android surface/transform
 *    transition that can briefly double-letterbox or stretch the picture.
 *  - At normal size the surface is display-sized. While zooming, the same
 *    view-aspect surface is upgraded towards the source resolution. Since only
 *    its resolution changes, zoom start/end does not change on-screen geometry.
 *  - New-file and window-exit transitions are forced back to the plain mpv/base
 *    surface so Android never animates a transformed TextureView while entering
 *    or leaving the player.
 *  - Audio-only media deliberately stays on the display-sized surface. Aspect
 *    menu changes therefore cannot trigger an expensive SurfaceTexture resize
 *    that can momentarily starve audio playback.
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

    private var renderSurfaceMode = RenderSurfaceMode.BASE
    private var audioOnlyMode = false

    // When a pinch returns close enough to normal size, finish it through the
    // same delayed reset path as double-tap. Calling reset() directly from
    // onScaleEnd still sees ScaleGestureDetector as in-progress on some devices,
    // which keeps the original-detail Android surface selected for that frame.
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

                // Switch to the original-detail buffer before the first visible zoom step.
                // Its aspect remains identical to the normal surface, so this changes detail
                // without changing the picture rectangle.
                updateRenderSurfaceForCurrentState(force = true)
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
                updateRenderSurfaceForCurrentState(force = false)
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    updateRenderSurfaceForCurrentState(force = true)
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
            immediate = false,
        )
    }

    fun setVideoPixelSize(size: Pair<Int, Int>?) {
        setVideoGeometry(
            aspect = videoAspect.takeIf { it > 0.001 },
            pixelSize = size,
            panscanValue = panscan,
            immediate = false,
        )
    }

    fun setPanscan(value: Double?) {
        setVideoGeometry(
            aspect = videoAspect.takeIf { it > 0.001 },
            pixelSize = videoPixelSizeOrNull(),
            panscanValue = value,
            immediate = false,
        )
    }

    fun setVideoGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
        immediate: Boolean = false,
    ) {
        videoAspect = aspect ?: 0.0
        videoPixelWidth = pixelSize?.first ?: 0
        videoPixelHeight = pixelSize?.second ?: 0
        panscan = panscanValue ?: 0.0

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
            immediate = true,
        )
    }

    fun setAudioOnlyMode(enabled: Boolean) {
        if (audioOnlyMode == enabled)
            return

        audioOnlyMode = enabled
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    private fun videoPixelSizeOrNull(): Pair<Int, Int>? {
        if (videoPixelWidth <= 0 || videoPixelHeight <= 0)
            return null
        return videoPixelWidth to videoPixelHeight
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchDoubleTapReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        resetTransformState()

        // Critical for scan quality: after returning to normal size, do not keep
        // the original-resolution texture and let Android minify it. Return to
        // the display-sized view-aspect surface; its geometry is identical.
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun resetForNewFile() {
        resetTransformState()
        videoAspect = 0.0
        videoPixelWidth = 0
        videoPixelHeight = 0
        panscan = 0.0
        requestBaseRenderSurfaceSize(force = true)
        applyToView()
    }

    fun prepareForVisibleMedia() {
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun prepareForWindowExit() {
        resetTransformState()
        target.alpha = 0f
        requestBaseRenderSurfaceSize(force = true)
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
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.translationX = tx.toFloat()
        target.translationY = ty.toFloat()
    }

    private fun updateRenderSurfaceForCurrentState(force: Boolean) {
        val zooming = isZoomed() || scaleDetector.isInProgress

        // Keep a view-shaped output window in every state. mpv owns letterboxing,
        // panscan and aspect override, so there is never a second Android-side fit
        // to become visible for one frame during an aspect change.
        if (zooming && !audioOnlyMode)
            requestViewAspectOriginalRenderSurfaceSize(force)
        else
            requestBaseRenderSurfaceSize(force)
    }

    private fun requestBaseRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        if (!force && renderSurfaceMode == RenderSurfaceMode.BASE)
            return

        renderSurfaceMode = RenderSurfaceMode.BASE
        player.resetRenderSurfaceSize()
    }

    private fun requestViewAspectOriginalRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()

        if (!force && renderSurfaceMode == RenderSurfaceMode.VIEW_ASPECT_ORIGINAL)
            return

        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        // Same-orientation path: keep the buffer aspect identical to the on-screen
        // view, but choose its scale so the video content rect inside it is
        // rendered at the original source resolution.
        val bufferScale = limitedOriginalDetailBufferScale(
            baseWidth = viewWidth.toDouble(),
            baseHeight = viewHeight.toDouble(),
            content = c,
        )

        val bufferWidth = ceilToIntAtLeastOne(viewWidth.toDouble() * bufferScale)
        val bufferHeight = ceilToIntAtLeastOne(viewHeight.toDouble() * bufferScale)
        player.setRenderSurfaceSize(bufferWidth, bufferHeight)
        renderSurfaceMode = RenderSurfaceMode.VIEW_ASPECT_ORIGINAL
    }

    private fun isPanscanActive(): Boolean = panscan > EPS.toDouble()

    private fun limitedOriginalDetailBufferScale(
        baseWidth: Double,
        baseHeight: Double,
        content: ContentRect,
    ): Double {
        val desired = originalDetailBufferScale(content)
        val maxEdge = max(baseWidth, baseHeight).coerceAtLeast(1.0)
        val maxByEdge = MAX_RENDER_SURFACE_EDGE / maxEdge
        val maxByPixels = sqrt(
            MAX_RENDER_SURFACE_PIXELS / (baseWidth * baseHeight).coerceAtLeast(1.0),
        )

        // Avoid requesting oversized SurfaceTexture buffers. Very wide overridden
        // ratios such as 2.35:1 on huge images can otherwise exceed the device
        // texture limit and leave the TextureView black even after resetting zoom.
        return desired
            .coerceAtMost(maxByEdge)
            .coerceAtMost(maxByPixels)
            .coerceAtLeast(1.0)
    }

    private fun originalDetailBufferScale(c: ContentRect): Double {
        val scaleX = videoPixelWidth.toDouble() / c.w.toDouble()
        val scaleY = videoPixelHeight.toDouble() / c.h.toDouble()
        return max(scaleX, scaleY).coerceAtLeast(1.0)
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
    private enum class RenderSurfaceMode {
        BASE,
        VIEW_ASPECT_ORIGINAL,
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
