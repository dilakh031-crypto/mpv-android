package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * High-FPS pinch-to-zoom + pan for mpv output.
 *
 * This version keeps zoom/pan inside mpv, but treats deep-zoom panning as a signal-processing
 * problem instead of simply copying raw touch coordinates to video-pan-x/y. At 19x/20x, tiny
 * touch sensor noise, skipped sub-pixel pan writes, and irregular MotionEvent spacing are visible
 * as micro-waves/zigzags even when the gesture is generally 60fps.
 *
 * The pan path therefore uses:
 *  - an adaptive One Euro filter on the finger position, strong at slow speed and light at speed;
 *  - a rendered transform (tx/ty) that follows the filtered target on every vsync;
 *  - much smaller mpv pan-write epsilon so slow 20x movement is not quantized into tiny steps;
 *  - MotionEvent historical samples, so bursts from the input dispatcher do not become jumps.
 *
 * IMPORTANT:
 *  - Touch input must come from an untransformed overlay view (gestureLayer).
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio (rotation already applied). 0 => unknown */
    private var videoAspect = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
    private val panStartSlop = max(1.25f, touchSlop * 0.22f)

    // Linear scale factor (1.0 = normal). mpv video-zoom is log2(scale), computed at apply time.
    private var scale = 1f

    // tx/ty is the transform currently rendered through mpv. targetTx/targetTy is where the
    // filtered finger signal says the image should go. Separating them removes micro-jitter caused
    // by uneven touch sampling without reintroducing the old delayed/sloppy pan feel.
    private var tx = 0f
    private var ty = 0f
    private var targetTx = 0f
    private var targetTy = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var didDrag = false
    private var tapCancelled = false

    // If a tiny micro-pan happens during what eventually becomes a tap, restore the pre-tap target
    // so double-tap reset remains reliable and single taps do not nudge the image.
    private var tapStartTargetTx = 0f
    private var tapStartTargetTy = 0f

    private var panFingerDown = false
    private var panActive = false
    private var panFilteredX = 0f
    private var panFilteredY = 0f
    private val panFilterX = OneEuroAxisFilter(PAN_MIN_CUTOFF, PAN_BETA, PAN_D_CUTOFF)
    private val panFilterY = OneEuroAxisFilter(PAN_MIN_CUTOFF, PAN_BETA, PAN_D_CUTOFF)

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var lastAppliedZoom = Double.NaN
    private var lastAppliedPanX = Double.NaN
    private var lastAppliedPanY = Double.NaN

    // Coalesce mpv property updates to vsync, but keep the loop alive while a finger is down or
    // while the rendered transform is still settling to the filtered target.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private var lastFrameTimeNs = 0L
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        applyScheduled = false

        val dt = frameDeltaSeconds(frameTimeNanos)

        if (isZoomed() && !scaleDetector.isInProgress) {
            val followMs = if (panFingerDown) TOUCH_FOLLOW_TIME_MS else SETTLE_FOLLOW_TIME_MS
            val alpha = timeAlpha(followMs, dt)
            tx += (targetTx - tx) * alpha
            ty += (targetTy - ty) * alpha

            if (abs(targetTx - tx) < TRANSLATION_EPS)
                tx = targetTx
            if (abs(targetTy - ty) < TRANSLATION_EPS)
                ty = targetTy
        } else {
            tx = targetTx
            ty = targetTy
        }

        clampTranslationToVideoContent()
        applyToMpv()

        if (isZoomed() && (panFingerDown || translationNeedsSettling()))
            scheduleApply()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun frameDeltaSeconds(frameTimeNanos: Long): Float {
        val previous = lastFrameTimeNs
        lastFrameTimeNs = frameTimeNanos
        if (previous == 0L)
            return DEFAULT_FRAME_DT

        return ((frameTimeNanos - previous) / 1_000_000_000f).coerceIn(MIN_FRAME_DT, MAX_FRAME_DT)
    }

    private fun resetFrameClock() {
        lastFrameTimeNs = 0L
    }

    private fun syncTargetToRendered() {
        targetTx = tx
        targetTy = ty
    }

    private fun translationNeedsSettling(): Boolean {
        return abs(targetTx - tx) > TRANSLATION_EPS || abs(targetTy - ty) > TRANSLATION_EPS
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                didDrag = true
                tapCancelled = true
                panActive = false
                panFingerDown = false
                syncTargetToRendered()
                resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)
                if (newScale == oldScale)
                    return true

                // Keep pinch focus stable.
                //
                // Our logical transform is: screen = scale * content + translation (pivot at 0,0).
                // To zoom around focus F (in screen coords) update translation as:
                //   t' = k * t + (1 - k) * F, where k = newScale / oldScale.
                // This logical transform is then converted to mpv's video-zoom/video-pan-x/y.
                val fx = detector.focusX
                val fy = detector.focusY
                val k = newScale / oldScale
                tx = (k * tx) + ((1f - k) * fx)
                ty = (k * ty) + ((1f - k) * fy)
                scale = newScale
                syncTargetToRendered()
                resetFrameClock()

                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1f + EPS)
                    reset()
                else
                    syncTargetToRendered()
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (isZoomed()) {
            clampTranslationToVideoContent()
            scheduleApply()
        }
    }

    fun setVideoAspect(aspect: Double?) {
        videoAspect = aspect ?: 0.0
        if (isZoomed()) {
            clampTranslationToVideoContent()
            scheduleApply()
        }
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

        scale = 1f
        tx = 0f
        ty = 0f
        syncTargetToRendered()
        didDrag = false
        tapCancelled = false
        panFingerDown = false
        panActive = false
        lastTapTime = 0L
        resetFrameClock()
        resetPanFilters(0f, 0f, SystemClock.uptimeMillis())
        applyToMpv(force = true)
    }

    /**
     * @return true if the event should be consumed.
     *         While zoomed: pinch/pan/double-tap are consumed.
     *         Single tap returns false so the Activity can toggle controls.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        // Always feed the scale detector first.
        scaleDetector.onTouchEvent(e)

        // Pointer transitions during pinch:
        // If one finger lifts and another remains down, update lastX/lastY so we don't jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            didDrag = true
            tapCancelled = true
            panFingerDown = false
            panActive = false
            syncTargetToRendered()
            if (e.pointerCount >= 2) {
                val upIdx = e.actionIndex
                val remainIdx = if (upIdx == 0) 1 else 0
                lastX = e.getX(remainIdx)
                lastY = e.getY(remainIdx)
                downX = lastX
                downY = lastY
                downTime = SystemClock.uptimeMillis()
                resetPanFilters(lastX, lastY, downTime)
            }
            return true
        }

        // Multi-touch (or active pinch) should always be consumed.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
            tapCancelled = true
            panFingerDown = false
            panActive = false
            syncTargetToRendered()
            return true
        }

        if (!isZoomed())
            return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                panFingerDown = true
                panActive = false
                downTime = SystemClock.uptimeMillis()
                didDrag = false
                tapCancelled = false
                tapStartTargetTx = targetTx
                tapStartTargetTy = targetTy
                resetFrameClock()
                resetPanFilters(e.x, e.y, e.eventTime)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y

                val dist = hypot(e.x - downX, e.y - downY)
                if (dist >= touchSlop) {
                    tapCancelled = true
                    didDrag = true
                    lastTapTime = 0L
                }

                if (!panActive && dist >= panStartSlop) {
                    panActive = true
                    // Start from the current sample; do not apply the accumulated slop distance as
                    // a jump. This is important for slow 20x panning.
                    resetPanFilters(e.x, e.y, e.eventTime)
                } else if (panActive) {
                    for (i in 0 until e.historySize)
                        addPanSample(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                    addPanSample(e.x, e.y, e.eventTime)
                }

                if (panActive)
                    scheduleApply()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !tapCancelled && (now - downTime) < DOUBLE_TAP_TIMEOUT

                panFingerDown = false
                panActive = false

                if (!wasTap) {
                    lastTapTime = 0L
                    if (translationNeedsSettling())
                        scheduleApply()
                    return true
                }

                // Undo any tiny micro-pan admitted below touchSlop so taps never leave the image
                // microscopically shifted and double-tap reset stays reliable.
                targetTx = tapStartTargetTx
                targetTy = tapStartTargetTy

                // Double-tap anywhere while zoomed => reset.
                val dt = now - lastTapTime
                val dist = hypot(e.x - lastTapX, e.y - lastTapY)
                if (lastTapTime != 0L && dt < DOUBLE_TAP_TIMEOUT && dist < touchSlop * 3f) {
                    reset()
                    lastTapTime = 0L
                    return true
                }

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                if (translationNeedsSettling())
                    scheduleApply()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                tapCancelled = false
                panFingerDown = false
                panActive = false
                syncTargetToRendered()
                return true
            }
        }

        return true
    }

    private fun resetPanFilters(x: Float, y: Float, timeMs: Long) {
        panFilteredX = x
        panFilteredY = y
        panFilterX.reset(x, timeMs)
        panFilterY.reset(y, timeMs)
    }

    private fun addPanSample(x: Float, y: Float, timeMs: Long) {
        val fx = panFilterX.filter(x, timeMs)
        val fy = panFilterY.filter(y, timeMs)
        val dx = fx - panFilteredX
        val dy = fy - panFilteredY
        panFilteredX = fx
        panFilteredY = fy

        if (dx != 0f || dy != 0f) {
            targetTx += dx
            targetTy += dy
            clampTranslationToVideoContent()
        }
    }

    /**
     * Compute the content (video) rect within the view at base scale.
     */
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
            // video wider => fit width
            cw = w
            ch = w / ar
        } else {
            // video taller/narrower => fit height
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
            scale = 1f
            tx = 0f
            ty = 0f
            syncTargetToRendered()
            return
        }

        val c = contentRect()

        // Clamp independently per axis.
        // Use the transformed content rect (not the transformed whole view) so black bars are never "pan space".
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        fun clampX(value: Float): Float {
            return if (contentWScaled <= viewWidth + EPS) {
                // Content smaller than viewport: keep it centered (no horizontal panning)
                ((viewWidth - contentWScaled) * 0.5f) - scale * c.ox
            } else {
                val minTx = viewWidth - scale * (c.ox + c.w)
                val maxTx = -scale * c.ox
                value.coerceIn(minTx, maxTx)
            }
        }

        fun clampY(value: Float): Float {
            return if (contentHScaled <= viewHeight + EPS) {
                // Content smaller than viewport: keep it centered (no vertical panning)
                ((viewHeight - contentHScaled) * 0.5f) - scale * c.oy
            } else {
                val minTy = viewHeight - scale * (c.oy + c.h)
                val maxTy = -scale * c.oy
                value.coerceIn(minTy, maxTy)
            }
        }

        tx = clampX(tx)
        targetTx = clampX(targetTx)
        ty = clampY(ty)
        targetTy = clampY(targetTy)
    }

    private fun applyToMpv(force: Boolean = false) {
        // Keep the Android surface unscaled. All zoom happens in mpv so source pixels are preserved.
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.translationX = 0f
        target.translationY = 0f

        val c = contentRect()
        val safeScale = scale.coerceAtLeast(MIN_SCALE)
        val zoom = if (safeScale <= 1f + EPS) 0.0 else log2(safeScale.toDouble())

        var panX = 0.0
        var panY = 0.0

        if (safeScale > 1f + EPS && c.w > 1f && c.h > 1f) {
            // Desired transformed content rect from the gesture model.
            val desiredLeft = safeScale * c.ox + tx
            val desiredTop = safeScale * c.oy + ty

            // mpv first centers the zoomed video, then applies pan as a fraction of the
            // scaled video dimensions. Convert from desired pixel displacement to mpv units.
            val centeredLeft = (viewWidth - safeScale * c.w) * 0.5f
            val centeredTop = (viewHeight - safeScale * c.h) * 0.5f
            panX = ((desiredLeft - centeredLeft) / (safeScale * c.w)).toDouble()
            panY = ((desiredTop - centeredTop) / (safeScale * c.h)).toDouble()
        }

        setMpvDouble("video-zoom", zoom, force)
        setMpvDouble("video-pan-x", panX, force)
        setMpvDouble("video-pan-y", panY, force)
    }

    private fun setMpvDouble(property: String, value: Double, force: Boolean) {
        val previous = when (property) {
            "video-zoom" -> lastAppliedZoom
            "video-pan-x" -> lastAppliedPanX
            "video-pan-y" -> lastAppliedPanY
            else -> Double.NaN
        }

        val eps = when (property) {
            "video-zoom" -> ZOOM_APPLY_EPS
            "video-pan-x", "video-pan-y" -> PAN_APPLY_EPS
            else -> PAN_APPLY_EPS
        }

        if (!force && !previous.isNaN() && abs(previous - value) < eps)
            return

        try {
            MPVLib.setPropertyDouble(property, value)
            when (property) {
                "video-zoom" -> lastAppliedZoom = value
                "video-pan-x" -> lastAppliedPanX = value
                "video-pan-y" -> lastAppliedPanY = value
            }
        } catch (_: Throwable) {
            // mpv may not be initialized yet during early Activity setup.
        }
    }

    private fun timeAlpha(timeMs: Float, dtSeconds: Float): Float {
        val tau = max(timeMs, 1f) / 1000f
        return (1f - exp((-dtSeconds / tau).toDouble()).toFloat()).coerceIn(0f, 1f)
    }

    private fun log2(value: Double): Double = ln(value) / LN_2

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)

    private class OneEuroAxisFilter(
        private val minCutoff: Double,
        private val beta: Double,
        private val dCutoff: Double,
    ) {
        private var initialized = false
        private var xPrev = 0.0
        private var dxPrev = 0.0
        private var tPrev = 0L

        fun reset(value: Float, timeMs: Long) {
            initialized = true
            xPrev = value.toDouble()
            dxPrev = 0.0
            tPrev = timeMs
        }

        fun filter(value: Float, timeMs: Long): Float {
            if (!initialized) {
                reset(value, timeMs)
                return value
            }

            val dt = ((timeMs - tPrev) / 1000.0).coerceIn(1.0 / 240.0, 1.0 / 15.0)
            val raw = value.toDouble()
            val dx = (raw - xPrev) / dt
            val edx = lowPass(dx, dxPrev, alpha(dCutoff, dt))
            val cutoff = minCutoff + beta * abs(edx)
            val result = lowPass(raw, xPrev, alpha(cutoff, dt))

            xPrev = result
            dxPrev = edx
            tPrev = timeMs
            return result.toFloat()
        }

        private fun lowPass(value: Double, previous: Double, alpha: Double): Double {
            return (alpha * value) + ((1.0 - alpha) * previous)
        }

        private fun alpha(cutoff: Double, dt: Double): Double {
            val tau = 1.0 / (2.0 * PI * cutoff.coerceAtLeast(0.0001))
            return 1.0 / (1.0 + tau / dt)
        }
    }

    companion object {
        private const val EPS = 0.001f
        private const val TRANSLATION_EPS = 0.01f
        private const val ZOOM_APPLY_EPS = 0.000001
        private const val PAN_APPLY_EPS = 0.0000001
        private const val MIN_SCALE = 1f
        // Enough for deep inspection of 4K/6K images without forcing users into coarse jumps.
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val LN_2 = 0.6931471805599453

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FRAME_DT = 1f / 240f
        private const val MAX_FRAME_DT = 1f / 20f
        private const val TOUCH_FOLLOW_TIME_MS = 14f
        private const val SETTLE_FOLLOW_TIME_MS = 24f

        // One Euro tuning for touch coordinates in screen pixels. Lower minCutoff filters slow
        // movement strongly; beta raises the cutoff when the finger is actually moving faster.
        private const val PAN_MIN_CUTOFF = 1.05
        private const val PAN_BETA = 0.018
        private const val PAN_D_CUTOFF = 1.0
    }
}
