package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pinch-to-zoom + pan for mpv output.
 *
 * Important quality detail:
 *  - Unzoomed view uses a display-sized mpv-rendered compact surface, so mpv,
 *    not Android's TextureView compositor, performs the huge downscale. This
 *    avoids moire / false-color artifacts on high-frequency scans at 720p.
 *  - After the first mpv frame is ready, the unzoomed view is prepared with the
 *    same media-aspect fit that will be used while zoomed. At normal size it
 *    uses only a display-sized compact buffer; when the user starts zooming it
 *    upgrades the same geometry to an original-detail buffer.
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

    private val viewConfiguration = ViewConfiguration.get(target.context)
    private val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * 0.22f))
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity.toFloat()

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

    // Lock the initial midpoint of the first two-finger pinch for the whole
    // touch sequence. ScaleGestureDetector.focusX/focusY follow the moving
    // centroid, which makes the zoom anchor drift as the fingers move.
    private var pinchFocusLocked = false
    private var pinchFocusX = 0.0
    private var pinchFocusY = 0.0

    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()
    private val multiPanFilterX = OneEuroFilter()
    private val multiPanFilterY = OneEuroFilter()

    // Two-finger translation is tracked independently from the locked pinch
    // focus. The locked point remains the scale anchor, while movement of the
    // current centroid contributes only a pan delta.
    private var multiPanFingerDown = false
    private var multiPanActive = false
    private var multiPanDownX = 0f
    private var multiPanDownY = 0f
    private var lastMultiPanX = 0f
    private var lastMultiPanY = 0f

    // Android-standard fling plumbing. VelocityTracker supplies release speed,
    // ViewConfiguration supplies device-scaled thresholds, and OverScroller
    // supplies the platform's normal friction/deceleration curve.
    private var velocityTracker: VelocityTracker? = null
    private var lastPanVelocityX = 0f
    private var lastPanVelocityY = 0f
    private var lastPanVelocityEventTime = Long.MIN_VALUE
    private var hasPannedInGesture = false
    private val flingScroller = OverScroller(target.context)
    private var flingPosted = false
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!flingScroller.computeScrollOffset()) {
                flingPosted = false
                return
            }

            val requestedTx = flingScroller.currX.toDouble()
            val requestedTy = flingScroller.currY.toDouble()
            tx = requestedTx
            ty = requestedTy
            clampTranslationToVideoContent()
            applyToView()

            target.postOnAnimation(this)
        }
    }

    private var requestedRenderSurfaceMode = RenderSurfaceMode.BASE
    private var displayedRenderSurfaceMode = RenderSurfaceMode.BASE
    private var surfaceModeTransitionInFlight: RenderSurfaceMode? = null
    private var queuedRenderSurfaceUpdate = false

    private var previousSurfaceFrameUptimeMs = Long.MIN_VALUE
    private var lastSurfaceFrameUptimeMs = Long.MIN_VALUE
    private var zoomRenderSurfaceMode: RenderSurfaceMode? = null
    private var zoomHighQualityRequested = false

    private var lastZoomMotionUptimeMs = 0L
    private var smoothedZoomVelocity = Float.POSITIVE_INFINITY
    private var slowZoomMotionSinceMs = 0L
    private var zoomQualityMonitorPosted = false
    private val zoomQualityMonitor = object : Runnable {
        override fun run() {
            zoomQualityMonitorPosted = false
            if (!scaleDetector.isInProgress || zoomHighQualityRequested || !isZoomed())
                return

            val now = SystemClock.uptimeMillis()
            val motionAge = now - lastZoomMotionUptimeMs
            val quietEnough = motionAge >= ZOOM_QUIET_GAP_MS
            val slowEnough = smoothedZoomVelocity <= ZOOM_SLOW_VELOCITY_PER_SECOND

            if (quietEnough && motionAge >= ZOOM_QUIET_UPGRADE_DELAY_MS) {
                requestZoomHighQuality()
                return
            }

            if (slowEnough) {
                if (slowZoomMotionSinceMs == 0L)
                    slowZoomMotionSinceMs = now
                if (now - slowZoomMotionSinceMs >= ZOOM_SLOW_DWELL_MS) {
                    requestZoomHighQuality()
                    return
                }
            } else if (!quietEnough) {
                slowZoomMotionSinceMs = 0L
            }

            postZoomQualityMonitor()
        }
    }

    // Keep the startup/exit window transitions on the plain mpv surface. Once
    // MPVActivity has a stable first frame hidden behind the startup preview, it
    // enables the compact normal surface so zoom can start/stop without a tear.
    private var normalCompactSurfacePrepared = false

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

                if (!pinchFocusLocked) {
                    pinchFocusX = detector.focusX.toDouble()
                    pinchFocusY = detector.focusY.toDouble()
                    pinchFocusLocked = true
                }

                // Switch to the original-detail buffer before the first visible zoom step.
                // If the first-frame preparation was skipped (for example, a remote file
                // without startup preview), arm the compact normal geometry now as a fallback.
                normalCompactSurfacePrepared = true
                val now = SystemClock.uptimeMillis()
                if (!isZoomed()) {
                    zoomHighQualityRequested = false
                    zoomRenderSurfaceMode = null
                }
                lastZoomMotionUptimeMs = now
                smoothedZoomVelocity = Float.POSITIVE_INFINITY
                slowZoomMotionSinceMs = 0L
                updateRenderSurfaceForCurrentState(force = false)
                applyToView()
                postZoomQualityMonitor()

                resetPanFilters(pinchFocusX.toFloat(), pinchFocusY.toFloat(), now)
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
                    resetPanFilters(pinchFocusX.toFloat(), pinchFocusY.toFloat(), SystemClock.uptimeMillis())
                    scheduleApply()
                    return true
                }

                pendingPinchDoubleTapReset = false
                if (newScale == oldScale)
                    return true

                // Keep the initial pinch midpoint stable for the entire touch
                // sequence instead of following the moving finger centroid.
                // transform: screen = scale * content + translation
                val fx = pinchFocusX
                val fy = pinchFocusY
                val k = (newScale / oldScale).toDouble()
                tx = (k * tx) + ((1.0 - k) * fx)
                ty = (k * ty) + ((1.0 - k) * fy)
                scale = newScale

                val now = SystemClock.uptimeMillis()
                updateZoomMotionVelocity(oldScale, newScale, now)
                clampTranslationToVideoContent()
                resetPanFilters(pinchFocusX.toFloat(), pinchFocusY.toFloat(), now)
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    stopZoomQualityMonitor()
                    resetPanFilters(pinchFocusX.toFloat(), pinchFocusY.toFloat(), SystemClock.uptimeMillis())
                    requestZoomHighQuality()
                }
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        stopFling()
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
        stopFling()
        videoAspect = aspect ?: 0.0
        videoPixelWidth = pixelSize?.first ?: 0
        videoPixelHeight = pixelSize?.second ?: 0
        panscan = panscanValue ?: 0.0
        zoomRenderSurfaceMode = null

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
        val now = SystemClock.uptimeMillis()
        previousSurfaceFrameUptimeMs = lastSurfaceFrameUptimeMs
        lastSurfaceFrameUptimeMs = now

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

        // Critical for scan quality: after returning to normal size, do not keep
        // the original-resolution texture and let Android minify it. Return to
        // the prepared compact normal surface so the next zoom starts from the
        // same geometry, without a start/end tear.
        updateRenderSurfaceForCurrentState(force = true)
        applyToView()
    }

    fun resetForNewFile() {
        resetTransformState()
        clearPinchFocusLock()
        videoAspect = 0.0
        videoPixelWidth = 0
        videoPixelHeight = 0
        panscan = 0.0
        normalCompactSurfacePrepared = false
        previousSurfaceFrameUptimeMs = Long.MIN_VALUE
        lastSurfaceFrameUptimeMs = Long.MIN_VALUE
        zoomRenderSurfaceMode = null
        zoomHighQualityRequested = false
        commitHiddenBaseRenderSurfaceMode()
        requestBaseRenderSurfaceSize(force = true)
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
        clearPinchFocusLock()
        normalCompactSurfacePrepared = false
        target.alpha = 0f
        commitHiddenBaseRenderSurfaceMode()
        requestBaseRenderSurfaceSize(force = true)
        applyToView()
    }

    private fun resetTransformState() {
        stopFling()
        recycleVelocityTracker()

        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

        scale = 1f
        tx = 0.0
        ty = 0.0
        panFingerDown = false
        panActive = false
        multiPanFingerDown = false
        multiPanActive = false
        canBeTap = false
        hasPannedInGesture = false
        lastPanVelocityX = 0f
        lastPanVelocityY = 0f
        lastPanVelocityEventTime = Long.MIN_VALUE
        lastTapTime = 0L
        pendingPinchDoubleTapReset = false
        stopZoomQualityMonitor()
        zoomRenderSurfaceMode = null
        zoomHighQualityRequested = false
        lastZoomMotionUptimeMs = 0L
        smoothedZoomVelocity = Float.POSITIVE_INFINITY
        slowZoomMotionSinceMs = 0L
        resetPanFilters(0f, 0f, SystemClock.uptimeMillis())
        resetMultiPanFilters(0f, 0f, SystemClock.uptimeMillis())
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

        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            stopFling()
            beginVelocityTracking(e)
            hasPannedInGesture = false
            lastPanVelocityX = 0f
            lastPanVelocityY = 0f
            lastPanVelocityEventTime = Long.MIN_VALUE
            clearPinchFocusLock()
        } else {
            velocityTracker?.addMovement(e)
        }

        // Capture the literal midpoint as soon as the second finger touches,
        // before ScaleGestureDetector's span threshold can delay onScaleBegin.
        if (!pinchFocusLocked &&
            e.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
            e.pointerCount >= 2
        ) {
            pinchFocusX = ((e.getX(0) + e.getX(1)) * 0.5f).toDouble()
            pinchFocusY = ((e.getY(0) + e.getY(1)) * 0.5f).toDouble()
            pinchFocusLocked = true
        }

        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.pointerCount >= 2)
            beginMultiPointerPan(e)

        // Feed the scale detector before applying centroid translation. Scaling
        // is therefore still performed around the locked initial midpoint, and
        // the current centroid contributes a separate pan delta afterwards.
        scaleDetector.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
            clearPinchFocusLock()

        if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            multiPanFingerDown = false
            multiPanActive = false
            canBeTap = false
            hasPannedInGesture = false
            recycleVelocityTracker()
            resetPanFilters(lastPointerX, lastPointerY, SystemClock.uptimeMillis())
            resetMultiPanFilters(lastMultiPanX, lastMultiPanY, SystemClock.uptimeMillis())
            return true
        }

        // Complete the last two-finger pan sample before rebasing to the
        // remaining pointer(s). Do not fling yet: the gesture remains active
        // until the final finger leaves the screen.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            if (multiPanFingerDown && e.pointerCount >= 2)
                processMultiPointerPanCurrentSample(e)

            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false

            val upIndex = e.actionIndex
            val remainingCount = e.pointerCount - 1
            when {
                remainingCount >= 2 -> {
                    beginMultiPointerPan(e, excludedPointerIndex = upIndex)
                }

                remainingCount == 1 && isZoomed() -> {
                    multiPanFingerDown = false
                    multiPanActive = false
                    val remainIndex = firstPointerIndexExcept(e, upIndex)
                    val x = e.getX(remainIndex)
                    val y = e.getY(remainIndex)
                    downX = x
                    downY = y
                    lastPointerX = x
                    lastPointerY = y
                    lastPanX = x
                    lastPanY = y
                    downTime = e.eventTime
                    resetPanFilters(x, y, e.eventTime)
                    panFingerDown = true
                }

                else -> {
                    multiPanFingerDown = false
                    multiPanActive = false
                }
            }
            return true
        }

        // Two or more fingers can pan and pinch at the same time. The centroid
        // movement is independent of ScaleGestureDetector.focusX/focusY, so it
        // does not unlock or move the fixed zoom anchor.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false

            if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount >= 2 && isZoomed()) {
                processMultiPointerPanMove(e)
                if (multiPanActive)
                    updateVelocitySnapshotForPointers(e)
            }
            return true
        }

        if (!isZoomed()) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                recycleVelocityTracker()
                multiPanFingerDown = false
                multiPanActive = false
                hasPannedInGesture = false
            }
            return pendingPinchDoubleTapReset
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastPointerX = e.x
                lastPointerY = e.y
                lastPanX = e.x
                lastPanY = e.y
                downTime = e.eventTime

                tapStartTx = tx
                tapStartTy = ty

                panFingerDown = true
                panActive = false
                multiPanFingerDown = false
                multiPanActive = false
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
                if (panActive)
                    updateVelocitySnapshotForPointer(e.getPointerId(0), e.eventTime)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val now = e.eventTime
                val moveDist = hypot(e.x - downX, e.y - downY)
                val wasTap = canBeTap && moveDist < touchSlop && (now - downTime) < DOUBLE_TAP_TIMEOUT

                if (panActive)
                    updateVelocitySnapshotForPointer(e.getPointerId(0), e.eventTime)

                panFingerDown = false
                panActive = false
                multiPanFingerDown = false
                multiPanActive = false
                canBeTap = false

                if (!wasTap) {
                    lastTapTime = 0L
                    startFlingFromTrackedVelocity(now)
                    recycleVelocityTracker()
                    hasPannedInGesture = false
                    resetPanFilters(lastPointerX, lastPointerY, now)
                    return true
                }

                recycleVelocityTracker()
                hasPannedInGesture = false

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
        }

        return true
    }

    private fun processPanSample(x: Float, y: Float, timeMs: Long) {
        lastPointerX = x
        lastPointerY = y

        val distFromDown = hypot(x - downX, y - downY)
        val gestureAge = (timeMs - downTime).coerceAtLeast(0L)

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

        val oldTx = tx
        val oldTy = ty
        tx += dx.toDouble()
        ty += dy.toDouble()
        clampTranslationToVideoContent()
        if (tx != oldTx || ty != oldTy) {
            hasPannedInGesture = true
            scheduleApply()
        }
    }

    private fun beginMultiPointerPan(e: MotionEvent, excludedPointerIndex: Int = -1) {
        val activePointerCount = e.pointerCount - if (excludedPointerIndex >= 0) 1 else 0
        if (activePointerCount < 2) {
            multiPanFingerDown = false
            multiPanActive = false
            return
        }

        val x = pointerCentroidX(
            e,
            historicalPosition = -1,
            excludedPointerIndex = excludedPointerIndex,
        )
        val y = pointerCentroidY(
            e,
            historicalPosition = -1,
            excludedPointerIndex = excludedPointerIndex,
        )
        multiPanDownX = x
        multiPanDownY = y
        lastMultiPanX = x
        lastMultiPanY = y
        multiPanFingerDown = true
        multiPanActive = false
        canBeTap = false
        resetMultiPanFilters(x, y, e.eventTime)
    }

    private fun processMultiPointerPanMove(e: MotionEvent) {
        if (!multiPanFingerDown)
            beginMultiPointerPan(e)
        if (!multiPanFingerDown)
            return

        for (historyPosition in 0 until e.historySize) {
            processMultiPanSample(
                pointerCentroidX(e, historyPosition, excludedPointerIndex = -1),
                pointerCentroidY(e, historyPosition, excludedPointerIndex = -1),
                e.getHistoricalEventTime(historyPosition),
            )
        }
        processMultiPointerPanCurrentSample(e)
    }

    private fun processMultiPointerPanCurrentSample(e: MotionEvent) {
        if (!multiPanFingerDown || e.pointerCount < 2 || !isZoomed())
            return

        processMultiPanSample(
            pointerCentroidX(e, historicalPosition = -1, excludedPointerIndex = -1),
            pointerCentroidY(e, historicalPosition = -1, excludedPointerIndex = -1),
            e.eventTime,
        )
    }

    private fun processMultiPanSample(x: Float, y: Float, timeMs: Long) {
        val distFromDown = hypot(x - multiPanDownX, y - multiPanDownY)
        if (!multiPanActive) {
            if (distFromDown < panStartSlop)
                return

            multiPanActive = true
            resetMultiPanFilters(x, y, timeMs)
            return
        }

        val params = filterParamsForCurrentScale()
        val panX: Float
        val panY: Float
        if (params.enabled) {
            panX = multiPanFilterX.filter(x, timeMs, params)
            panY = multiPanFilterY.filter(y, timeMs, params)
        } else {
            panX = x
            panY = y
        }

        val dx = panX - lastMultiPanX
        val dy = panY - lastMultiPanY
        lastMultiPanX = panX
        lastMultiPanY = panY
        if (dx == 0f && dy == 0f)
            return

        val oldTx = tx
        val oldTy = ty
        tx += dx.toDouble()
        ty += dy.toDouble()
        clampTranslationToVideoContent()
        if (tx != oldTx || ty != oldTy) {
            hasPannedInGesture = true
            scheduleApply()
        }
    }

    private fun pointerCentroidX(
        e: MotionEvent,
        historicalPosition: Int,
        excludedPointerIndex: Int,
    ): Float {
        var sum = 0f
        var count = 0
        for (pointerIndex in 0 until e.pointerCount) {
            if (pointerIndex == excludedPointerIndex)
                continue
            sum += if (historicalPosition >= 0)
                e.getHistoricalX(pointerIndex, historicalPosition)
            else
                e.getX(pointerIndex)
            count++
        }
        return if (count > 0) sum / count else 0f
    }

    private fun pointerCentroidY(
        e: MotionEvent,
        historicalPosition: Int,
        excludedPointerIndex: Int,
    ): Float {
        var sum = 0f
        var count = 0
        for (pointerIndex in 0 until e.pointerCount) {
            if (pointerIndex == excludedPointerIndex)
                continue
            sum += if (historicalPosition >= 0)
                e.getHistoricalY(pointerIndex, historicalPosition)
            else
                e.getY(pointerIndex)
            count++
        }
        return if (count > 0) sum / count else 0f
    }

    private fun firstPointerIndexExcept(e: MotionEvent, excludedPointerIndex: Int): Int {
        for (pointerIndex in 0 until e.pointerCount) {
            if (pointerIndex != excludedPointerIndex)
                return pointerIndex
        }
        return 0
    }

    private fun beginVelocityTracking(e: MotionEvent) {
        recycleVelocityTracker()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(e) }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun updateVelocitySnapshotForPointer(pointerId: Int, eventTime: Long) {
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, maximumFlingVelocity)
        lastPanVelocityX = tracker.getXVelocity(pointerId)
        lastPanVelocityY = tracker.getYVelocity(pointerId)
        lastPanVelocityEventTime = eventTime
    }

    private fun updateVelocitySnapshotForPointers(e: MotionEvent) {
        val tracker = velocityTracker ?: return
        if (e.pointerCount <= 0)
            return

        tracker.computeCurrentVelocity(1000, maximumFlingVelocity)
        var velocityX = 0f
        var velocityY = 0f
        for (pointerIndex in 0 until e.pointerCount) {
            val pointerId = e.getPointerId(pointerIndex)
            velocityX += tracker.getXVelocity(pointerId)
            velocityY += tracker.getYVelocity(pointerId)
        }
        lastPanVelocityX = velocityX / e.pointerCount
        lastPanVelocityY = velocityY / e.pointerCount
        lastPanVelocityEventTime = e.eventTime
    }

    private fun startFlingFromTrackedVelocity(releaseEventTime: Long) {
        if (!hasPannedInGesture || !isZoomed())
            return
        if (lastPanVelocityEventTime == Long.MIN_VALUE ||
            releaseEventTime - lastPanVelocityEventTime > MAX_FLING_VELOCITY_AGE_MS
        ) {
            return
        }

        var velocityX = lastPanVelocityX
        var velocityY = lastPanVelocityY
        if (abs(velocityX) < minimumFlingVelocity)
            velocityX = 0f
        if (abs(velocityY) < minimumFlingVelocity)
            velocityY = 0f
        if (velocityX == 0f && velocityY == 0f)
            return

        val bounds = translationBounds()
        val integerBounds = integerFlingBounds(bounds)
        if (integerBounds.minX == integerBounds.maxX)
            velocityX = 0f
        if (integerBounds.minY == integerBounds.maxY)
            velocityY = 0f
        if (velocityX == 0f && velocityY == 0f)
            return

        val startX = tx.roundToInt().coerceIn(integerBounds.minX, integerBounds.maxX)
        val startY = ty.roundToInt().coerceIn(integerBounds.minY, integerBounds.maxY)
        flingScroller.fling(
            startX,
            startY,
            velocityX.roundToInt(),
            velocityY.roundToInt(),
            integerBounds.minX,
            integerBounds.maxX,
            integerBounds.minY,
            integerBounds.maxY,
        )
        if (!flingScroller.isFinished) {
            flingPosted = true
            target.postOnAnimation(flingRunnable)
        }
    }

    private fun stopFling() {
        if (flingPosted)
            target.removeCallbacks(flingRunnable)
        if (!flingScroller.isFinished)
            flingScroller.forceFinished(true)
        flingPosted = false
    }

    private fun integerFlingBounds(bounds: TranslationBounds): IntegerTranslationBounds {
        val x = integerAxisBounds(bounds.minX, bounds.maxX)
        val y = integerAxisBounds(bounds.minY, bounds.maxY)
        return IntegerTranslationBounds(
            minX = x.min,
            maxX = x.max,
            minY = y.min,
            maxY = y.max,
        )
    }

    private fun integerAxisBounds(minValue: Double, maxValue: Double): IntegerAxisBounds {
        val inwardMin = ceil(minValue)
            .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
            .toInt()
        val inwardMax = floor(maxValue)
            .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
            .toInt()
        if (inwardMin <= inwardMax)
            return IntegerAxisBounds(inwardMin, inwardMax)

        // A sub-pixel valid range can contain no integer coordinate. Use its
        // midpoint for OverScroller and let the exact floating-point clamp keep
        // the rendered video inside the true bounds.
        val midpoint = ((minValue + maxValue) * 0.5)
            .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
            .roundToInt()
        return IntegerAxisBounds(midpoint, midpoint)
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

    private fun resetMultiPanFilters(x: Float, y: Float, timeMs: Long) {
        multiPanFilterX.reset(x, timeMs)
        multiPanFilterY.reset(y, timeMs)
        lastMultiPanX = x
        lastMultiPanY = y
    }

    private fun clearPinchFocusLock() {
        pinchFocusLocked = false
        pinchFocusX = 0.0
        pinchFocusY = 0.0
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

        val bounds = translationBounds()
        tx = tx.coerceIn(bounds.minX, bounds.maxX)
        ty = ty.coerceIn(bounds.minY, bounds.maxY)
    }

    private fun translationBounds(): TranslationBounds {
        val c = contentRect()

        // Clamp against the actual video edges, not the TextureView or its
        // letterbox/pillarbox bars. When the scaled video is smaller than the
        // viewport, the two edge-alignment positions form the available travel
        // range inside the bars; do not force it back to the centre. Once it is
        // larger than the viewport, the same bounds reverse naturally and keep
        // both viewport edges covered so no empty space can appear outside video.
        val xAtLeftEdge = (-scale * c.ox).toDouble()
        val xAtRightEdge = (viewWidth - scale * (c.ox + c.w)).toDouble()
        val yAtTopEdge = (-scale * c.oy).toDouble()
        val yAtBottomEdge = (viewHeight - scale * (c.oy + c.h)).toDouble()
        return TranslationBounds(
            minX = min(xAtLeftEdge, xAtRightEdge),
            maxX = max(xAtLeftEdge, xAtRightEdge),
            minY = min(yAtTopEdge, yAtBottomEdge),
            maxY = max(yAtTopEdge, yAtBottomEdge),
        )
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
        val zooming = isZoomed() || scaleDetector.isInProgress
        val desiredMode = if (!zooming || !zoomHighQualityRequested) {
            RenderSurfaceMode.BASE
        } else {
            zoomRenderSurfaceMode ?: selectZoomRenderSurfaceMode().also {
                zoomRenderSurfaceMode = it
            }
        }

        val transition = surfaceModeTransitionInFlight
        if (transition != null) {
            if (force || desiredMode != requestedRenderSurfaceMode)
                queuedRenderSurfaceUpdate = true
            return
        }

        when (desiredMode) {
            RenderSurfaceMode.BASE -> requestBaseRenderSurfaceSize(force)
            RenderSurfaceMode.VIEW_ASPECT_ORIGINAL -> requestViewAspectOriginalRenderSurfaceSize(force)
            RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL -> requestMediaAspectOriginalRenderSurfaceSize(force)
        }
    }

    private fun selectZoomRenderSurfaceMode(): RenderSurfaceMode {
        if (isPanscanActive())
            return RenderSurfaceMode.VIEW_ASPECT_ORIGINAL

        return if (shouldKeepViewAspectWhileZooming())
            RenderSurfaceMode.VIEW_ASPECT_ORIGINAL
        else
            RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL
    }

    private fun shouldKeepViewAspectWhileZooming(): Boolean {
        val currentTrackIsStillImage = try {
            MPVLib.getPropertyString("current-tracks/video/image")
                ?.equals("yes", ignoreCase = true) == true
        } catch (_: Throwable) {
            false
        }

        if (!currentTrackIsStillImage)
            return true

        val previous = previousSurfaceFrameUptimeMs
        val latest = lastSurfaceFrameUptimeMs
        if (previous == Long.MIN_VALUE || latest == Long.MIN_VALUE)
            return false

        val frameInterval = latest - previous
        val frameAge = SystemClock.uptimeMillis() - latest
        return frameInterval in 1..CONTINUOUS_SURFACE_FRAME_MAX_INTERVAL_MS &&
            frameAge in 0..CONTINUOUS_SURFACE_FRAME_MAX_AGE_MS
    }

    private fun requestBaseRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        if (!force && requestedRenderSurfaceMode == RenderSurfaceMode.BASE)
            return

        player.resetRenderSurfaceSize()
        markRenderSurfaceModeRequested(RenderSurfaceMode.BASE)
    }

    private fun requestViewAspectOriginalRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()

        if (!force && requestedRenderSurfaceMode == RenderSurfaceMode.VIEW_ASPECT_ORIGINAL)
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

        val bufferScale = limitedOriginalDetailBufferScale(
            baseWidth = viewWidth.toDouble(),
            baseHeight = viewHeight.toDouble(),
            content = c,
        )

        val bufferWidth = ceilToIntAtLeastOne(viewWidth.toDouble() * bufferScale)
        val bufferHeight = ceilToIntAtLeastOne(viewHeight.toDouble() * bufferScale)
        player.setRenderSurfaceSize(bufferWidth, bufferHeight)
        markRenderSurfaceModeRequested(RenderSurfaceMode.VIEW_ASPECT_ORIGINAL)
    }

    private fun requestMediaAspectOriginalRenderSurfaceSize(force: Boolean) {
        val player = renderTarget ?: return
        refreshMetricsFromTarget()

        if (!force && requestedRenderSurfaceMode == RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL)
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

        val bufferScale = limitedOriginalDetailBufferScale(
            baseWidth = c.w.toDouble(),
            baseHeight = c.h.toDouble(),
            content = c,
        )

        val bufferWidth = ceilToIntAtLeastOne(c.w.toDouble() * bufferScale)
        val bufferHeight = ceilToIntAtLeastOne(c.h.toDouble() * bufferScale)
        player.setRenderSurfaceSize(bufferWidth, bufferHeight)
        markRenderSurfaceModeRequested(RenderSurfaceMode.MEDIA_ASPECT_ORIGINAL)
    }

    private fun markRenderSurfaceModeRequested(mode: RenderSurfaceMode) {
        requestedRenderSurfaceMode = mode
        if (mode.usesMediaAspectFit == displayedRenderSurfaceMode.usesMediaAspectFit) {
            displayedRenderSurfaceMode = mode
            surfaceModeTransitionInFlight = null
        } else {
            surfaceModeTransitionInFlight = mode
        }
    }

    private fun commitHiddenBaseRenderSurfaceMode() {
        requestedRenderSurfaceMode = RenderSurfaceMode.BASE
        displayedRenderSurfaceMode = RenderSurfaceMode.BASE
        surfaceModeTransitionInFlight = null
        queuedRenderSurfaceUpdate = false
    }

    private fun updateZoomMotionVelocity(oldScale: Float, newScale: Float, now: Long) {
        val previousTime = lastZoomMotionUptimeMs
        lastZoomMotionUptimeMs = now
        if (previousTime <= 0L || now <= previousTime) {
            smoothedZoomVelocity = Float.POSITIVE_INFINITY
            slowZoomMotionSinceMs = 0L
            postZoomQualityMonitor()
            return
        }

        val dtSeconds = ((now - previousTime).toFloat() / 1000f)
            .coerceIn(MIN_ZOOM_VELOCITY_DT_SECONDS, MAX_ZOOM_VELOCITY_DT_SECONDS)
        val relativeDelta = abs(newScale - oldScale) / oldScale.coerceAtLeast(1f)
        val instantaneousVelocity = relativeDelta / dtSeconds
        smoothedZoomVelocity = if (smoothedZoomVelocity.isFinite()) {
            ZOOM_VELOCITY_SMOOTHING * smoothedZoomVelocity +
                (1f - ZOOM_VELOCITY_SMOOTHING) * instantaneousVelocity
        } else {
            instantaneousVelocity
        }

        if (smoothedZoomVelocity > ZOOM_SLOW_VELOCITY_PER_SECOND)
            slowZoomMotionSinceMs = 0L
        postZoomQualityMonitor()
    }

    private fun requestZoomHighQuality() {
        if (zoomHighQualityRequested || !isZoomed())
            return

        zoomHighQualityRequested = true
        zoomRenderSurfaceMode = null
        stopZoomQualityMonitor()
        updateRenderSurfaceForCurrentState(force = false)
    }

    private fun postZoomQualityMonitor() {
        if (zoomQualityMonitorPosted || zoomHighQualityRequested || !scaleDetector.isInProgress)
            return
        zoomQualityMonitorPosted = true
        target.postDelayed(zoomQualityMonitor, ZOOM_QUALITY_MONITOR_INTERVAL_MS)
    }

    private fun stopZoomQualityMonitor() {
        if (zoomQualityMonitorPosted) {
            target.removeCallbacks(zoomQualityMonitor)
            zoomQualityMonitorPosted = false
        }
    }

    private fun usesOppositeOrientationMediaAspectRenderSurface(): Boolean {
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

    private fun shouldAvoidViewAspectOriginalRenderSurface(): Boolean {
        if (viewWidth <= 1f || viewHeight <= 1f || videoPixelWidth <= 1 || videoPixelHeight <= 1)
            return false

        val c = contentRect()
        if (c.w <= 1f || c.h <= 1f)
            return false

        val bufferScale = originalDetailBufferScale(c)
        val viewAspectWidth = viewWidth.toDouble() * bufferScale
        val viewAspectHeight = viewHeight.toDouble() * bufferScale
        val mediaAspectWidth = c.w.toDouble() * bufferScale
        val mediaAspectHeight = c.h.toDouble() * bufferScale

        val viewAspectPixels = viewAspectWidth * viewAspectHeight
        val mediaAspectPixels = (mediaAspectWidth * mediaAspectHeight).coerceAtLeast(1.0)
        val wastedPixelRatio = viewAspectPixels / mediaAspectPixels
        val longestViewAspectEdge = max(viewAspectWidth, viewAspectHeight)

        return wastedPixelRatio >= MEDIA_ASPECT_FALLBACK_WASTE_RATIO ||
            longestViewAspectEdge >= MEDIA_ASPECT_FALLBACK_MAX_EDGE
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
    private data class TranslationBounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
    )
    private data class IntegerAxisBounds(val min: Int, val max: Int)
    private data class IntegerTranslationBounds(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
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
        private const val MAX_FLING_VELOCITY_AGE_MS = 80L
        private const val MEDIA_ORIENTATION_THRESHOLD = 1.08
        private const val VIEW_ORIENTATION_THRESHOLD = 1.08f
        private const val MEDIA_ASPECT_FALLBACK_WASTE_RATIO = 2.0
        private const val MEDIA_ASPECT_FALLBACK_MAX_EDGE = 8192.0
        private const val CONTINUOUS_SURFACE_FRAME_MAX_INTERVAL_MS = 250L
        private const val CONTINUOUS_SURFACE_FRAME_MAX_AGE_MS = 250L

        private const val ZOOM_QUALITY_MONITOR_INTERVAL_MS = 32L
        private const val ZOOM_QUIET_GAP_MS = 48L
        private const val ZOOM_QUIET_UPGRADE_DELAY_MS = 135L
        private const val ZOOM_SLOW_DWELL_MS = 145L
        private const val ZOOM_SLOW_VELOCITY_PER_SECOND = 0.32f
        private const val ZOOM_VELOCITY_SMOOTHING = 0.58f
        private const val MIN_ZOOM_VELOCITY_DT_SECONDS = 1f / 240f
        private const val MAX_ZOOM_VELOCITY_DT_SECONDS = 1f / 8f

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
