package `is`.xyz.mpv

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
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
 *  - Unzoomed view uses the normal view-sized mpv surface so mpv, not Android's
 *    TextureView compositor, performs the huge downscale. This avoids moire /
 *    false-color artifacts on high-frequency scans at 720p.
 *  - As soon as the user starts zooming, the render surface switches to an
 *    original-detail buffer so zoom keeps full source detail when the requested
 *    buffer fits the device.
 *  - If that full-detail buffer would exceed the device limit, Android still
 *    performs the gesture immediately and we progressively raise the mpv surface
 *    resolution to the highest safe size for the current zoom. This avoids
 *    black frames/OOM while allowing the image to sharpen after the pinch settles.
 *  - If the phone orientation is opposite to the media orientation, we keep a
 *    media-aspect render surface and compensate with the normal View transform.
 *    This avoids the oversized black-bar buffer that loses zoom quality without
 *    using TextureView#setTransform, so playback does not tear at zoom/reset.
 *
 * We do not use mpv video-pan/video-zoom for finger movement.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private val renderTarget = target as? BaseMPVView

    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio (rotation already applied). 0 => unknown */
    private var videoAspect = 0.0
    private var videoPixelWidth = 0
    private var videoPixelHeight = 0

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
    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0

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

    private var adaptiveRenderUpdateScheduled = false
    private val adaptiveRenderUpdateRunnable = Runnable {
        adaptiveRenderUpdateScheduled = false
        updateRenderSurfaceForCurrentState(force = false)
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
                // This keeps early zoom stages sharp without forcing Android to downscale
                // the original-size texture while the image is still unzoomed.
                updateRenderSurfaceForCurrentState(force = true)

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
                updateRenderSurfaceAfterZoomChange()
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
        videoAspect = aspect ?: 0.0
        if (isZoomed() || scaleDetector.isInProgress)
            clampTranslationToVideoContent()
        updateRenderSurfaceForCurrentState(force = true)
        scheduleApply()
    }

    fun setVideoPixelSize(size: Pair<Int, Int>?) {
        videoPixelWidth = size?.first ?: 0
        videoPixelHeight = size?.second ?: 0
        updateRenderSurfaceForCurrentState(force = true)
        scheduleApply()
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchDoubleTapReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }
        if (adaptiveRenderUpdateScheduled) {
            target.removeCallbacks(adaptiveRenderUpdateRunnable)
            adaptiveRenderUpdateScheduled = false
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

        // Critical for scan quality: after returning to normal size, do not keep
        // the original-resolution texture and let Android minify it. Let mpv draw
        // directly to the view-sized surface instead. The only exception is the
        // opposite-orientation case: there we keep a media-aspect surface so zoom
        // can start/stop without a surface-aspect switch or a one-frame tear.
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
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
        if (renderSurfaceMode != RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL || viewWidth <= 1f || viewHeight <= 1f)
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
        when {
            usesMediaAspectRenderSurface() -> requestMediaAspectRenderSurfaceSize(force)
            isZoomed() || scaleDetector.isInProgress -> requestViewAspectOriginalRenderSurfaceSize(force)
            else -> requestBaseRenderSurfaceSize(force)
        }
    }

    private fun updateRenderSurfaceAfterZoomChange() {
        if (currentOriginalDetailBufferExceedsDevice())
            scheduleAdaptiveRenderSurfaceUpdate()
        else
            updateRenderSurfaceForCurrentState(force = false)
    }

    private fun scheduleAdaptiveRenderSurfaceUpdate() {
        if (adaptiveRenderUpdateScheduled)
            return
        adaptiveRenderUpdateScheduled = true
        target.postDelayed(adaptiveRenderUpdateRunnable, ADAPTIVE_RENDER_UPDATE_DELAY_MS)
    }

    private fun requestBaseRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        if (!force && renderSurfaceMode == RenderSurfaceMode.BASE)
            return

        renderSurfaceMode = RenderSurfaceMode.BASE
        renderSurfaceWidth = 0
        renderSurfaceHeight = 0
        player.resetRenderSurfaceSize()
    }

    private fun requestViewAspectOriginalRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()

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
        // rendered at the original source resolution when possible. If that full
        // buffer is larger than the device can safely allocate, grow only up to
        // the current zoom need and clamp to the device limit.
        val bufferScale = renderBufferScaleForBase(
            baseWidth = viewWidth.toDouble(),
            baseHeight = viewHeight.toDouble(),
            content = c,
        )

        val bufferWidth = ceilToIntAtLeastOne(viewWidth.toDouble() * bufferScale)
        val bufferHeight = ceilToIntAtLeastOne(viewHeight.toDouble() * bufferScale)
        applyRenderSurfaceSize(
            player = player,
            mode = RenderSurfaceMode.VIEW_ASPECT_ORIGINAL,
            width = bufferWidth,
            height = bufferHeight,
            force = force,
        )
    }

    private fun requestMediaAspectRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()

        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f) {
            requestBaseRenderSurfaceSize(force = true)
            return
        }

        // Opposite-orientation path: do not pad the render surface to the phone's
        // portrait/landscape aspect. If the original-detail compact buffer still
        // exceeds the device limit, use the same adaptive refinement path instead
        // of requesting an impossible buffer.
        val bufferScale = renderBufferScaleForBase(
            baseWidth = c.w.toDouble(),
            baseHeight = c.h.toDouble(),
            content = c,
        )

        val bufferWidth = ceilToIntAtLeastOne(c.w.toDouble() * bufferScale)
        val bufferHeight = ceilToIntAtLeastOne(c.h.toDouble() * bufferScale)
        applyRenderSurfaceSize(
            player = player,
            mode = RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL,
            width = bufferWidth,
            height = bufferHeight,
            force = force,
        )
    }

    private fun applyRenderSurfaceSize(
        player: BaseMPVView,
        mode: RenderSurfaceMode,
        width: Int,
        height: Int,
        force: Boolean,
    ) {
        if (!force && renderSurfaceMode == mode && renderSurfaceWidth == width && renderSurfaceHeight == height)
            return

        renderSurfaceMode = mode
        renderSurfaceWidth = width
        renderSurfaceHeight = height
        player.setRenderSurfaceSize(width, height)
    }

    private fun currentOriginalDetailBufferExceedsDevice(): Boolean {
        refreshMetricsFromTarget()
        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1)
            return false

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f)
            return false

        val baseWidth: Double
        val baseHeight: Double
        if (usesMediaAspectRenderSurface()) {
            baseWidth = c.w.toDouble()
            baseHeight = c.h.toDouble()
        } else {
            baseWidth = viewWidth.toDouble()
            baseHeight = viewHeight.toDouble()
        }

        val fullScale = originalDetailBufferScale(c)
        return !bufferFitsDeviceLimits(baseWidth, baseHeight, fullScale)
    }

    private fun renderBufferScaleForBase(
        baseWidth: Double,
        baseHeight: Double,
        content: ContentRect,
    ): Double {
        val fullScale = originalDetailBufferScale(content)
        if (bufferFitsDeviceLimits(baseWidth, baseHeight, fullScale))
            return fullScale

        // Progressive fallback, used only when full source-detail is bigger than
        // the device can safely host as a single Surface/texture. Android keeps
        // the gesture smooth using the existing buffer, then this value catches
        // up after the small debounce in scheduleAdaptiveRenderSurfaceUpdate().
        val scaleNeededForCurrentZoom = max(1.0, scale.toDouble() * ADAPTIVE_ZOOM_OVERSAMPLE)
        val adaptiveScale = min(fullScale, scaleNeededForCurrentZoom)
        return clampBufferScaleToDeviceLimits(baseWidth, baseHeight, adaptiveScale)
    }

    private fun originalDetailBufferScale(c: ContentRect): Double {
        val scaleX = videoPixelWidth.toDouble() / c.w.toDouble()
        val scaleY = videoPixelHeight.toDouble() / c.h.toDouble()
        return max(scaleX, scaleY).coerceAtLeast(1.0)
    }

    private fun bufferFitsDeviceLimits(baseWidth: Double, baseHeight: Double, scale: Double): Boolean {
        val width = baseWidth * scale
        val height = baseHeight * scale
        return width <= deviceMaxTextureEdge().toDouble() &&
            height <= deviceMaxTextureEdge().toDouble() &&
            width * height <= MAX_SAFE_RENDER_BUFFER_PIXELS.toDouble()
    }

    private fun clampBufferScaleToDeviceLimits(
        baseWidth: Double,
        baseHeight: Double,
        requestedScale: Double,
    ): Double {
        var capped = requestedScale.coerceAtLeast(1.0)
        val maxEdge = deviceMaxTextureEdge().toDouble()

        if (baseWidth * capped > maxEdge)
            capped = min(capped, maxEdge / baseWidth)
        if (baseHeight * capped > maxEdge)
            capped = min(capped, maxEdge / baseHeight)

        val basePixels = (baseWidth * baseHeight).coerceAtLeast(1.0)
        val pixelScaleLimit = sqrt(MAX_SAFE_RENDER_BUFFER_PIXELS.toDouble() / basePixels)
        capped = min(capped, pixelScaleLimit)

        return capped.coerceAtLeast(1.0)
    }

    private fun ceilToIntAtLeastOne(value: Double): Int {
        return ceil(value)
            .coerceAtLeast(1.0)
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
    }

    private fun usesMediaAspectRenderSurface(): Boolean {
        if (viewWidth <= 1f || viewHeight <= 1f || videoAspect <= 0.001)
            return false

        val mediaIsLandscape = videoAspect > MEDIA_ORIENTATION_THRESHOLD
        val mediaIsPortrait = videoAspect < (1.0 / MEDIA_ORIENTATION_THRESHOLD)
        if (!mediaIsLandscape && !mediaIsPortrait)
            return false

        val viewAspect = viewWidth / viewHeight
        val viewIsLandscape = viewAspect > VIEW_ORIENTATION_THRESHOLD
        val viewIsPortrait = viewAspect < (1f / VIEW_ORIENTATION_THRESHOLD)
        if (!viewIsLandscape && !viewIsPortrait)
            return false

        return (mediaIsLandscape && viewIsPortrait) || (mediaIsPortrait && viewIsLandscape)
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

    private enum class RenderSurfaceMode {
        BASE,
        VIEW_ASPECT_ORIGINAL,
        MEDIA_ASPECT_ORIGINAL,
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
        private const val MEDIA_ORIENTATION_THRESHOLD = 1.08
        private const val VIEW_ORIENTATION_THRESHOLD = 1.08f
        private const val ADAPTIVE_RENDER_UPDATE_DELAY_MS = 120L
        private const val ADAPTIVE_ZOOM_OVERSAMPLE = 1.15
        private const val FALLBACK_MAX_TEXTURE_EDGE = 8192
        private const val MAX_SAFE_RENDER_BUFFER_PIXELS = 64_000_000L

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

        private val cachedDeviceMaxTextureEdge: Int by lazy(LazyThreadSafetyMode.NONE) {
            queryDeviceMaxTextureEdge()
        }

        private fun deviceMaxTextureEdge(): Int = cachedDeviceMaxTextureEdge

        private fun queryDeviceMaxTextureEdge(): Int {
            var display = EGL14.EGL_NO_DISPLAY
            var surface = EGL14.EGL_NO_SURFACE
            var context = EGL14.EGL_NO_CONTEXT

            return try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY)
                    return FALLBACK_MAX_TEXTURE_EDGE

                val version = IntArray(2)
                if (!EGL14.eglInitialize(display, version, 0, version, 1))
                    return FALLBACK_MAX_TEXTURE_EDGE

                val configAttribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                    numConfigs[0] <= 0 || configs[0] == null
                ) {
                    return FALLBACK_MAX_TEXTURE_EDGE
                }
                val config = configs[0] ?: return FALLBACK_MAX_TEXTURE_EDGE

                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE,
                )
                context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    contextAttribs,
                    0,
                )
                if (context == EGL14.EGL_NO_CONTEXT)
                    return FALLBACK_MAX_TEXTURE_EDGE

                val surfaceAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE,
                )
                surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
                if (surface == EGL14.EGL_NO_SURFACE)
                    return FALLBACK_MAX_TEXTURE_EDGE

                if (!EGL14.eglMakeCurrent(display, surface, surface, context))
                    return FALLBACK_MAX_TEXTURE_EDGE

                val maxTextureSize = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
                maxTextureSize[0].takeIf { it > 0 } ?: FALLBACK_MAX_TEXTURE_EDGE
            } catch (_: Throwable) {
                FALLBACK_MAX_TEXTURE_EDGE
            } finally {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != EGL14.EGL_NO_SURFACE)
                        EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT)
                        EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }
}
