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
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pinch-to-zoom + pan implemented entirely by mpv's video renderer.
 *
 * Android never scales or translates the video surface. The SurfaceView remains at
 * the real window/display size and mpv receives video-zoom/video-pan updates once
 * per display frame. This has two important consequences:
 *
 *  - At 1x, mpv renders straight into the screen-sized SurfaceView; there is no
 *    compact media-aspect buffer for Android to resample back into the window.
 *  - While zoomed, mpv samples the original decoded frame for the currently visible
 *    region directly into the same screen-sized output. There is no screenshot-like
 *    TextureView magnification and no render-surface size transition at zoom start/end.
 *
 * The touch experience intentionally follows the previous Android-transform zoom:
 * fixed two-finger zoom anchor, one-finger pan, bounded pan, double-tap reset,
 * deep-zoom noise filtering, and Android-style inertial pan/fling.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** currently displayed aspect ratio, including video-aspect-override. 0 => unknown */
    private var videoAspect = 0.0
    private var panscan = 0.0

    private val viewConfiguration = ViewConfiguration.get(target.context)
    private val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
    private val panStartSlop = max(1f, min(2.5f, touchSlop * 0.22f))
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity.toFloat()

    // Keep the same screen-space transform model as the previous zoom implementation.
    // It is only a convenient gesture model now; it is never applied to the Android View.
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
    private var panMovedDuringTouch = false
    private var canBeTap = false

    private var tapStartTx = 0.0
    private var tapStartTy = 0.0

    // Keep zoom centered on the midpoint captured when the second pointer arrives.
    private var pinchTouchSessionActive = false
    private var lockedPinchFocusX = 0f
    private var lockedPinchFocusY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val panFilterX = OneEuroFilter()
    private val panFilterY = OneEuroFilter()

    // Android's native spline fling, expressed in screen-pixel translation space.
    private val panScroller = OverScroller(target.context)
    private var flingFramePosted = false
    private val flingFrameCallback = Choreographer.FrameCallback {
        flingFramePosted = false
        if (panScroller.computeScrollOffset()) {
            tx = panScroller.currX.toDouble()
            ty = panScroller.currY.toDouble()
            clampTranslationToVideoContent()
            applyToMpv()
            if (!panScroller.isFinished)
                postFlingFrame()
        }
    }

    // One-finger-only velocity stream; pinch motion is never allowed to seed a fling.
    private var panVelocityTracker: VelocityTracker? = null
    private var velocityGestureDownTimeMs = 0L

    private var pendingPinchDoubleTapReset = false

    // Coalesce all touch samples to one mpv transform update per display frame.
    private val choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        clampTranslationToVideoContent()
        applyToMpv()
    }

    // Avoid sending identical JNI/property updates on every vsync.
    private var lastAppliedZoom = Double.NaN
    private var lastAppliedPanX = Double.NaN
    private var lastAppliedPanY = Double.NaN

    init {
        // The previous implementation transformed the Android video view. The new path never
        // does; normalize once in case this Activity is being recreated from that code path.
        resetAndroidViewTransform()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                stopFling()
                lastTapTime = 0L
                pendingPinchDoubleTapReset = false
                panActive = false
                canBeTap = false

                if (!pinchTouchSessionActive) {
                    pinchTouchSessionActive = true
                    lockedPinchFocusX = detector.focusX
                    lockedPinchFocusY = detector.focusY
                }

                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                refreshMetricsFromTarget()
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val newScale = (oldScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)

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

                // Preserve the exact same fixed-anchor gesture geometry as before:
                // screen = scale * baseContent + translation.
                val fx = lockedPinchFocusX.toDouble()
                val fy = lockedPinchFocusY.toDouble()
                val k = (newScale / oldScale).toDouble()
                tx = k * tx + (1.0 - k) * fx
                ty = k * ty + (1.0 - k) * fy
                scale = newScale

                clampTranslationToVideoContent()
                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    // Flush the final scale immediately; no Surface transition exists to wait for.
                    applyToMpv()
                }
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        stopFling()
        viewWidth = width
        viewHeight = height
        refreshMetricsFromTarget()
        clampTranslationToVideoContent()
        scheduleApply()
    }

    fun setVideoGeometry(
        aspect: Double?,
        panscanValue: Double?,
        immediate: Boolean = false,
    ) {
        stopFling()
        videoAspect = aspect ?: 0.0
        panscan = (panscanValue ?: 0.0).coerceAtLeast(0.0)

        clampTranslationToVideoContent()
        if (immediate)
            applyToMpv(force = true)
        else
            scheduleApply()
    }

    fun applyPredictedAspectMenuGeometry(
        aspect: Double?,
        panscanValue: Double?,
    ) {
        setVideoGeometry(
            aspect = aspect,
            panscanValue = panscanValue,
            immediate = true,
        )
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun isFlingInProgress(): Boolean = !panScroller.isFinished || flingFramePosted

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchDoubleTapReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        resetTransformState()
        applyToMpv(force = true)
    }

    fun resetForNewFile() {
        resetTransformState()
        videoAspect = 0.0
        panscan = 0.0
        applyToMpv(force = true)
    }

    fun prepareForWindowExit() {
        resetTransformState()
        applyToMpv(force = true)
    }

    private fun resetTransformState() {
        stopFling()
        recyclePanVelocityTracker()
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

        scale = 1f
        tx = 0.0
        ty = 0.0
        panFingerDown = false
        panActive = false
        panMovedDuringTouch = false
        canBeTap = false
        lastTapTime = 0L
        pendingPinchDoubleTapReset = false
        resetPanFilters(0f, 0f, SystemClock.uptimeMillis())

        // Never leave an Android transform behind. All visual movement belongs to mpv.
        resetAndroidViewTransform()
    }

    private fun resetLikeDoubleTapAfterPinch() {
        target.post {
            if (scaleDetector.isInProgress) {
                resetLikeDoubleTapAfterPinch()
                return@post
            }

            if (!pendingPinchDoubleTapReset && scale > PINCH_DOUBLE_TAP_RESET_SCALE)
                return@post

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

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                endPinchTouchSession()
                panMovedDuringTouch = false
                beginPanVelocityTracking(e.x, e.y, e.eventTime)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stopFling()
                recyclePanVelocityTracker()
                panMovedDuringTouch = false
                panFingerDown = false
                panActive = false
                canBeTap = false
                beginPinchTouchSession(e)
            }
        }

        scaleDetector.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
            endPinchTouchSession()

        if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
            stopFling()
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            resetPanFilters(lastPointerX, lastPointerY, SystemClock.uptimeMillis())
            recyclePanVelocityTracker()
            return isZoomed() || pendingPinchDoubleTapReset
        }

        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            canBeTap = false
            val remainingPointerCount = e.pointerCount - 1
            if (remainingPointerCount == 1) {
                val upIdx = e.actionIndex
                val remainIdx = firstPointerIndexExcept(e, upIdx)
                if (remainIdx < 0)
                    return true
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
                panMovedDuringTouch = false
                rebasePanVelocityTracking(x, y, e.eventTime)
                panFingerDown = true
            }
            return true
        }

        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP)
            return true

        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            panFingerDown = false
            panActive = false
            panMovedDuringTouch = false
            canBeTap = false
            recyclePanVelocityTracker()
            return true
        }

        if (!isZoomed()) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
                recyclePanVelocityTracker()
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

                addPanVelocitySample(e.x, e.y, e.eventTime, MotionEvent.ACTION_UP)
                val releaseVelocity = releasePanVelocity()

                panFingerDown = false
                panActive = false
                canBeTap = false

                if (!wasTap) {
                    lastTapTime = 0L
                    resetPanFilters(lastPointerX, lastPointerY, now)
                    if (panMovedDuringTouch)
                        startFling(releaseVelocity.x, releaseVelocity.y)
                    recyclePanVelocityTracker()
                    return true
                }

                val dt = now - lastTapTime
                val dist = hypot(e.x - lastTapX, e.y - lastTapY)
                if (lastTapTime != 0L && dt < DOUBLE_TAP_TIMEOUT && dist < touchSlop * 3f) {
                    reset()
                    lastTapTime = 0L
                    recyclePanVelocityTracker()
                    return true
                }

                tx = tapStartTx
                ty = tapStartTy
                clampTranslationToVideoContent()
                applyToMpv()

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                resetPanFilters(e.x, e.y, now)
                recyclePanVelocityTracker()
                return false
            }
        }

        return true
    }

    private fun beginPinchTouchSession(e: MotionEvent) {
        val focus = pointerCentroid(e) ?: return
        if (!pinchTouchSessionActive) {
            pinchTouchSessionActive = true
            lockedPinchFocusX = focus.x
            lockedPinchFocusY = focus.y
        }
    }

    private fun endPinchTouchSession() {
        pinchTouchSessionActive = false
        lockedPinchFocusX = 0f
        lockedPinchFocusY = 0f
    }

    private fun pointerCentroid(e: MotionEvent): PointerCentroid? {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until e.pointerCount) {
            sumX += e.getX(i)
            sumY += e.getY(i)
            count++
        }
        if (count == 0)
            return null
        return PointerCentroid(sumX / count, sumY / count)
    }

    private fun firstPointerIndexExcept(e: MotionEvent, excludedPointerIndex: Int): Int {
        for (i in 0 until e.pointerCount) {
            if (i != excludedPointerIndex)
                return i
        }
        return -1
    }

    private fun processPanSample(x: Float, y: Float, timeMs: Long) {
        addPanVelocitySample(x, y, timeMs)
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
        panMovedDuringTouch = true
        clampTranslationToVideoContent()
        scheduleApply()
    }

    private fun beginPanVelocityTracking(x: Float, y: Float, timeMs: Long) {
        recyclePanVelocityTracker()
        panVelocityTracker = VelocityTracker.obtain()
        velocityGestureDownTimeMs = timeMs
        addPanVelocitySample(x, y, timeMs, MotionEvent.ACTION_DOWN)
    }

    private fun rebasePanVelocityTracking(x: Float, y: Float, timeMs: Long) {
        val tracker = panVelocityTracker ?: VelocityTracker.obtain().also {
            panVelocityTracker = it
        }
        tracker.clear()
        velocityGestureDownTimeMs = timeMs
        addPanVelocitySample(x, y, timeMs, MotionEvent.ACTION_DOWN)
    }

    private fun addPanVelocitySample(
        x: Float,
        y: Float,
        timeMs: Long,
        action: Int = MotionEvent.ACTION_MOVE,
    ) {
        val tracker = panVelocityTracker ?: return
        val safeEventTime = max(timeMs, velocityGestureDownTimeMs)
        val event = MotionEvent.obtain(
            velocityGestureDownTimeMs,
            safeEventTime,
            action,
            x,
            y,
            0,
        )
        tracker.addMovement(event)
        event.recycle()
    }

    private fun currentPanVelocity(): PanVelocity {
        val tracker = panVelocityTracker ?: return PanVelocity.ZERO
        tracker.computeCurrentVelocity(1000, maximumFlingVelocity)
        return PanVelocity(
            x = tracker.getXVelocity(VELOCITY_POINTER_ID),
            y = tracker.getYVelocity(VELOCITY_POINTER_ID),
        )
    }

    private fun releasePanVelocity(): PanVelocity = currentPanVelocity()

    private fun recyclePanVelocityTracker() {
        panVelocityTracker?.recycle()
        panVelocityTracker = null
        velocityGestureDownTimeMs = 0L
    }

    private fun startFling(rawVelocityX: Float, rawVelocityY: Float) {
        if (!isZoomed() || scaleDetector.isInProgress)
            return

        refreshMetricsFromTarget()
        clampTranslationToVideoContent()
        val bounds = translationBounds()

        val velocityX = rawVelocityX
            .takeIf { abs(it) >= minimumFlingVelocity }
            ?.coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
            ?: 0f
        val velocityY = rawVelocityY
            .takeIf { abs(it) >= minimumFlingVelocity }
            ?.coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
            ?: 0f

        if (velocityX == 0f && velocityY == 0f)
            return

        panScroller.fling(
            tx.roundToInt(),
            ty.roundToInt(),
            velocityX.roundToInt(),
            velocityY.roundToInt(),
            bounds.minX.roundToInt(),
            bounds.maxX.roundToInt(),
            bounds.minY.roundToInt(),
            bounds.maxY.roundToInt(),
        )
        postFlingFrame()
    }

    private fun postFlingFrame() {
        if (flingFramePosted)
            return
        flingFramePosted = true
        choreographer.postFrameCallback(flingFrameCallback)
    }

    private fun stopFling() {
        if (!panScroller.isFinished)
            panScroller.forceFinished(true)
        if (flingFramePosted) {
            choreographer.removeFrameCallback(flingFrameCallback)
            flingFramePosted = false
        }
    }

    private fun scheduleApply() {
        if (applyScheduled)
            return
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

    /**
     * Base mpv video rectangle before our video-zoom. This mirrors normal keepaspect
     * fitting and linearly applies panscan from fit (0) to fill (1).
     */
    private fun contentRect(): ContentRect {
        val w = viewWidth
        val h = viewHeight
        if (w <= 1f || h <= 1f)
            return ContentRect(0f, 0f, w, h)

        val ar = if (videoAspect > 0.001) videoAspect.toFloat() else (w / h)
        val viewAr = w / h

        var cw: Float
        var ch: Float
        if (ar > viewAr) {
            cw = w
            ch = w / ar
        } else {
            ch = h
            cw = h * ar
        }

        val ps = panscan.coerceIn(0.0, 1.0).toFloat()
        if (ps > 0f && cw > 0f && ch > 0f) {
            val fillScale = max(w / cw, h / ch)
            val appliedScale = 1f + ps * (fillScale - 1f)
            cw *= appliedScale
            ch *= appliedScale
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

        val bounds = translationBounds()
        tx = tx.coerceIn(bounds.minX, bounds.maxX)
        ty = ty.coerceIn(bounds.minY, bounds.maxY)
    }

    private fun translationBounds(): TranslationBounds {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return TranslationBounds(0.0, 0.0, 0.0, 0.0)

        val c = contentRect()
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        val minX: Double
        val maxX: Double
        if (contentWScaled <= viewWidth + EPS) {
            val centeredX = (((viewWidth - contentWScaled) * 0.5f) - scale * c.ox).toDouble()
            minX = centeredX
            maxX = centeredX
        } else {
            minX = (viewWidth - scale * (c.ox + c.w)).toDouble()
            maxX = (-scale * c.ox).toDouble()
        }

        val minY: Double
        val maxY: Double
        if (contentHScaled <= viewHeight + EPS) {
            val centeredY = (((viewHeight - contentHScaled) * 0.5f) - scale * c.oy).toDouble()
            minY = centeredY
            maxY = centeredY
        } else {
            minY = (viewHeight - scale * (c.oy + c.h)).toDouble()
            maxY = (-scale * c.oy).toDouble()
        }

        return TranslationBounds(minX, maxX, minY, maxY)
    }

    /**
     * Convert the old screen-space transform into mpv's native renderer coordinates.
     *
     * mpv video-pan is measured as a fraction of the *scaled video size*. At zero
     * pan, mpv centers the zoomed rectangle. Our tx/ty model is a top-left affine
     * transform, so the center-origin correction is (scale - 1) * window / 2.
     */
    private fun mpvTransform(): MpvTransform {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return MpvTransform(0.0, 0.0, 0.0)

        val c = contentRect()
        if (c.w <= EPS || c.h <= EPS)
            return MpvTransform(0.0, 0.0, 0.0)

        val s = scale.toDouble()
        val centerCorrectionX = (s - 1.0) * viewWidth.toDouble() * 0.5
        val centerCorrectionY = (s - 1.0) * viewHeight.toDouble() * 0.5

        val panX = (tx + centerCorrectionX) / (s * c.w.toDouble())
        val panY = (ty + centerCorrectionY) / (s * c.h.toDouble())
        val zoom = ln(s) / LN_2

        return MpvTransform(
            zoom = zoom,
            panX = panX,
            panY = panY,
        )
    }

    private fun applyToMpv(force: Boolean = false) {
        val transform = mpvTransform()
        val changed = force ||
                !sameMpvValue(transform.zoom, lastAppliedZoom) ||
                !sameMpvValue(transform.panX, lastAppliedPanX) ||
                !sameMpvValue(transform.panY, lastAppliedPanY)
        if (!changed)
            return

        MPVLib.setVideoTransform(transform.zoom, transform.panX, transform.panY)
        lastAppliedZoom = transform.zoom
        lastAppliedPanX = transform.panX
        lastAppliedPanY = transform.panY
    }

    private fun resetAndroidViewTransform() {
        if (target.scaleX != 1f) target.scaleX = 1f
        if (target.scaleY != 1f) target.scaleY = 1f
        if (target.translationX != 0f) target.translationX = 0f
        if (target.translationY != 0f) target.translationY = 0f
        if (target.alpha != 1f) target.alpha = 1f
    }

    private fun sameMpvValue(a: Double, b: Double): Boolean {
        if (!a.isFinite() || !b.isFinite())
            return false
        return abs(a - b) <= MPV_VALUE_EPS
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
    private data class PointerCentroid(val x: Float, val y: Float)
    private data class PanVelocity(val x: Float, val y: Float) {
        companion object {
            val ZERO = PanVelocity(0f, 0f)
        }
    }
    private data class TranslationBounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
    )
    private data class MpvTransform(val zoom: Double, val panX: Double, val panY: Double)
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
        private const val VELOCITY_POINTER_ID = 0

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f

        private const val FILTER_START_SCALE = 10f
        private const val FILTER_MIN_CUTOFF_AT_START = 12f
        private const val FILTER_MIN_CUTOFF_AT_MAX = 6f
        private const val FILTER_BETA_AT_START = 0.020f
        private const val FILTER_BETA_AT_MAX = 0.050f
        private const val FILTER_D_CUTOFF = 1.0f

        private const val LN_2 = 0.6931471805599453
        private const val MPV_VALUE_EPS = 1e-7
    }
}
