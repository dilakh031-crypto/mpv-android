package `is`.xyz.mpv

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.ImageView
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
 * Rendering has two explicit owners:
 *  - At scale 1 the TextureView buffer is exactly the Android window size and its
 *    texture matrix is identity. mpv therefore owns scaling, aspect ratio and
 *    rotation just like the release renderer; Android does not minify an already
 *    downscaled hardware layer a second time.
 *  - While zoomed, mpv renders a source-detail, media-aspect buffer and Android's
 *    View properties perform zoom/pan exactly as in the original edited build.
 *
 * SurfaceTexture cannot atomically change both buffer geometry and the View
 * transform. A two-phase handoff first commits the last valid frame in a
 * lightweight overlay, then changes ownership behind that already-visible
 * overlay. It is removed only after confirmed TextureView updates whose mpv
 * output dimensions and presentation state match the destination. This is
 * event-driven: there is no delay, blackout, or guessed grace period.
 *
 * We do not use mpv video-pan/video-zoom for finger movement.
 */
internal class VideoZoomGestures(
    private val target: BaseMPVView,
    private val handoffOverlay: ImageView,
) {
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
    private var customSurfaceSize = false
    private var customSurfaceWidth = 0
    private var customSurfaceHeight = 0

    private val handoffTransform = Matrix()
    private var handoffBitmap: Bitmap? = null
    private var handoffTracksBaseSurface = false
    private var handoffWaitingForFrame = 0L
    private var handoffExpectedSurfaceWidth = 0
    private var handoffExpectedSurfaceHeight = 0
    private var handoffToken = 0L
    private var pendingSurfaceTransition: ResolvedSurface? = null
    private var pendingSurfaceCommitAction: (() -> Unit)? = null
    private var handoffDestinationPresentation: PresentationTarget? = null
    private var handoffDestinationPresentationObserved = true
    private var handoffMatchingFramesRequired = 1
    private var handoffMatchingFramesSeen = 0
    private var handoffLastMatchingFrameSerial = 0L
    private var handoffPreDrawObserver: ViewTreeObserver? = null
    private var handoffPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var handoffFrameCommitObserver: ViewTreeObserver? = null
    private var handoffFrameCommitCallback: Runnable? = null
    private var handoffCommitPostedForToken = 0L

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

                // Begin the frame-synchronized handoff before the first visible
                // zoom step. The normal mpv frame remains responsive in the
                // overlay until the source-detail buffer produces a real frame.
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
        val nextAspect = aspect ?: 0.0
        val nextPixelWidth = pixelSize?.first ?: 0
        val nextPixelHeight = pixelSize?.second ?: 0
        val nextPanscan = panscanValue ?: 0.0
        val geometryChanged =
            nextAspect != videoAspect ||
            nextPixelWidth != videoPixelWidth ||
            nextPixelHeight != videoPixelHeight ||
            nextPanscan != panscan

        /*
         * An aspect-menu transition owns the presentation state until its
         * destination frame is visible. Property notifications are asynchronous
         * and can arrive out of order when the user selects two ratios quickly;
         * never let an older notification replace the newer predicted geometry.
         */
        handoffDestinationPresentation?.let { destination ->
            if (handoffOverlay.visibility == View.VISIBLE) {
                if (!presentationMatches(
                        aspect = nextAspect.takeIf { it > 0.001 },
                        panscanValue = nextPanscan,
                        destination = destination,
                    )
                ) {
                    return
                }
                markDestinationPresentationObserved()
            }
        }

        val sourceTransform = if (geometryChanged &&
            (((isZoomed() || scaleDetector.isInProgress) &&
                renderSurfaceMode != RenderSurfaceMode.BASE) ||
                handoffOverlay.visibility == View.VISIBLE)
        ) {
            matrixForMode(renderSurfaceMode, Matrix())
        } else {
            null
        }

        videoAspect = nextAspect
        videoPixelWidth = nextPixelWidth
        videoPixelHeight = nextPixelHeight
        panscan = nextPanscan

        if (isZoomed() || scaleDetector.isInProgress)
            clampTranslationToVideoContent()

        updateRenderSurfaceForCurrentState(
            force = true,
            handoffSourceTransform = sourceTransform,
        )
        if (immediate)
            applyToView()
        else
            scheduleApply()
    }

    /**
     * Apply an aspect-menu selection as one visual transaction.
     *
     * The source frame is committed to the overlay before [applyMpvProperties]
     * runs. This closes the BASE-to-BASE gap where mpv could previously expose
     * one frame rendered with an intermediate aspect/panscan combination.
     */
    fun applyAspectRatioTransition(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
        applyMpvProperties: () -> Unit,
    ) {
        refreshMetricsFromTarget()
        val sourceTransform = matrixForMode(renderSurfaceMode, Matrix())

        val nextAspect = aspect?.takeIf { it > 0.001 }
            ?: videoAspect.takeIf { it > 0.001 }
        val nextPixelWidth = pixelSize?.first ?: videoPixelWidth
        val nextPixelHeight = pixelSize?.second ?: videoPixelHeight
        val nextPanscan = panscanValue ?: panscan
        val destinationAlreadyCurrent = mpvPresentationMatches(
            aspect = nextAspect,
            panscanValue = nextPanscan,
        )
        val destination = PresentationTarget(
            aspect = nextAspect,
            panscan = nextPanscan,
            waitForPropertyObservation = !destinationAlreadyCurrent,
        )

        videoAspect = nextAspect ?: 0.0
        videoPixelWidth = nextPixelWidth
        videoPixelHeight = nextPixelHeight
        panscan = nextPanscan

        if (isZoomed() || scaleDetector.isInProgress)
            clampTranslationToVideoContent()

        val needsPresentationHandoff =
            !destinationAlreadyCurrent || handoffOverlay.visibility == View.VISIBLE
        updateRenderSurfaceForCurrentState(
            force = true,
            handoffSourceTransform = sourceTransform.takeIf { needsPresentationHandoff },
            forceVisualHandoff = needsPresentationHandoff,
            surfaceCommitAction = applyMpvProperties,
            destinationPresentation = destination,
        )
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

        // Hand rendering back to mpv's window-sized surface. The last valid zoom
        // frame covers the buffer-geometry change until mpv posts its base frame.
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun resetForNewFile() {
        resetTransformState()
        videoAspect = 0.0
        videoPixelWidth = 0
        videoPixelHeight = 0
        panscan = 0.0
        cancelHandoff()
        renderSurfaceMode = RenderSurfaceMode.BASE
        target.resetRenderSurfaceSize()
        customSurfaceSize = false
        customSurfaceWidth = 0
        customSurfaceHeight = 0
        applyToView()
    }

    fun prepareForWindowExit() {
        resetTransformState()
        cancelHandoff()
        renderSurfaceMode = RenderSurfaceMode.BASE
        target.resetRenderSurfaceSize()
        customSurfaceSize = false
        customSurfaceWidth = 0
        customSurfaceHeight = 0
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
        // Preserve the original zoom implementation: scaling and translation are
        // View properties, not a TextureView content matrix. Besides retaining the
        // established gesture feel, this leaves TextureView's internal sampling
        // path unchanged while the user pinches and pans.
        //
        // In BASE mode scale=1 and fit is identity, so normal playback is still
        // passed through untouched and mpv remains the sole scaling/aspect owner.
        val fit = renderSurfaceFitTransform(renderSurfaceMode)

        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale * fit.scaleX
        target.scaleY = scale * fit.scaleY
        target.translationX = (tx + scale * fit.translationX).toFloat()
        target.translationY = (ty + scale * fit.translationY).toFloat()

        if (handoffOverlay.visibility == View.VISIBLE && handoffTracksBaseSurface) {
            matrixForMode(RenderSurfaceMode.BASE, handoffTransform)
            handoffOverlay.imageMatrix = handoffTransform
        }
    }

    private fun matrixForMode(mode: RenderSurfaceMode, out: Matrix): Matrix {
        val fit = renderSurfaceFitTransform(mode)
        val scaleX = scale * fit.scaleX
        val scaleY = scale * fit.scaleY
        val translationX = (tx + scale * fit.translationX).toFloat()
        val translationY = (ty + scale * fit.translationY).toFloat()
        out.setValues(
            floatArrayOf(
                scaleX, 0f, translationX,
                0f, scaleY, translationY,
                0f, 0f, 1f,
            ),
        )
        return out
    }

    private fun renderSurfaceFitTransform(mode: RenderSurfaceMode): SurfaceFitTransform {
        if (!mode.usesMediaAspectFit || viewWidth <= 1f || viewHeight <= 1f)
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

    private fun updateRenderSurfaceForCurrentState(
        force: Boolean,
        handoffSourceTransform: Matrix? = null,
        forceVisualHandoff: Boolean = false,
        surfaceCommitAction: (() -> Unit)? = null,
        destinationPresentation: PresentationTarget? = null,
    ) {
        val zooming = isZoomed() || scaleDetector.isInProgress
        refreshMetricsFromTarget()

        val desired = resolveRenderSurface(zooming)
        val modeChanged = desired.mode != renderSurfaceMode
        val bufferChanged = desired.customSize != customSurfaceSize ||
            (desired.customSize &&
                (desired.width != customSurfaceWidth || desired.height != customSurfaceHeight))
        val geometryTransition = handoffSourceTransform != null
        val visualTransition =
            modeChanged || bufferChanged || geometryTransition || forceVisualHandoff
        val needsApply = force || visualTransition || surfaceCommitAction != null
        if (!needsApply)
            return

        /*
         * A transition can be superseded before the overlay's first draw (for
         * example a very short pinch that immediately returns to scale 1).
         * If the latest desired surface is still the active one, discard the
         * queued transition instead of committing its stale destination later.
         */
        if (!modeChanged && !bufferChanged && !geometryTransition && !forceVisualHandoff) {
            if (pendingSurfaceTransition != null) {
                if (pendingSurfaceCommitAction == null)
                    cancelHandoff()
                else
                    pendingSurfaceTransition = desired
            }
            applyResolvedSurface(desired)
            applyToView()
            surfaceCommitAction?.invoke()
            return
        }

        val shouldHandoff = visualTransition &&
            (forceVisualHandoff ||
                handoffOverlay.visibility == View.VISIBLE ||
                renderSurfaceMode != RenderSurfaceMode.BASE ||
                desired.mode != RenderSurfaceMode.BASE)
        if (shouldHandoff) {
            val sourceMatrix = handoffSourceTransform
                ?: matrixForMode(renderSurfaceMode, Matrix())
            val handoffReady = beginHandoff(
                sourceMatrix = sourceMatrix,
                trackBaseSurface = renderSurfaceMode == RenderSurfaceMode.BASE &&
                    desired.mode != RenderSurfaceMode.BASE,
            )
            if (handoffReady) {
                queueSurfaceTransition(
                    desired = desired,
                    surfaceCommitAction = surfaceCommitAction,
                    destinationPresentation = destinationPresentation,
                )
                return
            }
        }

        // Snapshotting is deliberately best-effort. If the TextureView has not
        // produced a frame yet, there is no valid image to preserve.
        pendingSurfaceTransition = null
        pendingSurfaceCommitAction = null
        handoffDestinationPresentation = null
        handoffDestinationPresentationObserved = true
        applyResolvedSurface(desired)
        applyToView()
        surfaceCommitAction?.invoke()
    }

    private fun applyResolvedSurface(desired: ResolvedSurface) {
        renderSurfaceMode = desired.mode
        if (desired.customSize) {
            target.setRenderSurfaceSize(desired.width, desired.height)
            customSurfaceSize = true
            customSurfaceWidth = desired.width
            customSurfaceHeight = desired.height
        } else {
            target.resetRenderSurfaceSize()
            customSurfaceSize = false
            customSurfaceWidth = 0
            customSurfaceHeight = 0
        }
    }

    private fun resolveRenderSurface(zooming: Boolean): ResolvedSurface {
        if (!zooming ||
            viewWidth <= 1f || viewHeight <= 1f ||
            videoPixelWidth <= 1 || videoPixelHeight <= 1
        ) return ResolvedSurface.BASE

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f)
            return ResolvedSurface.BASE

        if (isPanscanActive()) {
            // Panscan requires a window-shaped mpv output. Preserve source detail
            // by scaling that window until the content reaches native resolution.
            val bufferScale = limitedOriginalDetailBufferScale(
                baseWidth = viewWidth.toDouble(),
                baseHeight = viewHeight.toDouble(),
                content = c,
            )
            return ResolvedSurface(
                mode = RenderSurfaceMode.VIEW_ASPECT_ORIGINAL,
                customSize = true,
                width = ceilToIntAtLeastOne(viewWidth.toDouble() * bufferScale),
                height = ceilToIntAtLeastOne(viewHeight.toDouble() * bufferScale),
            )
        }

        // A media-aspect zoom buffer contains no oversized black bars, so rotated
        // portrait/landscape combinations retain the same source detail without
        // exceeding the device texture limit unnecessarily.
        val bufferScale = limitedOriginalDetailBufferScale(
            baseWidth = c.w.toDouble(),
            baseHeight = c.h.toDouble(),
            content = c,
        )
        return ResolvedSurface(
            mode = RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL,
            customSize = true,
            width = ceilToIntAtLeastOne(c.w.toDouble() * bufferScale),
            height = ceilToIntAtLeastOne(c.h.toDouble() * bufferScale),
        )
    }

    private fun beginHandoff(sourceMatrix: Matrix, trackBaseSurface: Boolean): Boolean {
        if (handoffOverlay.visibility == View.VISIBLE) {
            // A second geometry update can arrive before the destination frame
            // (for example aspect and panscan properties in one menu action).
            // Keep the already-visible valid frame instead of capturing a partly
            // reconfigured TextureView.
            handoffTracksBaseSurface =
                handoffTracksBaseSurface || trackBaseSurface
            return true
        }

        val width = target.width
        val height = target.height
        if (!target.isAvailable ||
            target.surfaceTextureFrameSerial <= 0L ||
            width <= 1 || height <= 1
        )
            return false

        var bitmap = handoffBitmap
        if (bitmap == null || bitmap.isRecycled ||
            bitmap.width != width || bitmap.height != height
        ) {
            handoffOverlay.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = try {
                Bitmap.createBitmap(
                    target.resources.displayMetrics,
                    width,
                    height,
                    Bitmap.Config.ARGB_8888,
                )
            } catch (_: Throwable) {
                null
            }
            handoffBitmap = bitmap
        }
        if (bitmap == null)
            return false

        // TextureView.getBitmap() captures its content but not the outer View
        // scale/translation used by the original zoom path. The content transform
        // remains at TextureView's default identity throughout playback, so the
        // overlay applies sourceMatrix exactly once.
        try {
            target.getBitmap(bitmap)
        } catch (_: Throwable) {
            return false
        }

        handoffTransform.set(sourceMatrix)
        handoffOverlay.setImageBitmap(bitmap)
        handoffOverlay.imageMatrix = handoffTransform
        handoffOverlay.visibility = View.VISIBLE
        handoffTracksBaseSurface = trackBaseSurface
        return true
    }

    /**
     * Do not resize the producer in the same UI turn that makes the overlay
     * visible. With continuously playing video, a queued SurfaceTexture update
     * can otherwise become visible before Android has drawn the overlay.
     *
     * OnPreDraw lets the current pass draw the stable source image. On Android
     * 10+ the surface mutation waits for that frame's commit callback, which is
     * the platform's explicit acknowledgement that the overlay was submitted to
     * the swap chain. Older Android versions use the following animation step.
     */
    private fun queueSurfaceTransition(
        desired: ResolvedSurface,
        surfaceCommitAction: (() -> Unit)? = null,
        destinationPresentation: PresentationTarget? = null,
    ) {
        pendingSurfaceTransition = desired
        if (surfaceCommitAction != null)
            pendingSurfaceCommitAction = surfaceCommitAction
        if (destinationPresentation != null)
            handoffDestinationPresentation = destinationPresentation
        handoffWaitingForFrame = 0L
        handoffExpectedSurfaceWidth = 0
        handoffExpectedSurfaceHeight = 0
        handoffMatchingFramesSeen = 0
        handoffLastMatchingFrameSerial = 0L

        if (handoffPreDrawListener != null || handoffCommitPostedForToken != 0L)
            return

        handoffToken++
        val token = handoffToken
        val observer = handoffOverlay.viewTreeObserver
        if (!observer.isAlive) {
            commitPendingSurfaceTransition(token)
            return
        }

        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removeHandoffPreDrawListener()

                if (token == handoffToken &&
                    pendingSurfaceTransition != null &&
                    handoffOverlay.visibility == View.VISIBLE
                ) {
                    handoffCommitPostedForToken = token
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        target.isHardwareAccelerated &&
                        observer.isAlive
                    ) {
                        lateinit var frameCommit: Runnable
                        frameCommit = Runnable {
                            // Frame commit callbacks can run away from the UI
                            // thread. All state and View mutations stay on it.
                            handoffOverlay.post {
                                if (handoffFrameCommitCallback === frameCommit) {
                                    handoffFrameCommitObserver = null
                                    handoffFrameCommitCallback = null
                                }
                                finishPostedSurfaceTransition(token)
                            }
                        }
                        handoffFrameCommitObserver = observer
                        handoffFrameCommitCallback = frameCommit
                        observer.registerFrameCommitCallback(frameCommit)
                    } else {
                        handoffOverlay.postOnAnimation {
                            finishPostedSurfaceTransition(token)
                        }
                    }
                }
                return true
            }
        }

        handoffPreDrawObserver = observer
        handoffPreDrawListener = listener
        observer.addOnPreDrawListener(listener)
        handoffOverlay.postInvalidateOnAnimation()
    }

    private fun finishPostedSurfaceTransition(token: Long) {
        if (handoffCommitPostedForToken == token)
            handoffCommitPostedForToken = 0L
        commitPendingSurfaceTransition(token)
    }

    private fun commitPendingSurfaceTransition(token: Long) {
        if (token != handoffToken)
            return

        val desired = pendingSurfaceTransition ?: return
        val surfaceCommitAction = pendingSurfaceCommitAction
        pendingSurfaceTransition = null
        pendingSurfaceCommitAction = null

        applyResolvedSurface(desired)
        applyToView()
        surfaceCommitAction?.invoke()
        armHandoffForDestinationFrame(desired)
    }

    private fun armHandoffForDestinationFrame(desired: ResolvedSurface) {
        if (desired.customSize) {
            handoffExpectedSurfaceWidth = desired.width
            handoffExpectedSurfaceHeight = desired.height
        } else {
            handoffExpectedSurfaceWidth = target.width.coerceAtLeast(1)
            handoffExpectedSurfaceHeight = target.height.coerceAtLeast(1)
        }
        val continuouslyRenderingVideo = isContinuouslyRenderingVideo()
        handoffMatchingFramesRequired = if (continuouslyRenderingVideo) 2 else 1
        handoffMatchingFramesSeen = 0
        handoffLastMatchingFrameSerial = 0L
        handoffDestinationPresentationObserved =
            !continuouslyRenderingVideo ||
            handoffDestinationPresentation?.waitForPropertyObservation != true
        handoffWaitingForFrame = target.surfaceTextureFrameSerial + 1L
    }

    fun onSurfaceFrameAvailable(frameSerial: Long) {
        val expected = handoffWaitingForFrame
        if (expected <= 0L || frameSerial < expected)
            return
        if (!handoffDestinationPresentationObserved)
            return
        if (!destinationSurfaceGeometryIsCurrent()) {
            handoffMatchingFramesSeen = 0
            handoffLastMatchingFrameSerial = 0L
            return
        }

        if (frameSerial > handoffLastMatchingFrameSerial) {
            handoffLastMatchingFrameSerial = frameSerial
            handoffMatchingFramesSeen++
        }
        if (handoffMatchingFramesRequired > 1 && !isContinuouslyRenderingVideo())
            handoffMatchingFramesRequired = 1
        if (handoffMatchingFramesSeen < handoffMatchingFramesRequired)
            return

        val token = handoffToken
        val confirmedFrameSerial = frameSerial
        target.postOnAnimation {
            if (token == handoffToken &&
                handoffWaitingForFrame > 0L &&
                target.surfaceTextureFrameSerial >= confirmedFrameSerial &&
                handoffMatchingFramesSeen >= handoffMatchingFramesRequired &&
                destinationSurfaceGeometryIsCurrent()
            ) {
                cancelHandoff()
            }
        }
    }

    private fun destinationSurfaceGeometryIsCurrent(): Boolean {
        val expectedWidth = handoffExpectedSurfaceWidth
        val expectedHeight = handoffExpectedSurfaceHeight
        if (expectedWidth <= 0 || expectedHeight <= 0)
            return false

        // onSurfaceTextureUpdated only says that updateTexImage() ran; an old
        // BufferQueue entry can still produce that callback after a resize.
        // mpv's OSD dimensions identify the current producer geometry, but do not
        // identify the BufferQueue entry consumed by this individual callback.
        // Continuously playing video therefore also needs the consecutive-frame
        // confirmation performed by onSurfaceFrameAvailable().
        val voWidth = MPVLib.getPropertyInt("osd-width") ?: return false
        val voHeight = MPVLib.getPropertyInt("osd-height") ?: return false
        if (voWidth != expectedWidth || voHeight != expectedHeight)
            return false

        val destination = handoffDestinationPresentation
        return destination == null || mpvPresentationMatches(
            aspect = destination.aspect,
            panscanValue = destination.panscan,
        )
    }

    private fun markDestinationPresentationObserved() {
        if (handoffDestinationPresentationObserved || handoffWaitingForFrame <= 0L)
            return

        // Do not count a consumer update that raced the property notification.
        // The next callback is the first eligible frame in the new generation.
        handoffDestinationPresentation =
            handoffDestinationPresentation?.copy(waitForPropertyObservation = false)
        handoffDestinationPresentationObserved = true
        handoffWaitingForFrame = target.surfaceTextureFrameSerial + 1L
        handoffMatchingFramesSeen = 0
        handoffLastMatchingFrameSerial = 0L
    }

    private fun presentationMatches(
        aspect: Double?,
        panscanValue: Double,
        destination: PresentationTarget,
    ): Boolean {
        val expectedAspect = destination.aspect
        if (expectedAspect != null) {
            val actualAspect = aspect ?: return false
            if (!approximatelyEqual(actualAspect, expectedAspect))
                return false
        }
        return approximatelyEqual(panscanValue, destination.panscan)
    }

    private fun mpvPresentationMatches(aspect: Double?, panscanValue: Double): Boolean {
        if (aspect != null) {
            val actualAspect = currentMpvEffectiveAspect() ?: return false
            if (!approximatelyEqual(actualAspect, aspect))
                return false
        }
        val actualPanscan = MPVLib.getPropertyDouble("panscan") ?: return false
        return approximatelyEqual(actualPanscan, panscanValue)
    }

    private fun currentMpvEffectiveAspect(): Double? {
        parseAspectRatio(MPVLib.getPropertyString("video-aspect-override"))?.let {
            return orientAspectForVideoRotation(it)
        }
        val nativeAspect = MPVLib.getPropertyDouble("video-params/aspect")
            ?.takeIf { it > 0.001 }
            ?: return null
        return orientAspectForVideoRotation(nativeAspect)
    }

    private fun orientAspectForVideoRotation(aspect: Double): Double {
        val rotation =
            ((MPVLib.getPropertyInt("video-params/rotate") ?: 0) % 360 + 360) % 360
        return if (rotation == 90 || rotation == 270) 1.0 / aspect else aspect
    }

    private fun parseAspectRatio(value: String?): Double? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed == "-1" || trimmed.equals("no", true))
            return null

        val parts = trimmed.split(':', limit = 2)
        val parsed = if (parts.size == 2) {
            val width = parts[0].toDoubleOrNull()
            val height = parts[1].toDoubleOrNull()
            if (width != null && height != null && height != 0.0)
                width / height
            else
                null
        } else {
            trimmed.toDoubleOrNull()
        }
        return parsed?.takeIf { it > 0.001 }
    }

    private fun approximatelyEqual(a: Double, b: Double): Boolean {
        val tolerance = max(0.001, max(abs(a), abs(b)) * 0.001)
        return abs(a - b) <= tolerance
    }

    private fun isContinuouslyRenderingVideo(): Boolean {
        // "no" is the only state that guarantees a continuously advancing
        // video track. Null/empty can occur during reconfiguration and must not
        // strand the overlay waiting for a second frame that may never arrive.
        if (MPVLib.getPropertyString("current-tracks/video/image") != "no")
            return false
        if (MPVLib.getPropertyBoolean("pause") == true ||
            MPVLib.getPropertyBoolean("paused-for-cache") == true ||
            MPVLib.getPropertyBoolean("eof-reached") == true
        ) {
            return false
        }
        return true
    }

    private fun removeHandoffPreDrawListener() {
        val listener = handoffPreDrawListener ?: return
        val observer = handoffPreDrawObserver
        if (observer != null && observer.isAlive)
            observer.removeOnPreDrawListener(listener)
        handoffPreDrawObserver = null
        handoffPreDrawListener = null
    }

    private fun removeHandoffFrameCommitCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return

        val callback = handoffFrameCommitCallback ?: return
        val observer = handoffFrameCommitObserver
        if (observer != null && observer.isAlive)
            observer.unregisterFrameCommitCallback(callback)
        handoffFrameCommitObserver = null
        handoffFrameCommitCallback = null
    }

    private fun cancelHandoff() {
        handoffToken++
        pendingSurfaceTransition = null
        pendingSurfaceCommitAction = null
        removeHandoffPreDrawListener()
        removeHandoffFrameCommitCallback()
        handoffCommitPostedForToken = 0L
        handoffWaitingForFrame = 0L
        handoffExpectedSurfaceWidth = 0
        handoffExpectedSurfaceHeight = 0
        handoffDestinationPresentation = null
        handoffDestinationPresentationObserved = true
        handoffMatchingFramesRequired = 1
        handoffMatchingFramesSeen = 0
        handoffLastMatchingFrameSerial = 0L
        handoffTracksBaseSurface = false
        handoffOverlay.visibility = View.GONE
        handoffOverlay.setImageDrawable(null)
    }

    fun release() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }
        cancelHandoff()
        handoffBitmap?.recycle()
        handoffBitmap = null
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

    private data class ResolvedSurface(
        val mode: RenderSurfaceMode,
        val customSize: Boolean,
        val width: Int,
        val height: Int,
    ) {
        companion object {
            val BASE = ResolvedSurface(
                mode = RenderSurfaceMode.BASE,
                customSize = false,
                width = 0,
                height = 0,
            )
        }
    }

    private data class PresentationTarget(
        val aspect: Double?,
        val panscan: Double,
        val waitForPropertyObservation: Boolean,
    )

    private enum class RenderSurfaceMode(val usesMediaAspectFit: Boolean) {
        BASE(false),
        VIEW_ASPECT_ORIGINAL(false),
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
