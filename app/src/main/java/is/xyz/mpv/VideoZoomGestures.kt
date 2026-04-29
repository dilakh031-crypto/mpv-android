package `is`.xyz.mpv

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
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
 *    video-zoom/video-pan-x/y so the settled frame is rendered from the original source.
 *
 * The hard part is the handoff. If the SurfaceView transform is simply removed after mpv is
 * updated, Android can show a visible jump: one frame can be double-zoomed, unzoomed, or shifted
 * while mpv and the compositor catch up on different clocks. To make the handoff visually stable,
 * we keep a one-frame PixelCopy snapshot above the player during the switch, then fade it out.
 * The user should see only the expected quality change from compositor-upscaled to mpv-rendered.
 *
 * IMPORTANT:
 *  - Touch input must come from an untransformed overlay view (gestureLayer), not from the
 *    transformed video view itself.
 */
internal class VideoZoomGestures(
    private val target: View,
    private val handoffOverlay: ImageView? = null,
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
    // identity-transformed. When the next real gesture starts, we switch back to the compositor
    // fast path at the same logical transform.
    private var mpvQualityActive = false

    private var lastSentZoom = Double.NaN
    private var lastSentPanX = Double.NaN
    private var lastSentPanY = Double.NaN

    private val mainHandler = Handler(Looper.getMainLooper())
    private var handoffSerial = 0
    private var handoffBitmap: Bitmap? = null

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
        beginHighQualityHandoff()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun cancelHighQualityCommit() {
        target.removeCallbacks(highQualityCommitRunnable)
        handoffSerial++
    }

    private fun hideHandoffOverlay(clearBitmap: Boolean = false) {
        handoffSerial++
        handoffOverlay?.let { overlay ->
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.visibility = View.GONE
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            overlay.translationX = 0f
            overlay.translationY = 0f
            overlay.setImageDrawable(null)
        }
        if (clearBitmap) {
            handoffBitmap?.recycle()
            handoffBitmap = null
        }
    }

    /**
     * Switch from settled mpv-quality mode back to the old smooth SurfaceView transform mode.
     * This is intentionally done only when the user really starts dragging/pinching, not on a
     * simple tap, so tapping controls while zoomed does not cause a quality flicker.
     */
    private fun ensureInteractiveFastPath() {
        cancelHighQualityCommit()
        hideHandoffOverlay(clearBitmap = false)
        if (!mpvQualityActive)
            return

        // The reset is synchronous so we do not get a temporary double-transform when the
        // SurfaceView transform is restored for interactive movement.
        mpvQualityActive = false
        resetMpvTransform(force = true, synchronous = true)
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
        hideHandoffOverlay(clearBitmap = false)

        scale = 1f
        tx = 0f
        ty = 0f
        didDrag = false
        panFingerDown = false
        lastTapTime = 0L
        mpvQualityActive = false

        applyIdentityToView()
        resetMpvTransform(force = true, synchronous = true)
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
        applyTransformTo(target, scale, tx, ty)
    }

    private fun applyIdentityToView() {
        applyTransformTo(target, 1f, 0f, 0f)
    }

    private fun applyTransformTo(view: View, s: Float, x: Float, y: Float) {
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = s
        view.scaleY = s
        view.translationX = x
        view.translationY = y
    }

    private fun beginHighQualityHandoff() {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return

        clampTranslationToVideoContent()

        if (tryPixelCopyHandoff())
            return

        // Fallback for old Android versions or PixelCopy failures: do an immediate, synchronous
        // switch. This avoids the old delayed double-transform flash.
        commitHighQualityMpvTransform()
        applyIdentityToView()
    }

    private fun tryPixelCopyHandoff(): Boolean {
        val surfaceView = target as? SurfaceView ?: return false
        val overlay = handoffOverlay ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return false
        if (viewWidth <= 1f || viewHeight <= 1f)
            return false

        val w = viewWidth.toInt().coerceAtLeast(1)
        val h = viewHeight.toInt().coerceAtLeast(1)
        val bitmap = obtainHandoffBitmap(w, h) ?: return false
        val serial = ++handoffSerial

        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (serial != handoffSerial || scale <= 1f + EPS)
                    return@request

                if (result == PixelCopy.SUCCESS) {
                    showHandoffOverlay(overlay, bitmap)
                    commitHighQualityMpvTransform()
                    applyIdentityToView()
                    fadeHandoffOverlay(overlay, serial)
                } else {
                    commitHighQualityMpvTransform()
                    applyIdentityToView()
                }
            }, mainHandler)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun obtainHandoffBitmap(width: Int, height: Int): Bitmap? {
        val old = handoffBitmap
        if (old != null && !old.isRecycled && old.width == width && old.height == height)
            return old

        handoffOverlay?.setImageDrawable(null)
        old?.recycle()
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                handoffBitmap = it
            }
        } catch (_: Throwable) {
            handoffBitmap = null
            null
        }
    }

    private fun showHandoffOverlay(overlay: ImageView, bitmap: Bitmap) {
        overlay.animate().cancel()
        overlay.setImageBitmap(bitmap)
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        applyTransformTo(overlay, scale, tx, ty)
    }

    private fun fadeHandoffOverlay(overlay: ImageView, serial: Int) {
        overlay.animate().cancel()
        overlay.postDelayed({
            if (serial != handoffSerial)
                return@postDelayed
            overlay.animate()
                .alpha(0f)
                .setStartDelay(0L)
                .setDuration(HANDOFF_FADE_MS)
                .withEndAction {
                    if (serial == handoffSerial) {
                        overlay.visibility = View.GONE
                        overlay.setImageDrawable(null)
                        applyTransformTo(overlay, 1f, 0f, 0f)
                    }
                }
                .start()
        }, HANDOFF_HOLD_MS)
    }

    private fun commitHighQualityMpvTransform() {
        val mpvTransform = computeMpvTransform() ?: return

        // Synchronous property writes here are deliberate. They happen only at gesture end, not
        // continuously, and make the SurfaceView->mpv handoff much more deterministic.
        setMpvDouble("video-zoom", mpvTransform.zoom, force = false, synchronous = true)
        setMpvDouble("video-pan-x", mpvTransform.panX, force = false, synchronous = true)
        setMpvDouble("video-pan-y", mpvTransform.panY, force = false, synchronous = true)

        mpvQualityActive = true
    }

    private fun computeMpvTransform(): MpvTransform? {
        if (viewWidth <= 1f || viewHeight <= 1f || scale <= 1f + EPS)
            return null

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

        return MpvTransform(zoom, panX, panY)
    }

    private fun resetMpvTransform(force: Boolean, synchronous: Boolean) {
        setMpvDouble("video-zoom", 0.0, force, synchronous)
        setMpvDouble("video-pan-x", 0.0, force, synchronous)
        setMpvDouble("video-pan-y", 0.0, force, synchronous)
    }

    private fun setMpvDouble(property: String, value: Double, force: Boolean, synchronous: Boolean) {
        val previous = when (property) {
            "video-zoom" -> lastSentZoom
            "video-pan-x" -> lastSentPanX
            "video-pan-y" -> lastSentPanY
            else -> Double.NaN
        }

        if (!force && !previous.isNaN() && abs(previous - value) < MPV_APPLY_EPS)
            return

        try {
            if (synchronous) {
                MPVLib.setPropertyDouble(property, value)
            } else {
                val result = MPVLib.commandAsync(arrayOf("set", property, value.toString()), 0L)
                if (result < 0)
                    MPVLib.setPropertyDouble(property, value)
            }
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
    private data class MpvTransform(val zoom: Double, val panX: Double, val panY: Double)

    companion object {
        private const val EPS = 0.001f
        private const val MPV_APPLY_EPS = 0.00001
        private const val MIN_SCALE = 1f
        // Enough for deep inspection of 4K/6K images without forcing coarse jumps.
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L

        // Delay before switching from fast interactive SurfaceView transform to sharp mpv render.
        // Kept low so the image becomes clean quickly, but high enough to avoid committing while
        // MotionEvents are still arriving.
        private const val IDLE_COMMIT_DELAY_MS = 64L
        private const val SHORT_COMMIT_DELAY_MS = 16L

        // Snapshot handoff: a very short cover avoids spatial jumps without making video feel
        // frozen. The fade is just long enough to turn the switch into a quality improvement.
        private const val HANDOFF_HOLD_MS = 32L
        private const val HANDOFF_FADE_MS = 64L
        private const val LN_2 = 0.6931471805599453
    }
}
