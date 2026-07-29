package `is`.xyz.mpv

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private typealias PanscanTransitionAction = ((Long?) -> Unit) -> Unit

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
 * output dimensions match the destination surface. This is event-driven:
 * there is no delay, blackout, or guessed grace period.
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
    private val handoffBitmapExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "mpv-handoff-bitmap").apply { isDaemon = true }
        }
    private var handoffReleased = false
    private var handoffBitmapGeneration = 0L
    private var handoffBitmapAllocationInFlight = false
    private var handoffCaptureGeneration = 0L
    private var handoffCaptureRequested = false
    private var handoffCaptureInFlight = false
    private var handoffCaptureReady = false
    private var handoffCaptureRetryOnNextFrame = false
    private var handoffCaptureAttempts = 0
    private var pendingCaptureSurfaceTransition: ResolvedSurface? = null
    private var pendingCaptureNeedsQueueDrain = false
    private var pendingCaptureSourceTransform: Matrix? = null
    private var pendingCaptureTracksBaseSurface = false
    private var pendingPanscanAction: PanscanTransitionAction? = null
    private var deferredPanscanAction: PanscanTransitionAction? = null
    private var panscanMutationInProgress = false
    private var handoffTracksBaseSurface = false
    private var handoffWaitingForFrame = 0L
    private var handoffExpectedSurfaceWidth = 0
    private var handoffExpectedSurfaceHeight = 0
    private var handoffExpectedPresentationTime = 0L
    private var handoffToken = 0L
    private var pendingSurfaceTransition: ResolvedSurface? = null
    private var pendingTransitionNeedsQueueDrain = false
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
    private var pinchSurfacePrepared = false
    private var pinchCapturePrimed = false

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

                /*
                 * ACTION_POINTER_DOWN normally starts this preparation before
                 * ScaleGestureDetector's span slop is crossed. Keep the explicit
                 * zoom intent here as well for quick-scale/stylus gestures and
                 * devices that synthesize a different pointer sequence.
                 */
                pinchSurfacePrepared = true
                pinchCapturePrimed = false
                updateRenderSurfaceForCurrentState(
                    force = true,
                    prepareForPinch = true,
                )
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
                pinchSurfacePrepared = false

                clampTranslationToVideoContent()
                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                updateRenderSurfaceForCurrentState(force = false)
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinchSurfacePrepared = false
                pinchCapturePrimed = false
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
        preallocateHandoffBitmap()
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
        val sourceTransform = if (geometryChanged &&
            (isZoomed() || scaleDetector.isInProgress) &&
            renderSurfaceMode != RenderSurfaceMode.BASE
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

        /*
         * A panscan menu transaction updates mpv only after the old frame overlay
         * has been committed. Its caller immediately feeds the final, combined
         * geometry back through this method. Cache that geometry here; the
         * transaction commits the matching SurfaceTexture size and View transform
         * together after the callback returns.
         */
        if (panscanMutationInProgress)
            return

        updateRenderSurfaceForCurrentState(
            force = true,
            handoffSourceTransform = sourceTransform,
        )
        if (immediate)
            applyToView()
        else
            scheduleApply()
    }

    private fun videoPixelSizeOrNull(): Pair<Int, Int>? {
        if (videoPixelWidth <= 0 || videoPixelHeight <= 0)
            return null
        return videoPixelWidth to videoPixelHeight
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun hasPendingPanscanTransition(): Boolean =
        pendingPanscanAction != null ||
            deferredPanscanAction != null ||
            panscanMutationInProgress

    /**
     * Run an aspect change that enters or leaves panscan as a visual transaction.
     *
     * [changeAndSyncGeometry] starts mpv's aspect+panscan transaction and calls
     * its completion with the final buffer timestamp only after VO has presented
     * the explicitly marked frame. The old frame remains visible in the overlay
     * while the asynchronous pipeline crosses that boundary.
     */
    fun performPanscanTransition(changeAndSyncGeometry: PanscanTransitionAction) {
        if (handoffReleased) {
            changeAndSyncGeometry {}
            return
        }

        if (panscanMutationInProgress) {
            deferredPanscanAction = changeAndSyncGeometry
            return
        }

        if (handoffOverlay.visibility == View.VISIBLE) {
            if (pendingPanscanAction != null &&
                handoffWaitingForFrame == 0L
            ) {
                // The source overlay is visible but the property transaction has
                // not run yet. Replace it in place so rapid menu taps apply only
                // the user's newest choice.
                pendingPanscanAction = changeAndSyncGeometry
                return
            }

            // A menu choice can race the tail of a zoom ownership handoff. Keep
            // only the newest choice and start it from the fully committed frame.
            deferredPanscanAction = changeAndSyncGeometry
            return
        }
        if (pendingSurfaceTransition != null) {
            deferredPanscanAction = changeAndSyncGeometry
            return
        }

        pendingPanscanAction = changeAndSyncGeometry
        pendingCaptureSurfaceTransition = null
        pendingCaptureNeedsQueueDrain = false
        pendingCaptureSourceTransform = matrixForMode(renderSurfaceMode, Matrix())
        pendingCaptureTracksBaseSurface = false

        if (!requestHandoffCapture()) {
            pendingPanscanAction = null
            pendingCaptureSourceTransform = null
            applyPanscanWithoutSnapshot(changeAndSyncGeometry)
        }
    }

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
        pinchSurfacePrepared = false
        pinchCapturePrimed = false
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

        /*
         * Prime the asynchronous snapshot on the first finger, before a pinch
         * exists. On large Android 9 surfaces PixelCopy can occupy part of a GPU
         * frame; moving it ahead of ACTION_POINTER_DOWN keeps that work out of
         * the first scale samples. A normal one-finger tap simply discards the
         * prepared frame. The mpv surface itself is not changed here.
         */
        if (e.actionMasked == MotionEvent.ACTION_DOWN &&
            !isZoomed() &&
            !scaleDetector.isInProgress &&
            !pendingPinchDoubleTapReset
        ) {
            pinchCapturePrimed = true
            prewarmHandoffCapture()
        }

        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            e.pointerCount == 2 &&
            !isZoomed() &&
            !scaleDetector.isInProgress &&
            !pendingPinchDoubleTapReset
        ) {
            pinchSurfacePrepared = true
            pinchCapturePrimed = false
            prewarmHandoffCapture()
        }

        // Always feed the scale detector first.
        scaleDetector.onTouchEvent(e)

        val unusedPinchPreparationEnded =
            pinchSurfacePrepared &&
            !scaleDetector.isInProgress &&
            !isZoomed() &&
            (e.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL)
        if (unusedPinchPreparationEnded) {
            pinchSurfacePrepared = false
            pinchCapturePrimed = false
            discardUnusedHandoffCapture()
            updateRenderSurfaceForCurrentState(force = true)
            applyToView()
            return true
        }

        val unusedCapturePrimeEnded =
            pinchCapturePrimed &&
                !pinchSurfacePrepared &&
                !scaleDetector.isInProgress &&
                !isZoomed() &&
                (e.actionMasked == MotionEvent.ACTION_UP ||
                    e.actionMasked == MotionEvent.ACTION_CANCEL)
        if (unusedCapturePrimeEnded) {
            pinchCapturePrimed = false
            discardUnusedHandoffCapture()
        }

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
        prepareForPinch: Boolean = false,
    ) {
        val zooming =
            prepareForPinch || isZoomed() || scaleDetector.isInProgress
        refreshMetricsFromTarget()

        val desired = resolveRenderSurface(zooming)
        val modeChanged = desired.mode != renderSurfaceMode
        val bufferChanged = desired.customSize != customSurfaceSize ||
            (desired.customSize &&
                (desired.width != customSurfaceWidth || desired.height != customSurfaceHeight))
        val geometryTransition = handoffSourceTransform != null
        val needsApply = force || modeChanged || bufferChanged || geometryTransition
        if (!needsApply)
            return

        /*
         * A transition can be superseded before the overlay's first draw (for
         * example a very short pinch that immediately returns to scale 1).
         * If the latest desired surface is still the active one, discard the
         * queued transition instead of committing its stale destination later.
         */
        if (!modeChanged && !bufferChanged && !geometryTransition) {
            if (pendingSurfaceTransition != null)
                cancelHandoff()
            if (pendingPanscanAction == null)
                clearPendingCaptureTransition()
            applyResolvedSurface(desired)
            applyToView()
            return
        }

        val shouldHandoff =
            (modeChanged || bufferChanged || geometryTransition) &&
            (renderSurfaceMode != RenderSurfaceMode.BASE ||
                desired.mode != RenderSurfaceMode.BASE)
        if (shouldHandoff) {
            val needsQueueDrain =
                transitionNeedsOldBufferQueueDrain(
                    source = renderSurfaceMode,
                    destination = desired.mode,
                )
            val sourceMatrix = handoffSourceTransform
                ?: matrixForMode(renderSurfaceMode, Matrix())
            val handoffQueued = requestSurfaceHandoff(
                desired = desired,
                needsQueueDrain = needsQueueDrain,
                sourceMatrix = sourceMatrix,
                trackBaseSurface = renderSurfaceMode == RenderSurfaceMode.BASE &&
                    desired.mode != RenderSurfaceMode.BASE,
            )
            if (handoffQueued)
                return
        }

        // PixelCopy is deliberately best-effort. If the producer has no copyable
        // frame (for example protected content), preserve playback by applying the
        // destination directly instead of blocking the gesture.
        clearPendingCaptureTransition()
        pendingSurfaceTransition = null
        pendingTransitionNeedsQueueDrain = false
        applyResolvedSurface(desired)
        applyToView()
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

    private fun requestSurfaceHandoff(
        desired: ResolvedSurface,
        needsQueueDrain: Boolean,
        sourceMatrix: Matrix,
        trackBaseSurface: Boolean,
    ): Boolean {
        if (handoffOverlay.visibility == View.VISIBLE) {
            // Keep the already-visible valid frame when geometry is refined before
            // the destination frame arrives.
            handoffTracksBaseSurface =
                handoffTracksBaseSurface || trackBaseSurface
            queueSurfaceTransition(desired, needsQueueDrain)
            return true
        }

        pendingCaptureSurfaceTransition = desired
        pendingCaptureNeedsQueueDrain =
            pendingCaptureNeedsQueueDrain || needsQueueDrain
        pendingCaptureSourceTransform = Matrix(sourceMatrix)
        pendingCaptureTracksBaseSurface =
            pendingCaptureTracksBaseSurface || trackBaseSurface

        if (showCapturedHandoffIfPending())
            return true
        if (requestHandoffCapture())
            return true

        clearPendingCaptureTransition()
        return false
    }

    private fun prewarmHandoffCapture() {
        if (handoffReleased ||
            handoffOverlay.visibility == View.VISIBLE ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        ) return

        requestHandoffCapture()
    }

    private fun preallocateHandoffBitmap() {
        if (handoffReleased || handoffOverlay.visibility == View.VISIBLE)
            return
        ensureHandoffBitmapAllocated()
    }

    private fun ensureHandoffBitmapAllocated(): Boolean {
        val width = target.width
        val height = target.height
        if (handoffReleased || width <= 1 || height <= 1)
            return false

        val current = handoffBitmap
        if (current != null && !current.isRecycled &&
            current.width == width && current.height == height
        ) return true

        // PixelCopy owns the destination bitmap until its callback. Never recycle
        // or replace that bitmap during a configuration/layout change.
        if (handoffCaptureInFlight)
            return true

        if (handoffBitmapAllocationInFlight)
            return true

        handoffBitmapAllocationInFlight = true
        val generation = ++handoffBitmapGeneration
        return try {
            handoffBitmapExecutor.execute {
                val allocated = try {
                    Bitmap.createBitmap(
                        target.resources.displayMetrics,
                        width,
                        height,
                        Bitmap.Config.ARGB_8888,
                    )
                } catch (_: Throwable) {
                    null
                }

                target.post {
                    if (handoffReleased || generation != handoffBitmapGeneration) {
                        allocated?.recycle()
                        return@post
                    }

                    handoffBitmapAllocationInFlight = false
                    if (allocated == null ||
                        target.width != width || target.height != height
                    ) {
                        allocated?.recycle()
                        if (handoffCaptureRequested)
                            requestHandoffCapture()
                        return@post
                    }

                    val old = handoffBitmap
                    handoffBitmap = allocated
                    if (old !== allocated && old != null && !old.isRecycled)
                        old.recycle()

                    if (handoffCaptureRequested)
                        startHandoffFrameCopy()
                }
            }
            true
        } catch (_: Throwable) {
            handoffBitmapAllocationInFlight = false
            false
        }
    }

    private fun requestHandoffCapture(): Boolean {
        val width = target.width
        val height = target.height
        if (handoffReleased ||
            !target.isAvailable ||
            target.surfaceTextureFrameSerial <= 0L ||
            width <= 1 || height <= 1
        ) return false

        if (handoffCaptureReady) {
            if (showCapturedHandoffIfPending())
                return true
            return true
        }

        if (!handoffCaptureRequested)
            handoffCaptureAttempts = 0
        handoffCaptureRequested = true

        if (handoffCaptureInFlight ||
            handoffBitmapAllocationInFlight ||
            handoffCaptureRetryOnNextFrame
        ) return true

        if (!ensureHandoffBitmapAllocated())
            return false
        if (handoffBitmapAllocationInFlight)
            return true

        return startHandoffFrameCopy()
    }

    private fun startHandoffFrameCopy(): Boolean {
        if (handoffReleased || handoffCaptureInFlight)
            return false

        val bitmap = handoffBitmap
        if (bitmap == null || bitmap.isRecycled ||
            bitmap.width != target.width || bitmap.height != target.height
        ) {
            return ensureHandoffBitmapAllocated()
        }

        handoffCaptureRetryOnNextFrame = false
        handoffCaptureAttempts++

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // PixelCopy does not exist on API 21-23. Retain the release-compatible
            // fallback there; Android 9 and newer always take the asynchronous path.
            return try {
                target.getBitmap(bitmap)
                handoffCaptureRequested = false
                handoffCaptureReady = true
                showCapturedHandoffIfPending()
                true
            } catch (_: Throwable) {
                handoffCaptureRequested = false
                handoffCaptureReady = false
                false
            }
        }

        handoffCaptureInFlight = true
        val captureGeneration = ++handoffCaptureGeneration
        val started = target.requestSurfaceFrameCopy(bitmap) { result ->
            onHandoffFrameCopyFinished(captureGeneration, result)
        }
        if (!started) {
            handoffCaptureInFlight = false
            handoffCaptureRequested = false
            return false
        }
        return true
    }

    private fun onHandoffFrameCopyFinished(generation: Long, result: Int) {
        handoffCaptureInFlight = false
        if (handoffReleased)
            return
        if (generation != handoffCaptureGeneration) {
            if (handoffCaptureRequested)
                requestHandoffCapture()
            return
        }

        val bitmap = handoffBitmap
        val copiedCurrentGeometry =
            bitmap != null && !bitmap.isRecycled &&
                bitmap.width == target.width && bitmap.height == target.height
        if (result == PixelCopy.SUCCESS && copiedCurrentGeometry) {
            handoffCaptureRetryOnNextFrame = false
            handoffCaptureRequested = false

            val captureStillWanted =
                pendingCaptureSurfaceTransition != null ||
                    pendingPanscanAction != null ||
                    pinchCapturePrimed ||
                    pinchSurfacePrepared ||
                    scaleDetector.isInProgress
            handoffCaptureReady = captureStillWanted
            if (captureStillWanted)
                showCapturedHandoffIfPending()
            return
        }

        if (result == PixelCopy.SUCCESS) {
            handoffCaptureReady = false
            if (handoffCaptureRequested ||
                pendingCaptureSurfaceTransition != null ||
                pendingPanscanAction != null
            ) {
                handoffCaptureRequested = true
                requestHandoffCapture()
            }
            return
        }

        handoffCaptureReady = false
        val retryable =
            result == PixelCopy.ERROR_SOURCE_NO_DATA ||
                result == PixelCopy.ERROR_TIMEOUT ||
                result == PixelCopy.ERROR_UNKNOWN
        if (retryable &&
            handoffCaptureAttempts < MAX_HANDOFF_COPY_ATTEMPTS &&
            handoffCaptureRequested
        ) {
            handoffCaptureRetryOnNextFrame = true
            return
        }

        handoffCaptureRequested = false
        handoffCaptureRetryOnNextFrame = false
        finishPendingHandoffWithoutSnapshot()
    }

    private fun showCapturedHandoffIfPending(): Boolean {
        if (!handoffCaptureReady || handoffOverlay.visibility == View.VISIBLE)
            return false
        if (pendingCaptureSurfaceTransition == null && pendingPanscanAction == null)
            return false

        val bitmap = handoffBitmap
        if (bitmap == null || bitmap.isRecycled)
            return false

        val sourceTransform = pendingCaptureSourceTransform ?: Matrix()
        handoffTransform.set(
            if (pendingCaptureTracksBaseSurface)
                matrixForMode(RenderSurfaceMode.BASE, Matrix())
            else
                sourceTransform,
        )
        handoffOverlay.setImageBitmap(bitmap)
        handoffOverlay.imageMatrix = handoffTransform
        handoffOverlay.visibility = View.VISIBLE
        handoffTracksBaseSurface = pendingCaptureTracksBaseSurface
        handoffCaptureReady = false

        if (pendingPanscanAction != null) {
            pendingCaptureSourceTransform = null
            pendingCaptureTracksBaseSurface = false
            queuePanscanTransition()
            return true
        }

        val desired = pendingCaptureSurfaceTransition ?: return false
        val needsQueueDrain = pendingCaptureNeedsQueueDrain
        pendingCaptureSurfaceTransition = null
        pendingCaptureNeedsQueueDrain = false
        pendingCaptureSourceTransform = null
        pendingCaptureTracksBaseSurface = false
        queueSurfaceTransition(desired, needsQueueDrain)
        return true
    }

    private fun finishPendingHandoffWithoutSnapshot() {
        val panscanAction = pendingPanscanAction
        val desired = pendingCaptureSurfaceTransition

        pendingPanscanAction = null
        clearPendingCaptureTransition()

        if (panscanAction != null) {
            applyPanscanWithoutSnapshot(panscanAction)
            return
        }
        if (desired != null) {
            applyResolvedSurface(desired)
            applyToView()
        }
    }

    private fun applyPanscanWithoutSnapshot(action: PanscanTransitionAction) {
        val token = ++handoffToken
        panscanMutationInProgress = true
        try {
            action {
                target.post {
                    if (token != handoffToken || handoffReleased)
                        return@post

                    panscanMutationInProgress = false
                    val fallback = resolveRenderSurface(
                        isZoomed() || scaleDetector.isInProgress,
                    )
                    applyResolvedSurface(fallback)
                    applyToView()
                    completeHandoff()
                }
            }
        } catch (_: Throwable) {
            panscanMutationInProgress = false
            completeHandoff()
        }
    }

    private fun clearPendingCaptureTransition() {
        pendingCaptureSurfaceTransition = null
        pendingCaptureNeedsQueueDrain = false
        pendingCaptureSourceTransform = null
        pendingCaptureTracksBaseSurface = false
    }

    private fun discardUnusedHandoffCapture() {
        clearPendingCaptureTransition()
        if (pendingPanscanAction == null) {
            handoffCaptureRequested = false
            handoffCaptureRetryOnNextFrame = false
            handoffCaptureReady = false
        }
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
        needsQueueDrain: Boolean,
    ) {
        pendingSurfaceTransition = desired
        pendingTransitionNeedsQueueDrain =
            pendingTransitionNeedsQueueDrain || needsQueueDrain
        scheduleOverlayCommit()
    }

    private fun queuePanscanTransition() {
        pendingSurfaceTransition = null
        pendingTransitionNeedsQueueDrain = true
        scheduleOverlayCommit()
    }

    private fun scheduleOverlayCommit() {
        handoffWaitingForFrame = 0L
        handoffExpectedSurfaceWidth = 0
        handoffExpectedSurfaceHeight = 0
        handoffExpectedPresentationTime = 0L
        handoffMatchingFramesRequired = 1
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
                    (pendingSurfaceTransition != null ||
                        pendingPanscanAction != null) &&
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

        val panscanAction = pendingPanscanAction
        if (panscanAction != null) {
            val sourceMode = renderSurfaceMode
            val sourceCustomSize = customSurfaceSize
            val sourceWidth = customSurfaceWidth
            val sourceHeight = customSurfaceHeight
            pendingPanscanAction = null
            pendingSurfaceTransition = null
            pendingTransitionNeedsQueueDrain = false

            // The source overlay is committed. Keep it visible while mpv carries
            // the transaction marker through decoding and into a presented VO
            // frame. The UI thread stays free to drain TextureView's BufferQueue.
            panscanMutationInProgress = true
            try {
                panscanAction { finalPresentationTime ->
                    target.post {
                        finishPanscanMutation(
                            token = token,
                            sourceMode = sourceMode,
                            sourceCustomSize = sourceCustomSize,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            finalPresentationTime = finalPresentationTime,
                            actionSucceeded = true,
                        )
                    }
                }
            } catch (_: Throwable) {
                finishPanscanMutation(
                    token = token,
                    sourceMode = sourceMode,
                    sourceCustomSize = sourceCustomSize,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    finalPresentationTime = null,
                    actionSucceeded = false,
                )
            }
            return
        }

        val desired = pendingSurfaceTransition ?: return
        val needsQueueDrain = pendingTransitionNeedsQueueDrain
        pendingSurfaceTransition = null
        pendingTransitionNeedsQueueDrain = false

        applyResolvedSurface(desired)
        applyToView()
        armHandoffForDestinationFrame(
            desired = desired,
            needsQueueDrain = needsQueueDrain,
        )
    }

    private fun finishPanscanMutation(
        token: Long,
        sourceMode: RenderSurfaceMode,
        sourceCustomSize: Boolean,
        sourceWidth: Int,
        sourceHeight: Int,
        finalPresentationTime: Long?,
        actionSucceeded: Boolean,
    ) {
        if (token != handoffToken || handoffReleased)
            return

        panscanMutationInProgress = false
        if (!actionSucceeded) {
            completeHandoff()
            return
        }

        val desired = resolveRenderSurface(
            isZoomed() || scaleDetector.isInProgress,
        )
        val bufferGeometryChanges =
            desired.customSize != sourceCustomSize ||
                (desired.customSize &&
                    (desired.width != sourceWidth ||
                        desired.height != sourceHeight))
        val finalFrameIdentified = (finalPresentationTime ?: 0L) > 0L
        val needsQueueDrain =
            bufferGeometryChanges ||
                (!finalFrameIdentified &&
                    transitionNeedsOldBufferQueueDrain(
                        source = sourceMode,
                        destination = desired.mode,
                    ))
        applyResolvedSurface(desired)
        applyToView()
        armHandoffForDestinationFrame(
            desired = desired,
            needsQueueDrain = needsQueueDrain,
            expectedPresentationTime =
                if (bufferGeometryChanges) 0L
                else finalPresentationTime ?: 0L,
        )
    }

    private fun armHandoffForDestinationFrame(
        desired: ResolvedSurface,
        needsQueueDrain: Boolean,
        expectedPresentationTime: Long = 0L,
    ) {
        if (desired.customSize) {
            handoffExpectedSurfaceWidth = desired.width
            handoffExpectedSurfaceHeight = desired.height
        } else {
            handoffExpectedSurfaceWidth = target.width.coerceAtLeast(1)
            handoffExpectedSurfaceHeight = target.height.coerceAtLeast(1)
        }
        handoffMatchingFramesRequired =
            if (needsQueueDrain && isContinuouslyRenderingVideo()) 2 else 1
        handoffMatchingFramesSeen = 0
        handoffLastMatchingFrameSerial = 0L
        handoffExpectedPresentationTime = expectedPresentationTime
        handoffWaitingForFrame = target.surfaceTextureFrameSerial + 1L

        // The marked frame can be consumed before the command-completion post
        // reaches the UI thread. SurfaceTexture preserves EGL's presentation
        // timestamp, so an already-latched matching frame can be revealed now.
        if (expectedPresentationTime > 0L &&
            target.surfaceTexturePresentationTime >= expectedPresentationTime &&
            destinationSurfaceGeometryIsCurrent()
        ) {
            completeHandoff()
        }
    }

    fun onSurfaceFrameAvailable(frameSerial: Long) {
        preallocateHandoffBitmap()
        if (handoffCaptureRetryOnNextFrame &&
            handoffCaptureRequested &&
            !handoffCaptureInFlight
        ) {
            startHandoffFrameCopy()
        }

        val expected = handoffWaitingForFrame
        if (expected <= 0L || frameSerial < expected)
            return
        if (!destinationSurfaceGeometryIsCurrent()) {
            handoffMatchingFramesSeen = 0
            handoffLastMatchingFrameSerial = 0L
            return
        }
        if (handoffExpectedPresentationTime > 0L &&
            target.surfaceTexturePresentationTime <
                handoffExpectedPresentationTime
        ) return

        if (frameSerial > handoffLastMatchingFrameSerial) {
            handoffLastMatchingFrameSerial = frameSerial
            handoffMatchingFramesSeen++
        }
        if (handoffMatchingFramesSeen < handoffMatchingFramesRequired)
            return

        /*
         * TextureView invokes this callback after updateSurfaceTexture() but
         * before it records the texture layer. The overlay is the next sibling
         * in player.xml, so removing it here reveals the confirmed destination
         * in this very draw pass. postOnAnimation() would unnecessarily freeze
         * the old snapshot for another display frame.
         */
        if (handoffWaitingForFrame > 0L &&
            target.surfaceTextureFrameSerial >= frameSerial &&
            destinationSurfaceGeometryIsCurrent()
        ) {
            completeHandoff()
        }
    }

    private fun destinationSurfaceGeometryIsCurrent(): Boolean {
        val expectedWidth = handoffExpectedSurfaceWidth
        val expectedHeight = handoffExpectedSurfaceHeight
        if (expectedWidth <= 0 || expectedHeight <= 0)
            return false

        // onSurfaceTextureUpdated only says that updateTexImage() ran; an old
        // BufferQueue entry can still produce that callback after a resize.
        // mpv's OSD dimensions identify the producer geometry, but not the
        // individual BufferQueue entry consumed by this callback. Resize
        // handoffs therefore drain one additional advancing video entry. A
        // same-size panscan transaction instead uses the exact EGL timestamp.
        val voWidth = MPVLib.getPropertyInt("osd-width") ?: return false
        val voHeight = MPVLib.getPropertyInt("osd-height") ?: return false
        return voWidth == expectedWidth && voHeight == expectedHeight
    }

    private fun transitionNeedsOldBufferQueueDrain(
        source: RenderSurfaceMode,
        destination: RenderSurfaceMode,
    ): Boolean {
        val changesZoomOwnership =
            (source == RenderSurfaceMode.BASE) !=
                (destination == RenderSurfaceMode.BASE)
        val touchesPanscanSurface =
            source == RenderSurfaceMode.VIEW_ASPECT_ORIGINAL ||
                destination == RenderSurfaceMode.VIEW_ASPECT_ORIGINAL
        return changesZoomOwnership || touchesPanscanSurface
    }

    private fun isContinuouslyRenderingVideo(): Boolean {
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

    private fun completeHandoff() {
        val deferred = deferredPanscanAction
        deferredPanscanAction = null
        cancelHandoff()
        if (deferred != null) {
            target.post {
                performPanscanTransition(deferred)
            }
        }
    }

    private fun cancelHandoff() {
        handoffToken++
        handoffCaptureGeneration++
        clearPendingCaptureTransition()
        pendingSurfaceTransition = null
        pendingTransitionNeedsQueueDrain = false
        pendingPanscanAction = null
        deferredPanscanAction = null
        panscanMutationInProgress = false
        pinchCapturePrimed = false
        handoffCaptureRequested = false
        handoffCaptureRetryOnNextFrame = false
        handoffCaptureReady = false
        removeHandoffPreDrawListener()
        removeHandoffFrameCommitCallback()
        handoffCommitPostedForToken = 0L
        handoffWaitingForFrame = 0L
        handoffExpectedSurfaceWidth = 0
        handoffExpectedSurfaceHeight = 0
        handoffExpectedPresentationTime = 0L
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
        handoffReleased = true
        handoffBitmapGeneration++
        handoffBitmapExecutor.shutdownNow()
        deferredPanscanAction = null
        cancelHandoff()
        if (!handoffCaptureInFlight)
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
        private const val MAX_HANDOFF_COPY_ATTEMPTS = 3

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
