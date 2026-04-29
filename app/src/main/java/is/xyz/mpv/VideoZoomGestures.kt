package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Smooth pinch-to-zoom + pan for mpv output.
 *
 * This class deliberately uses a hybrid renderer:
 *  - while the finger is moving, the Android SurfaceView is transformed by the compositor;
 *    this is the same fast path as the old implementation and keeps gestures fluid.
 *  - shortly after the gesture stops, the same logical zoom/pan is committed to mpv with
 *    video-zoom/video-pan-x/y and the SurfaceView transform is released back to identity;
 *    this lets mpv render the selected area from the original high-resolution source.
 *
 * Continuous mpv-side zoom updates look sharp but are expensive on Android, especially for
 * video. Continuous SurfaceView-only zoom is smooth but magnifies the screen-sized frame.
 * The hybrid path keeps both: smooth interaction first, sharp settled image/video second.
 *
 * IMPORTANT:
 *  - Touch input must come from an untransformed overlay view (gestureLayer), not from the
 *    transformed video view itself.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio (rotation already applied). 0 => unknown */
    private var videoAspect = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()

    // Logical transform requested by the user (1.0 = normal, tx/ty in view pixels).
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var didDrag = false

    // Single-finger pan is applied on vsync to avoid bursty MotionEvent delivery causing
    // visible micro-stutter.
    private var panFingerDown = false
    private var panPendingX = 0f
    private var panPendingY = 0f
    private var panFrameX = 0f
    private var panFrameY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // True when mpv currently owns the settled high-quality zoom/pan and the SurfaceView is
    // supposed to be identity-transformed. When the next real gesture starts, we switch back
    // to the compositor fast path.
    private var mpvQualityActive = false

    private var lastSentZoom = Double.NaN
    private var lastSentPanX = Double.NaN
    private var lastSentPanY = Double.NaN

    // Coalesce view property updates to vsync.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false

        // Apply pan at a stable cadence (vsync) while zoomed.
        if (panFingerDown && didDrag && isZoomed() && !scaleDetector.isInProgress) {
            val dx = panPendingX - panFrameX
            val dy = panPendingY - panFrameY
            panFrameX = panPendingX
            panFrameY = panPendingY
            tx += dx
            ty += dy
        }

        clampTranslationToVideoContent()
        applyToView()
    }

    private val highQualityCommitRunnable = Runnable {
        commitHighQualityMpvTransform()
    }

    private val releaseSurfaceTransformRunnable = Runnable {
        if (mpvQualityActive)
            applyIdentityToView()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun cancelHighQualityCommit() {
        target.removeCallbacks(highQualityCommitRunnable)
        target.removeCallbacks(releaseSurfaceTransformRunnable)
    }

    /**
     * Switch from settled mpv-quality mode back to the old smooth SurfaceView transform mode.
     * This is intentionally done only when the user really starts dragging/pinching, not on a
     * simple tap, so tapping controls while zoomed does not cause a quality flicker.
     */
    private fun ensureInteractiveFastPath() {
        cancelHighQualityCommit()
        if (!mpvQualityActive)
            return

        mpvQualityActive = false
        resetMpvTransform(force = true)
        applyToView()
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                didDrag = true
                ensureInteractiveFastPath()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (viewWidth <= 1f || viewHeight <= 1f)
                    return true

                ensureInteractiveFastPath()

                val oldScale = scale
                val requested = oldScale * detector.scaleFactor
                val newScale = requested.coerceIn(MIN_SCALE, MAX_SCALE)
                if (newScale == oldScale)
                    return true

                // Keep pinch focus stable.
                // Our transform is: screen = scale * content + translation (pivot at 0,0).
                // To zoom around focus F (in screen coords), update translation as:
                //   t' = k * t + (1 - k) * F, where k = newScale / oldScale.
                val fx = detector.focusX
                val fy = detector.focusY
                val k = newScale / oldScale
                tx = (k * tx) + ((1f - k) * fx)
                ty = (k * ty) + ((1f - k) * fy)
                scale = newScale

                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1f + EPS)
                    reset()
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        if (isZoomed()) {
            clampTranslationToVideoContent()
            if (mpvQualityActive)
                scheduleHighQualityCommit(SHORT_COMMIT_DELAY_MS)
            else
                scheduleApply()
        }
    }

    fun setVideoAspect(aspect: Double?) {
        videoAspect = aspect ?: 0.0
        if (isZoomed()) {
            clampTranslationToVideoContent()
            if (mpvQualityActive)
                scheduleHighQualityCommit(SHORT_COMMIT_DELAY_MS)
            else
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
        cancelHighQualityCommit()

        scale = 1f
        tx = 0f
        ty = 0f
        didDrag = false
        panFingerDown = false
        lastTapTime = 0L
        mpvQualityActive = false

        applyIdentityToView()
        resetMpvTransform(force = true)
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
            panFingerDown = false
            if (e.pointerCount >= 2) {
                val upIdx = e.actionIndex
                val remainIdx = if (upIdx == 0) 1 else 0
                lastX = e.getX(remainIdx)
                lastY = e.getY(remainIdx)
                downX = lastX
                downY = lastY
                panPendingX = lastX
                panPendingY = lastY
                panFrameX = lastX
                panFrameY = lastY
                downTime = SystemClock.uptimeMillis()
            }
            return true
        }

        // Multi-touch (or active pinch) should always be consumed.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
            panFingerDown = false
            return true
        }

        if (!isZoomed())
            return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelHighQualityCommit()
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                panPendingX = e.x
                panPendingY = e.y
                panFrameX = e.x
                panFrameY = e.y
                panFingerDown = true
                downTime = SystemClock.uptimeMillis()
                didDrag = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y

                // Track latest pointer position; actual translation is applied on the next vsync.
                panPendingX = e.x
                panPendingY = e.y

                if (!didDrag) {
                    val dist = hypot(e.x - downX, e.y - downY)
                    if (dist >= touchSlop)
                        didDrag = true
                }

                if (didDrag) {
                    ensureInteractiveFastPath()
                    scheduleApply()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !didDrag && (now - downTime) < DOUBLE_TAP_TIMEOUT

                panFingerDown = false

                if (!wasTap) {
                    lastTapTime = 0L
                    finishGesture()
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

                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y
                // Keep/restore high-quality settled rendering after a simple tap.
                finishGesture()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                panFingerDown = false
                finishGesture()
                return true
            }
        }

        return true
    }

    private fun finishGesture() {
        clampTranslationToVideoContent()
        if (scale <= 1f + EPS) {
            reset()
        } else if (mpvQualityActive) {
            cancelHighQualityCommit()
            applyIdentityToView()
        } else {
            applyToView()
            scheduleHighQualityCommit(IDLE_COMMIT_DELAY_MS)
        }
    }

    private fun scheduleHighQualityCommit(delayMs: Long) {
        cancelHighQualityCommit()
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return
        target.postDelayed(highQualityCommitRunnable, delayMs)
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
            return
        }

        val c = contentRect()

        // Clamp independently per axis.
        // Use the transformed content rect (not the transformed whole view) so black bars are
        // never pan space.
        val contentWScaled = scale * c.w
        val contentHScaled = scale * c.h

        // X axis
        tx = if (contentWScaled <= viewWidth + EPS) {
            // Content smaller than viewport: keep it centered (no horizontal panning)
            ((viewWidth - contentWScaled) * 0.5f) - scale * c.ox
        } else {
            val minTx = viewWidth - scale * (c.ox + c.w)
            val maxTx = -scale * c.ox
            tx.coerceIn(minTx, maxTx)
        }

        // Y axis
        ty = if (contentHScaled <= viewHeight + EPS) {
            // Content smaller than viewport: keep it centered (no vertical panning)
            ((viewHeight - contentHScaled) * 0.5f) - scale * c.oy
        } else {
            val minTy = viewHeight - scale * (c.oy + c.h)
            val maxTy = -scale * c.oy
            ty.coerceIn(minTy, maxTy)
        }
    }

    private fun applyToView() {
        // Fast interactive path: compositor transform. This is intentionally the same kind of
        // transform the old implementation used, because it is extremely smooth on Android.
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.translationX = tx
        target.translationY = ty
    }

    private fun applyIdentityToView() {
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.translationX = 0f
        target.translationY = 0f
    }

    private fun commitHighQualityMpvTransform() {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return

        clampTranslationToVideoContent()
        val c = contentRect()
        val safeScale = scale.coerceAtLeast(MIN_SCALE)
        val zoom = log2(safeScale.toDouble())

        var panX = 0.0
        var panY = 0.0

        if (c.w > 1f && c.h > 1f) {
            // Desired transformed content rect from the same logical transform that drives the
            // fast SurfaceView path.
            val desiredLeft = safeScale * c.ox + tx
            val desiredTop = safeScale * c.oy + ty

            // mpv first centers the zoomed video, then applies pan as a fraction of the scaled
            // video dimensions. Convert from desired pixel displacement to mpv units.
            val centeredLeft = (viewWidth - safeScale * c.w) * 0.5f
            val centeredTop = (viewHeight - safeScale * c.h) * 0.5f
            panX = ((desiredLeft - centeredLeft) / (safeScale * c.w)).toDouble()
            panY = ((desiredTop - centeredTop) / (safeScale * c.h)).toDouble()
        }

        setMpvDouble("video-zoom", zoom, force = false)
        setMpvDouble("video-pan-x", panX, force = false)
        setMpvDouble("video-pan-y", panY, force = false)

        mpvQualityActive = true

        // Give mpv a short moment to present the new crop before releasing the compositor
        // transform. This avoids most visible snapping on slower devices, while still making the
        // settled frame high-quality almost immediately.
        target.removeCallbacks(releaseSurfaceTransformRunnable)
        target.postDelayed(releaseSurfaceTransformRunnable, RELEASE_SURFACE_TRANSFORM_DELAY_MS)
    }

    private fun resetMpvTransform(force: Boolean) {
        setMpvDouble("video-zoom", 0.0, force)
        setMpvDouble("video-pan-x", 0.0, force)
        setMpvDouble("video-pan-y", 0.0, force)
    }

    private fun setMpvDouble(property: String, value: Double, force: Boolean) {
        val previous = when (property) {
            "video-zoom" -> lastSentZoom
            "video-pan-x" -> lastSentPanX
            "video-pan-y" -> lastSentPanY
            else -> Double.NaN
        }

        if (!force && !previous.isNaN() && abs(previous - value) < MPV_APPLY_EPS)
            return

        try {
            val result = MPVLib.commandAsync(arrayOf("set", property, value.toString()), 0L)
            if (result < 0)
                MPVLib.setPropertyDouble(property, value)
            when (property) {
                "video-zoom" -> lastSentZoom = value
                "video-pan-x" -> lastSentPanX = value
                "video-pan-y" -> lastSentPanY = value
            }
        } catch (_: Throwable) {
            // mpv may not be initialized yet during early Activity setup or after shutdown.
        }
    }

    private fun log2(value: Double): Double = ln(value) / LN_2

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)

    companion object {
        private const val EPS = 0.001f
        private const val MPV_APPLY_EPS = 0.00001
        private const val MIN_SCALE = 1f
        // Enough for deep inspection of 4K/6K images without forcing coarse jumps.
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L

        // Delay before switching from fast interactive SurfaceView transform to sharp mpv render.
        // Kept low so the image becomes clean immediately after the finger stops, but high enough
        // to avoid committing while MotionEvents are still arriving.
        private const val IDLE_COMMIT_DELAY_MS = 80L
        private const val SHORT_COMMIT_DELAY_MS = 16L
        private const val RELEASE_SURFACE_TRANSFORM_DELAY_MS = 48L
        private const val LN_2 = 0.6931471805599453
    }
}
