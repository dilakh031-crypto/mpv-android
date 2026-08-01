package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
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

    // Exact midpoint of the two active pinch pointers, in the untransformed
    // gesture layer's coordinate space. ScaleGestureDetector exposes a generic
    // focus point (the centroid of every active pointer); keeping the raw
    // two-finger midpoint here makes the zoom anchor unambiguous.
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var hasPinchFocus = false

    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()

    // Use Android's own velocity estimation and viscous-fluid fling physics. This
    // matches the platform's scroll feel instead of maintaining a custom decay loop.
    private val panScroller = OverScroller(target.context)
    private var velocityTracker: VelocityTracker? = null
    private var activePanPointerId = MotionEvent.INVALID_POINTER_ID
    private var flingPosted = false
    private val flingRunnable = object : Runnable {
        override fun run() {
            flingPosted = false

            if (!isZoomed()) {
                panScroller.abortAnimation()
                return
            }

            if (!panScroller.computeScrollOffset())
                return

            tx = panScroller.currX.toDouble()
            ty = panScroller.currY.toDouble()
            clampTranslationToVideoContent()
            applyToView()

            if (!panScroller.isFinished) {
                flingPosted = true
                target.postOnAnimation(this)
            }
        }
    }

    // Pinch inertia uses the same Android viscous-fluid fling implementation as
    // panning. Scale is encoded logarithmically into a one-dimensional scroller:
    // equal distances therefore represent equal multiplicative zoom changes.
    private val zoomScroller = OverScroller(target.context)
    private var zoomFlingPosted = false
    private var zoomFlingUnitsPerLog = 1f
    private var zoomFlingFocusX = 0f
    private var zoomFlingFocusY = 0f
    private var zoomReleaseVelocityLogPerSecond = 0f
    private var zoomVelocitySampleTimeMs = 0L
    private var hasZoomVelocitySample = false

    // ScaleGestureDetector ends as soon as either pinch pointer lifts, while one
    // pointer is usually still down. Keep the release velocity briefly and only
    // launch the zoom fling when the final pointer lifts. If the remaining finger
    // starts panning, or is held, the candidate is discarded.
    private var pendingZoomFling = false
    private var pendingZoomFlingVelocityLogPerSecond = 0f
    private var pendingZoomFlingFocusX = 0f
    private var pendingZoomFlingFocusY = 0f
    private var pendingZoomFlingExpiryPosted = false
    private val pendingZoomFlingExpiryRunnable = Runnable {
        pendingZoomFlingExpiryPosted = false
        discardPendingZoomFling(requestHighQuality = true)
    }

    private val zoomFlingRunnable = object : Runnable {
        override fun run() {
            zoomFlingPosted = false

            if (scaleDetector.isInProgress || !isZoomed()) {
                finishZoomFling(requestHighQuality = isZoomed())
                return
            }

            if (!zoomScroller.computeScrollOffset()) {
                finishZoomFling(requestHighQuality = true)
                return
            }

            val oldScale = scale
            val newScale = exp(
                zoomScroller.currX.toDouble() / zoomFlingUnitsPerLog.toDouble(),
            ).toFloat().coerceIn(MIN_SCALE, MAX_SCALE)

            if (newScale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                cancelZoomFling(requestHighQuality = false)
                pendingPinchDoubleTapReset = true
                resetLikeDoubleTapAfterPinch()
                return
            }

            applyScaleAroundFocus(oldScale, newScale, zoomFlingFocusX, zoomFlingFocusY)
            updateZoomMotionVelocity(oldScale, newScale, SystemClock.uptimeMillis())
            clampTranslationToVideoContent()
            applyToView()

            if (zoomScroller.isFinished) {
                finishZoomFling(requestHighQuality = true)
            } else {
                zoomFlingPosted = true
                target.postOnAnimation(this)
            }
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
                cancelPanFling()
                cancelZoomFling(requestHighQuality = false)
                discardPendingZoomFling(requestHighQuality = false)
                recycleVelocityTracker()
                lastTapTime = 0L
                pendingPinchDoubleTapReset = false
                panActive = false
                canBeTap = false

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

                val focusX = currentPinchFocusX(detector)
                val focusY = currentPinchFocusY(detector)
                resetZoomVelocityTracking(now)
                resetPanFilters(focusX, focusY, now)
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
                    resetPanFilters(
                        currentPinchFocusX(detector),
                        currentPinchFocusY(detector),
                        SystemClock.uptimeMillis(),
                    )
                    scheduleApply()
                    return true
                }

                pendingPinchDoubleTapReset = false
                if (newScale == oldScale)
                    return true

                // Keep the exact point halfway between the two fingers stable.
                // transform: screen = scale * content + translation
                val focusX = currentPinchFocusX(detector)
                val focusY = currentPinchFocusY(detector)
                applyScaleAroundFocus(oldScale, newScale, focusX, focusY)

                val now = SystemClock.uptimeMillis()
                updateZoomReleaseVelocity(oldScale, newScale, detector.timeDelta, now)
                updateZoomMotionVelocity(oldScale, newScale, now)
                clampTranslationToVideoContent()
                resetPanFilters(focusX, focusY, now)
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    discardPendingZoomFling(requestHighQuality = false)
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    stopZoomQualityMonitor()
                    val now = SystemClock.uptimeMillis()
                    val focusX = currentPinchFocusX(detector)
                    val focusY = currentPinchFocusY(detector)
                    resetPanFilters(focusX, focusY, now)

                    val releaseVelocity = currentZoomReleaseVelocity(now)
                    if (releaseVelocity != null) {
                        setPendingZoomFling(releaseVelocity, focusX, focusY)
                    } else {
                        discardPendingZoomFling(requestHighQuality = false)
                        requestZoomHighQuality()
                    }
                }
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        cancelPanFling()
        cancelZoomFling(requestHighQuality = true)
        discardPendingZoomFling(requestHighQuality = true)
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
        cancelPanFling()
        cancelZoomFling(requestHighQuality = true)
        discardPendingZoomFling(requestHighQuality = true)
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
        normalCompactSurfacePrepared = false
        target.alpha = 0f
        commitHiddenBaseRenderSurfaceMode()
        requestBaseRenderSurfaceSize(force = true)
        applyToView()
    }

    private fun resetTransformState() {
        cancelPanFling()
        cancelZoomFling(requestHighQuality = false)
        discardPendingZoomFling(requestHighQuality = false)
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
        canBeTap = false
        lastTapTime = 0L
        pendingPinchDoubleTapReset = false
        hasPinchFocus = false
        stopZoomQualityMonitor()
        zoomRenderSurfaceMode = null
        zoomHighQualityRequested = false
        lastZoomMotionUptimeMs = 0L
        smoothedZoomVelocity = Float.POSITIVE_INFINITY
        slowZoomMotionSinceMs = 0L
        zoomReleaseVelocityLogPerSecond = 0f
        zoomVelocitySampleTimeMs = 0L
        hasZoomVelocitySample = false
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

        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelPanFling()
            cancelZoomFling(requestHighQuality = true)
            discardPendingZoomFling(requestHighQuality = true)
        }

        // Capture the raw two-finger midpoint before ScaleGestureDetector invokes
        // its callbacks. The detector's generic focus can include extra pointers,
        // while zoom anchoring here is intentionally defined by exactly two fingers.
        updatePinchFocusFromEvent(e)
        scaleDetector.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
            hasPinchFocus = false

        // ScaleGestureDetector ended when the other pinch pointer lifted. Launch
        // the stored zoom velocity only when the final pointer leaves, so zoom
        // inertia never fights a one-finger pan that follows the pinch.
        if (e.actionMasked == MotionEvent.ACTION_UP && pendingZoomFling) {
            panFingerDown = false
            panActive = false
            canBeTap = false
            lastTapTime = 0L
            recycleVelocityTracker()
            val started = startPendingZoomFling()
            if (!started)
                requestZoomHighQuality()
            return true
        }

        // Pointer transitions during pinch:
        // If one finger lifts and another remains down, rebase pan input so there is no jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            recycleVelocityTracker()
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
                beginVelocityTracking(e, remainIdx)
            }
            return true
        }

        // Multi-touch, or an active pinch, is handled only by ScaleGestureDetector.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            cancelPanFling()
            recycleVelocityTracker()
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            return true
        }

        if (!isZoomed()) {
            recycleVelocityTracker()
            return pendingPinchDoubleTapReset
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginVelocityTracking(e, 0)
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

                velocityTracker?.addMovement(e)

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
                velocityTracker?.addMovement(e)

                val now = SystemClock.uptimeMillis()
                val moveDist = hypot(e.x - downX, e.y - downY)
                val wasTap = canBeTap && moveDist < touchSlop && (now - downTime) < DOUBLE_TAP_TIMEOUT
                val wasPanActive = panActive
                val releaseVelocity = if (!wasTap && wasPanActive)
                    currentReleaseVelocity()
                else
                    null

                panFingerDown = false
                panActive = false
                canBeTap = false
                recycleVelocityTracker()

                if (!wasTap) {
                    lastTapTime = 0L
                    resetPanFilters(lastPointerX, lastPointerY, now)
                    releaseVelocity?.let { (velocityX, velocityY) ->
                        startPanFling(velocityX, velocityY)
                    }
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
                cancelPanFling()
                cancelZoomFling(requestHighQuality = true)
                discardPendingZoomFling(requestHighQuality = true)
                recycleVelocityTracker()
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

    private fun beginVelocityTracking(e: MotionEvent, pointerIndex: Int) {
        val safeIndex = pointerIndex.coerceIn(0, e.pointerCount - 1)
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.clear()
        activePanPointerId = e.getPointerId(safeIndex)
        tracker.addMovement(e)
    }

    private fun currentReleaseVelocity(): Pair<Float, Float>? {
        val tracker = velocityTracker ?: return null
        val pointerId = activePanPointerId
        if (pointerId == MotionEvent.INVALID_POINTER_ID)
            return null

        tracker.computeCurrentVelocity(1000, maximumFlingVelocity)
        var velocityX = tracker.getXVelocity(pointerId)
        var velocityY = tracker.getYVelocity(pointerId)

        val bounds = translationBounds()
        if (bounds.minTx >= bounds.maxTx - EPS)
            velocityX = 0f
        if (bounds.minTy >= bounds.maxTy - EPS)
            velocityY = 0f

        if (abs(velocityX) < minimumFlingVelocity)
            velocityX = 0f
        if (abs(velocityY) < minimumFlingVelocity)
            velocityY = 0f

        return if (velocityX == 0f && velocityY == 0f) null else velocityX to velocityY
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
        activePanPointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun startPanFling(velocityX: Float, velocityY: Float) {
        cancelZoomFling(requestHighQuality = true)
        discardPendingZoomFling(requestHighQuality = true)
        val bounds = translationBounds()
        val minX = flingBoundMin(bounds.minTx)
        val maxX = flingBoundMax(bounds.maxTx)
        val minY = flingBoundMin(bounds.minTy)
        val maxY = flingBoundMax(bounds.maxTy)

        val safeMinX = min(minX, maxX)
        val safeMaxX = max(minX, maxX)
        val safeMinY = min(minY, maxY)
        val safeMaxY = max(minY, maxY)
        val startX = flingPosition(tx).coerceIn(safeMinX, safeMaxX)
        val startY = flingPosition(ty).coerceIn(safeMinY, safeMaxY)
        val scaledVelocityX = flingVelocity(velocityX)
        val scaledVelocityY = flingVelocity(velocityY)

        if ((scaledVelocityX == 0 || safeMinX == safeMaxX) &&
            (scaledVelocityY == 0 || safeMinY == safeMaxY))
            return

        cancelPanFling()
        panScroller.fling(
            startX,
            startY,
            if (safeMinX == safeMaxX) 0 else scaledVelocityX,
            if (safeMinY == safeMaxY) 0 else scaledVelocityY,
            safeMinX,
            safeMaxX,
            safeMinY,
            safeMaxY,
        )

        if (!panScroller.isFinished) {
            flingPosted = true
            target.postOnAnimation(flingRunnable)
        }
    }

    private fun cancelPanFling() {
        if (flingPosted) {
            target.removeCallbacks(flingRunnable)
            flingPosted = false
        }
        if (!panScroller.isFinished)
            panScroller.abortAnimation()
    }

    private fun resetZoomVelocityTracking(now: Long) {
        zoomReleaseVelocityLogPerSecond = 0f
        zoomVelocitySampleTimeMs = now
        hasZoomVelocitySample = false
    }

    private fun updateZoomReleaseVelocity(
        oldScale: Float,
        newScale: Float,
        detectorTimeDeltaMs: Long,
        now: Long,
    ) {
        if (oldScale <= 0f || newScale <= 0f || oldScale == newScale)
            return

        val fallbackDeltaMs = now - zoomVelocitySampleTimeMs
        val deltaMs = if (detectorTimeDeltaMs > 0L) detectorTimeDeltaMs else fallbackDeltaMs
        if (deltaMs <= 0L)
            return

        val dtSeconds = (deltaMs.toFloat() / 1000f)
            .coerceIn(MIN_ZOOM_VELOCITY_DT_SECONDS, MAX_ZOOM_VELOCITY_DT_SECONDS)
        val instantaneous = (ln(newScale.toDouble() / oldScale.toDouble()) / dtSeconds)
            .toFloat()
        if (!instantaneous.isFinite())
            return

        zoomReleaseVelocityLogPerSecond = if (!hasZoomVelocitySample) {
            instantaneous
        } else {
            val sameDirection =
                zoomReleaseVelocityLogPerSecond == 0f ||
                    instantaneous == 0f ||
                    (zoomReleaseVelocityLogPerSecond > 0f) == (instantaneous > 0f)
            val previousWeight = if (sameDirection)
                ZOOM_FLING_VELOCITY_PREVIOUS_WEIGHT
            else
                ZOOM_FLING_DIRECTION_CHANGE_PREVIOUS_WEIGHT
            previousWeight * zoomReleaseVelocityLogPerSecond +
                (1f - previousWeight) * instantaneous
        }
        zoomVelocitySampleTimeMs = now
        hasZoomVelocitySample = true
    }

    private fun currentZoomReleaseVelocity(now: Long): Float? {
        if (!hasZoomVelocitySample || now - zoomVelocitySampleTimeMs > ZOOM_FLING_SAMPLE_MAX_AGE_MS)
            return null

        val units = zoomFlingUnitsForView()
        val minimumLogVelocity = max(
            MIN_ZOOM_FLING_LOG_VELOCITY_PER_SECOND,
            minimumFlingVelocity / units,
        )
        val maximumLogVelocity = min(
            MAX_ZOOM_FLING_LOG_VELOCITY_PER_SECOND,
            maximumFlingVelocity / units,
        ).coerceAtLeast(minimumLogVelocity)

        val velocity = zoomReleaseVelocityLogPerSecond
            .coerceIn(-maximumLogVelocity, maximumLogVelocity)
        if (abs(velocity) < minimumLogVelocity)
            return null
        if (velocity < 0f && scale <= MIN_SCALE + EPS)
            return null
        if (velocity > 0f && scale >= MAX_SCALE - EPS)
            return null
        return velocity
    }

    private fun setPendingZoomFling(velocityLogPerSecond: Float, focusX: Float, focusY: Float) {
        discardPendingZoomFling(requestHighQuality = false)
        pendingZoomFling = true
        pendingZoomFlingVelocityLogPerSecond = velocityLogPerSecond
        pendingZoomFlingFocusX = focusX
        pendingZoomFlingFocusY = focusY
        pendingZoomFlingExpiryPosted = true
        target.postDelayed(pendingZoomFlingExpiryRunnable, ZOOM_FLING_RELEASE_GRACE_MS)
    }

    private fun discardPendingZoomFling(requestHighQuality: Boolean) {
        val hadPendingFling = pendingZoomFling
        if (pendingZoomFlingExpiryPosted) {
            target.removeCallbacks(pendingZoomFlingExpiryRunnable)
            pendingZoomFlingExpiryPosted = false
        }
        pendingZoomFling = false
        pendingZoomFlingVelocityLogPerSecond = 0f

        if (requestHighQuality && hadPendingFling && isZoomed() && !scaleDetector.isInProgress)
            requestZoomHighQuality()
    }

    private fun startPendingZoomFling(): Boolean {
        if (!pendingZoomFling)
            return false

        val velocity = pendingZoomFlingVelocityLogPerSecond
        val focusX = pendingZoomFlingFocusX
        val focusY = pendingZoomFlingFocusY
        discardPendingZoomFling(requestHighQuality = false)
        return startZoomFling(velocity, focusX, focusY)
    }

    private fun startZoomFling(
        velocityLogPerSecond: Float,
        focusX: Float,
        focusY: Float,
    ): Boolean {
        if (!isZoomed() || velocityLogPerSecond == 0f)
            return false

        refreshMetricsFromTarget()
        val units = zoomFlingUnitsForView()
        val start = (ln(scale.toDouble()) * units).roundToInt()
        val minimum = (ln(MIN_SCALE.toDouble()) * units).roundToInt()
        val maximum = (ln(MAX_SCALE.toDouble()) * units).roundToInt()
        val velocity = (velocityLogPerSecond * units)
            .coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
            .roundToInt()

        if (abs(velocity) < minimumFlingVelocity.roundToInt())
            return false
        if (velocity < 0 && start <= minimum)
            return false
        if (velocity > 0 && start >= maximum)
            return false

        cancelPanFling()
        cancelZoomFling(requestHighQuality = false)
        zoomFlingUnitsPerLog = units
        zoomFlingFocusX = focusX
        zoomFlingFocusY = focusY
        zoomScroller.fling(
            start.coerceIn(minimum, maximum),
            0,
            velocity,
            0,
            minimum,
            maximum,
            0,
            0,
        )

        if (zoomScroller.isFinished)
            return false

        zoomFlingPosted = true
        target.postOnAnimation(zoomFlingRunnable)
        return true
    }

    private fun cancelZoomFling(requestHighQuality: Boolean) {
        val wasRunning = zoomFlingPosted || !zoomScroller.isFinished
        if (zoomFlingPosted) {
            target.removeCallbacks(zoomFlingRunnable)
            zoomFlingPosted = false
        }
        if (!zoomScroller.isFinished)
            zoomScroller.abortAnimation()

        if (requestHighQuality && wasRunning && isZoomed() && !scaleDetector.isInProgress)
            requestZoomHighQuality()
    }

    private fun finishZoomFling(requestHighQuality: Boolean) {
        if (zoomFlingPosted) {
            target.removeCallbacks(zoomFlingRunnable)
            zoomFlingPosted = false
        }
        if (!zoomScroller.isFinished)
            zoomScroller.abortAnimation()
        hasZoomVelocitySample = false

        if (requestHighQuality && isZoomed())
            requestZoomHighQuality()
    }

    private fun zoomFlingUnitsForView(): Float =
        max(viewWidth, viewHeight).coerceAtLeast(MIN_ZOOM_FLING_UNITS)

    private fun flingPosition(value: Double): Int =
        value.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).roundToInt()

    private fun flingBoundMin(value: Double): Int =
        ceil(value).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()

    private fun flingBoundMax(value: Double): Int =
        floor(value).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()

    private fun flingVelocity(value: Float): Int =
        value.coerceIn(Int.MIN_VALUE.toFloat(), Int.MAX_VALUE.toFloat()).roundToInt()

    private fun updatePinchFocusFromEvent(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            hasPinchFocus = false
            return
        }

        // ACTION_POINTER_UP still contains the pointer that is leaving. Exclude it
        // so a three-finger gesture cleanly falls back to the two fingers that remain.
        val ignoredIndex = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP)
            e.actionIndex
        else
            -1

        var firstIndex = -1
        var secondIndex = -1
        for (index in 0 until e.pointerCount) {
            if (index == ignoredIndex)
                continue
            if (firstIndex == -1)
                firstIndex = index
            else {
                secondIndex = index
                break
            }
        }

        if (secondIndex == -1)
            return

        pinchFocusX = (e.getX(firstIndex) + e.getX(secondIndex)) * 0.5f
        pinchFocusY = (e.getY(firstIndex) + e.getY(secondIndex)) * 0.5f
        hasPinchFocus = true
    }

    private fun currentPinchFocusX(detector: ScaleGestureDetector): Float =
        if (hasPinchFocus) pinchFocusX else detector.focusX

    private fun currentPinchFocusY(detector: ScaleGestureDetector): Float =
        if (hasPinchFocus) pinchFocusY else detector.focusY

    private fun applyScaleAroundFocus(
        oldScale: Float,
        newScale: Float,
        focusX: Float,
        focusY: Float,
    ) {
        if (oldScale <= 0f || newScale == oldScale)
            return

        val k = (newScale / oldScale).toDouble()
        tx = (k * tx) + ((1.0 - k) * focusX.toDouble())
        ty = (k * ty) + ((1.0 - k) * focusY.toDouble())
        scale = newScale
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
            discardPendingZoomFling(requestHighQuality = true)
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

    private fun translationBounds(): TranslationBounds {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return TranslationBounds(0.0, 0.0, 0.0, 0.0)

        val c = contentRect()
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        val minTx: Double
        val maxTx: Double
        if (contentWScaled <= viewWidth + EPS) {
            val centered = (((viewWidth - contentWScaled) * 0.5f) - scale * c.ox).toDouble()
            minTx = centered
            maxTx = centered
        } else {
            minTx = (viewWidth - scale * (c.ox + c.w)).toDouble()
            maxTx = (-scale * c.ox).toDouble()
        }

        val minTy: Double
        val maxTy: Double
        if (contentHScaled <= viewHeight + EPS) {
            val centered = (((viewHeight - contentHScaled) * 0.5f) - scale * c.oy).toDouble()
            minTy = centered
            maxTy = centered
        } else {
            minTy = (viewHeight - scale * (c.oy + c.h)).toDouble()
            maxTy = (-scale * c.oy).toDouble()
        }

        return TranslationBounds(minTx, maxTx, minTy, maxTy)
    }

    private fun clampTranslationToVideoContent() {
        if (viewWidth <= 1f || viewHeight <= 1f)
            return

        val bounds = translationBounds()
        tx = tx.coerceIn(bounds.minTx, bounds.maxTx)
        ty = ty.coerceIn(bounds.minTy, bounds.maxTy)
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
        val minTx: Double,
        val maxTx: Double,
        val minTy: Double,
        val maxTy: Double,
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

        private const val ZOOM_FLING_SAMPLE_MAX_AGE_MS = 100L
        private const val ZOOM_FLING_RELEASE_GRACE_MS = 180L
        private const val ZOOM_FLING_VELOCITY_PREVIOUS_WEIGHT = 0.35f
        private const val ZOOM_FLING_DIRECTION_CHANGE_PREVIOUS_WEIGHT = 0.12f
        private const val MIN_ZOOM_FLING_LOG_VELOCITY_PER_SECOND = 0.12f
        private const val MAX_ZOOM_FLING_LOG_VELOCITY_PER_SECOND = 4.0f
        private const val MIN_ZOOM_FLING_UNITS = 320f

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
