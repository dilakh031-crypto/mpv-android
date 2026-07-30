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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pinch-to-zoom + pan for mpv output.
 *
 * The finger-facing transform is always a cheap TextureView transform, so pinch
 * and pan stay responsive. mpv is only rebased occasionally (at gesture end or
 * before the preview reaches an edge) and then re-renders the visible viewport
 * from the original decoded image. A modest overscan buffer supplies hidden
 * pixels around the screen, avoiding both black edges while panning and the old
 * fixed 8192-pixel full-image surface limit.
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
    // 20x offsets do not lose sub-pixel precision before conversion to mpv pan units.
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

    /** Transform represented by the frame currently stored in the TextureView. */
    private var committedMpvTransform = MpvTransform.IDENTITY
    private var pendingMpvTransform: MpvTransform? = null
    private var queuedMpvRebase = false
    private var resetSurfaceAfterMpvCommit = false
    private var lastMpvRebaseRequestMs = 0L

    private var zoomRenderSurfaceActive = false
    private var zoomRenderSurfaceScale = 1f

    init {
        renderTarget?.onVideoZoomSurfaceTextureFrameAvailable = { onMpvFrameAvailable() }
    }

    // When a pinch returns close enough to normal size, finish it through the
    // same delayed reset path as double-tap. Calling reset() directly from
    // onScaleEnd still sees ScaleGestureDetector as in-progress on some devices.
    private var pendingPinchDoubleTapReset = false

    // Coalesce cheap TextureView property writes to vsync. mpv rebases are
    // intentionally handled separately and much less frequently.
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

                // Allocate only a small screen-sized overscan buffer. Finger movement
                // itself remains a TextureView transform and never waits for mpv.
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
                maybeRebaseDuringInteraction()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    updateRenderSurfaceForCurrentState(force = true)
                    requestMpvRebase(force = true)
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
            requestMpvRebase(force = true)
        } else {
            updateRenderSurfaceForCurrentState(force = true)
        }
        scheduleApply()
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

    @Suppress("UNUSED_PARAMETER")
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

        if (isZoomed() || scaleDetector.isInProgress)
            clampTranslationToVideoContent()

        updateRenderSurfaceForCurrentState(force = true)
        if (isZoomed() || scaleDetector.isInProgress)
            requestMpvRebase(force = true)
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

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchDoubleTapReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        resetTransformState()

        // Keep the last stable preview visible while mpv returns to identity once.
        // The overscan surface is released only after that identity frame arrives.
        resetSurfaceAfterMpvCommit = true
        requestMpvRebase(force = true, preferOverscan = false)
    }

    fun resetForNewFile() {
        resetTransformState()
        videoAspect = 0.0
        videoPixelWidth = 0
        videoPixelHeight = 0
        panscan = 0.0
        resetCommittedMpvState()
        deactivateZoomRenderSurface(force = true)
        applyToView()
    }

    fun prepareForVisibleMedia() {
        if (!isZoomed())
            deactivateZoomRenderSurface(force = true)
        applyToView()
    }

    fun prepareForWindowExit() {
        resetTransformState()
        resetCommittedMpvState()
        target.alpha = 0f
        deactivateZoomRenderSurface(force = true)
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
            // but deferred until the pinch detector has fully ended.
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
                    requestMpvRebase(force = true)
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
                if (isZoomed())
                    requestMpvRebase(force = true)
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
        maybeRebaseDuringInteraction()
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

        // Preserve the release-based behavior of the modified project: while
        // panscan is active mpv already fills/crops to the complete output view.
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

    private fun isPanscanActive(): Boolean = panscan > EPS.toDouble()

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
        val committed = screenTransformFor(committedMpvTransform)
        val committedScale = committed.scale.coerceAtLeast(MIN_TRANSFORM_SCALE)
        val residualScale = (scale.toDouble() / committedScale).finiteOrOne()
        val pivotX = viewWidth.toDouble() * 0.5
        val pivotY = viewHeight.toDouble() * 0.5

        // View transformation around a center pivot:
        // final = residual * committed + (1 - residual) * pivot + translation
        val residualTx = tx - residualScale * committed.tx - (1.0 - residualScale) * pivotX
        val residualTy = ty - residualScale * committed.ty - (1.0 - residualScale) * pivotY

        target.pivotX = pivotX.toFloat()
        target.pivotY = pivotY.toFloat()
        target.scaleX = residualScale.toFloat()
        target.scaleY = residualScale.toFloat()
        target.translationX = residualTx.finiteOrZero().toFloat()
        target.translationY = residualTy.finiteOrZero().toFloat()
    }

    private fun desiredScreenTransform(): ScreenTransform {
        return ScreenTransform(scale.toDouble(), tx, ty)
    }

    private fun screenTransformFor(mpv: MpvTransform): ScreenTransform {
        val screenScale = 2.0.pow(mpv.zoom).finiteOrOne().coerceAtLeast(MIN_TRANSFORM_SCALE)
        val c = contentRect()
        if (viewWidth <= 1f || viewHeight <= 1f || c.w <= 1f || c.h <= 1f)
            return ScreenTransform(screenScale, 0.0, 0.0)

        val scaledWidth = screenScale * c.w.toDouble()
        val scaledHeight = screenScale * c.h.toDouble()
        val centeredLeft = (viewWidth.toDouble() - scaledWidth) * 0.5
        val centeredTop = (viewHeight.toDouble() - scaledHeight) * 0.5
        val desiredLeft = centeredLeft + mpv.panX * scaledWidth
        val desiredTop = centeredTop + mpv.panY * scaledHeight

        return ScreenTransform(
            scale = screenScale,
            tx = (desiredLeft - screenScale * c.ox.toDouble()).finiteOrZero(),
            ty = (desiredTop - screenScale * c.oy.toDouble()).finiteOrZero(),
        )
    }

    private fun mpvTransformFor(screen: ScreenTransform): MpvTransform {
        val safeScale = screen.scale.coerceAtLeast(MIN_TRANSFORM_SCALE)
        val zoom = ln(safeScale) / LN_2
        val c = contentRect()
        if (viewWidth <= 1f || viewHeight <= 1f || c.w <= 1f || c.h <= 1f)
            return MpvTransform(zoom, 0.0, 0.0)

        val scaledWidth = safeScale * c.w.toDouble()
        val scaledHeight = safeScale * c.h.toDouble()
        val centeredLeft = (viewWidth.toDouble() - scaledWidth) * 0.5
        val centeredTop = (viewHeight.toDouble() - scaledHeight) * 0.5
        val desiredLeft = safeScale * c.ox.toDouble() + screen.tx
        val desiredTop = safeScale * c.oy.toDouble() + screen.ty

        return MpvTransform(
            zoom = zoom.finiteOrZero(),
            panX = ((desiredLeft - centeredLeft) / scaledWidth).finiteOrZero(),
            panY = ((desiredTop - centeredTop) / scaledHeight).finiteOrZero(),
        )
    }

    /**
     * Choose an mpv transform that leaves a centered TextureView overscan around
     * the requested final transform. The extra SurfaceTexture pixels exactly
     * compensate for the small residual Android scale, preserving display-pixel
     * detail without allocating a texture as large as the source image.
     */
    private fun overscannedMpvTransformFor(desired: ScreenTransform): MpvTransform {
        val previewScale = zoomRenderSurfaceScale.toDouble().coerceAtLeast(1.0)
        val residualScale = min(previewScale, desired.scale).coerceAtLeast(1.0)
        val committedScale = desired.scale / residualScale
        val pivotX = viewWidth.toDouble() * 0.5
        val pivotY = viewHeight.toDouble() * 0.5
        val committedTx = (desired.tx - (1.0 - residualScale) * pivotX) / residualScale
        val committedTy = (desired.ty - (1.0 - residualScale) * pivotY) / residualScale

        return mpvTransformFor(ScreenTransform(committedScale, committedTx, committedTy))
    }

    private fun requestMpvRebase(
        force: Boolean,
        preferOverscan: Boolean = true,
    ) {
        val player = renderTarget ?: return
        if (!player.isRenderSurfaceAttached())
            return

        refreshMetricsFromTarget()
        if (viewWidth <= 1f || viewHeight <= 1f)
            return

        if (isZoomed() || scaleDetector.isInProgress)
            activateZoomRenderSurface(force = false)

        if (pendingMpvTransform != null) {
            queuedMpvRebase = true
            return
        }

        val desired = desiredScreenTransform()
        val requested = if (preferOverscan && desired.scale > 1.0 + EPS)
            overscannedMpvTransformFor(desired)
        else
            mpvTransformFor(desired)

        if (approximatelyEqual(committedMpvTransform, requested)) {
            if (resetSurfaceAfterMpvCommit && requested.isIdentity()) {
                resetSurfaceAfterMpvCommit = false
                deactivateZoomRenderSurface(force = true)
            }
            applyToView()
            return
        }
        if (!force && SystemClock.uptimeMillis() - lastMpvRebaseRequestMs < MIN_MPV_REBASE_INTERVAL_MS)
            return

        pendingMpvTransform = requested
        lastMpvRebaseRequestMs = SystemClock.uptimeMillis()
        try {
            MPVLib.setPropertyDouble("video-zoom", requested.zoom)
            MPVLib.setPropertyDouble("video-pan-x", requested.panX)
            MPVLib.setPropertyDouble("video-pan-y", requested.panY)
        } catch (_: Throwable) {
            pendingMpvTransform = null
        }
    }

    private fun maybeRebaseDuringInteraction() {
        if (!isZoomed() || pendingMpvTransform != null)
            return
        val now = SystemClock.uptimeMillis()
        if (now - lastMpvRebaseRequestMs < MIN_MPV_REBASE_INTERVAL_MS)
            return

        val committed = screenTransformFor(committedMpvTransform)
        val residualScale = (scale.toDouble() / committed.scale.coerceAtLeast(MIN_TRANSFORM_SCALE))
            .finiteOrOne()
        val pivotX = viewWidth.toDouble() * 0.5
        val pivotY = viewHeight.toDouble() * 0.5
        val residualTx = tx - residualScale * committed.tx - (1.0 - residualScale) * pivotX
        val residualTy = ty - residualScale * committed.ty - (1.0 - residualScale) * pivotY

        val marginX = max(0.0, (residualScale - 1.0) * viewWidth.toDouble() * 0.5)
        val marginY = max(0.0, (residualScale - 1.0) * viewHeight.toDouble() * 0.5)
        val translationNearEdge =
            (marginX <= 1.0 && abs(residualTx) > 1.0) ||
            (marginY <= 1.0 && abs(residualTy) > 1.0) ||
            (marginX > 1.0 && abs(residualTx) >= marginX * PREVIEW_REBASE_MARGIN_FRACTION) ||
            (marginY > 1.0 && abs(residualTy) >= marginY * PREVIEW_REBASE_MARGIN_FRACTION)
        val scaleOutsidePreview =
            (committed.scale > 1.0 + EPS && residualScale < MIN_PREVIEW_RESIDUAL_SCALE) ||
            residualScale > MAX_PREVIEW_RESIDUAL_SCALE

        if (translationNearEdge || scaleOutsidePreview)
            requestMpvRebase(force = true)
    }

    private fun onMpvFrameAvailable() {
        val applied = pendingMpvTransform ?: return
        pendingMpvTransform = null
        committedMpvTransform = applied

        // A reset requested while an older rebase was already in flight must not
        // briefly expose the inverse/shrunk TextureView transform. Keep the last
        // visible preview unchanged and immediately ask mpv for its identity frame.
        if (resetSurfaceAfterMpvCommit && !applied.isIdentity()) {
            queuedMpvRebase = false
            requestMpvRebase(force = true, preferOverscan = false)
            return
        }

        applyToView()
        if (resetSurfaceAfterMpvCommit && applied.isIdentity()) {
            resetSurfaceAfterMpvCommit = false
            deactivateZoomRenderSurface(force = true)
            applyToView()
        }

        if (queuedMpvRebase) {
            queuedMpvRebase = false
            target.post { requestMpvRebase(force = true) }
        }
    }

    private fun resetCommittedMpvState() {
        committedMpvTransform = MpvTransform.IDENTITY
        pendingMpvTransform = null
        queuedMpvRebase = false
        resetSurfaceAfterMpvCommit = false
        lastMpvRebaseRequestMs = 0L
    }

    private fun updateRenderSurfaceForCurrentState(force: Boolean) {
        if (isZoomed() || scaleDetector.isInProgress || resetSurfaceAfterMpvCommit)
            activateZoomRenderSurface(force)
        else
            deactivateZoomRenderSurface(force)
    }

    private fun activateZoomRenderSurface(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()
        if (viewWidth <= 1f || viewHeight <= 1f)
            return

        val baseWidth = viewWidth.toDouble()
        val baseHeight = viewHeight.toDouble()
        val maxByEdge = MAX_ZOOM_RENDER_SURFACE_EDGE / max(baseWidth, baseHeight)
        val maxByPixels = sqrt(
            MAX_ZOOM_RENDER_SURFACE_PIXELS / (baseWidth * baseHeight).coerceAtLeast(1.0),
        )
        val requestedScale = min(
            ZOOM_RENDER_SURFACE_SCALE.toDouble(),
            min(maxByEdge, maxByPixels),
        ).coerceAtLeast(1.0).toFloat()

        if (!force && zoomRenderSurfaceActive &&
            abs(requestedScale - zoomRenderSurfaceScale) <= SURFACE_SCALE_EPS
        ) return

        zoomRenderSurfaceActive = true
        zoomRenderSurfaceScale = requestedScale
        val bufferWidth = ceil(baseWidth * requestedScale).coerceAtLeast(1.0).toInt()
        val bufferHeight = ceil(baseHeight * requestedScale).coerceAtLeast(1.0).toInt()
        player.setRenderSurfaceSize(bufferWidth, bufferHeight)
    }

    private fun deactivateZoomRenderSurface(force: Boolean) {
        val player = renderTarget ?: return
        if (!force && !zoomRenderSurfaceActive)
            return

        zoomRenderSurfaceActive = false
        zoomRenderSurfaceScale = 1f
        player.resetRenderSurfaceSize()
    }

    private fun approximatelyEqual(a: MpvTransform, b: MpvTransform): Boolean {
        return abs(a.zoom - b.zoom) <= MPV_PROPERTY_EPS &&
            abs(a.panX - b.panX) <= MPV_PROPERTY_EPS &&
            abs(a.panY - b.panY) <= MPV_PROPERTY_EPS
    }

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
    private fun Double.finiteOrOne(): Double = if (isFinite()) this else 1.0

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
    private data class ScreenTransform(val scale: Double, val tx: Double, val ty: Double)
    private data class MpvTransform(val zoom: Double, val panX: Double, val panY: Double) {
        fun isIdentity(): Boolean {
            return abs(zoom) <= MPV_PROPERTY_EPS &&
                abs(panX) <= MPV_PROPERTY_EPS &&
                abs(panY) <= MPV_PROPERTY_EPS
        }

        companion object {
            val IDENTITY = MpvTransform(0.0, 0.0, 0.0)
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
        private const val LN_2 = 0.6931471805599453
        private const val MPV_PROPERTY_EPS = 0.000001
        private const val MIN_TRANSFORM_SCALE = 0.000001

        // A screen-sized overscan is enough for smooth local movement. Unlike the
        // old full-source buffer, its memory use is bounded by the display size.
        private const val ZOOM_RENDER_SURFACE_SCALE = 1.35f
        private const val MAX_ZOOM_RENDER_SURFACE_EDGE = 4096.0
        private const val MAX_ZOOM_RENDER_SURFACE_PIXELS = 12_000_000.0
        private const val SURFACE_SCALE_EPS = 0.001f

        // mpv is rebased only occasionally; all intermediate finger samples remain
        // cheap View-property changes on the UI thread.
        private const val MIN_MPV_REBASE_INTERVAL_MS = 90L
        private const val PREVIEW_REBASE_MARGIN_FRACTION = 0.72
        private const val MIN_PREVIEW_RESIDUAL_SCALE = 1.08
        private const val MAX_PREVIEW_RESIDUAL_SCALE = 1.90

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
