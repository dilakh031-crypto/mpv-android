package `is`.xyz.mpv

import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Samsung-style zoom/pan for mpv-android.
 *
 * This version intentionally does NOT switch between Android scaling and mpv video-zoom.
 * It uses one continuous presentation path:
 *
 *   mpv renders into a high-resolution Surface buffer -> Android SurfaceFlinger/GPU composes
 *   that buffer with a cheap transform matrix.
 *
 * Why this is different from the previous attempts:
 *  - SurfaceView transform stays smooth because it is just compositor work.
 *  - Quality is preserved because the Surface buffer is not limited to the physical screen size;
 *    it is fixed to the media/display resolution when possible, capped for memory/GPU safety.
 *  - mpv video-zoom/video-pan are reset and are not updated during gestures, so there is no
 *    mpv<->Android visual handoff and no heavy per-frame libmpv property spam.
 *
 * The player SurfaceView is also resized to the actual video rectangle inside the viewport.
 * Black bars are provided by the parent background, so the high-resolution Surface buffer is not
 * wasted rendering letterbox/pillarbox pixels. This is the closest architecture available in this
 * project without replacing mpv's Android VO with a custom libmpv/OpenGL renderer.
 *
 * IMPORTANT:
 *  - Touch input must come from an untransformed overlay view (gestureLayer), not from the
 *    transformed SurfaceView itself.
 */
internal class VideoZoomGestures(
    private val target: View
) {
    private var viewportW = 0f
    private var viewportH = 0f

    /** video display aspect ratio, rotation already applied. 0 => unknown */
    private var videoAspect = 0.0

    private val surfaceView: SurfaceView? = target as? SurfaceView
    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()

    // Logical transform applied by Android's compositor to the high-resolution Surface buffer.
    // 1.0 = normal. tx/ty are in parent/viewport pixels.
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var didDrag = false

    // Single-finger pan is applied on vsync so MotionEvent burstiness cannot turn into jitter.
    private var panFingerDown = false
    private var panPendingX = 0f
    private var panPendingY = 0f
    private var panFrameX = 0f
    private var panFrameY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var lastLayoutW = -1
    private var lastLayoutH = -1
    private var lastBufferW = -1
    private var lastBufferH = -1
    private var resetMpvZoomSent = false

    private val choreographer = Choreographer.getInstance()
    private var applyScheduled = false

    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false

        if (panFingerDown && didDrag && isZoomed() && !scaleDetector.isInProgress) {
            val dx = panPendingX - panFrameX
            val dy = panPendingY - panFrameY
            panFrameX = panPendingX
            panFrameY = panPendingY
            tx += dx
            ty += dy
        }

        clampTranslation()
        applySurfaceTransform()
    }

    private fun scheduleApply() {
        if (applyScheduled) return
        applyScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                didDrag = true
                ensureMpvZoomDisabled()
                updateSurfaceGeometry()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (viewportW <= 1f || viewportH <= 1f)
                    return true

                val oldScale = scale
                val newScale = (oldScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (newScale == oldScale)
                    return true

                // Keep pinch focus stable. The target view is laid out at baseRect.left/top,
                // then transformed as: parent = baseLeft + tx + scale * local.
                val b = baseRect()
                val k = newScale / oldScale
                tx = (k * tx) + ((1f - k) * (detector.focusX - b.left))
                ty = (k * ty) + ((1f - k) * (detector.focusY - b.top))
                scale = newScale

                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1f + EPS)
                    reset()
                else
                    scheduleApply()
            }
        }
    )

    fun setMetrics(width: Float, height: Float) {
        viewportW = width
        viewportH = height
        updateSurfaceGeometry()
        clampTranslation()
        applySurfaceTransform()
    }

    fun setVideoAspect(aspect: Double?) {
        videoAspect = aspect ?: 0.0
        updateSurfaceGeometry()
        clampTranslation()
        applySurfaceTransform()
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
        didDrag = false
        panFingerDown = false
        lastTapTime = 0L

        ensureMpvZoomDisabled(force = true)
        updateSurfaceGeometry()
        applySurfaceTransform()
    }

    /**
     * @return true if the event should be consumed.
     *         While zoomed: pinch/pan/double-tap are consumed.
     *         Single tap returns false so the Activity can toggle controls.
     */
    fun onTouchEvent(e: MotionEvent): Boolean {
        ensureMpvZoomDisabled()
        scaleDetector.onTouchEvent(e)

        // Pointer transition during pinch. If one finger lifts and another remains down,
        // reset the pan anchor to the remaining finger so there is no jump.
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
            scheduleApply()
            return true
        }

        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
            panFingerDown = false
            scheduleApply()
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
                panPendingX = e.x
                panPendingY = e.y

                if (!didDrag) {
                    val dist = hypot(e.x - downX, e.y - downY)
                    if (dist >= touchSlop)
                        didDrag = true
                }

                if (didDrag)
                    scheduleApply()

                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !didDrag && (now - downTime) < DOUBLE_TAP_TIMEOUT

                panFingerDown = false

                if (!wasTap) {
                    lastTapTime = 0L
                    scheduleApply()
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
                scheduleApply()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                panFingerDown = false
                scheduleApply()
                return true
            }
        }

        return true
    }

    /** The video rectangle inside the full viewport at base zoom. */
    private fun baseRect(): RectF2 {
        val w = viewportW
        val h = viewportH
        if (w <= 1f || h <= 1f)
            return RectF2(0f, 0f, w, h)

        val ar = if (videoAspect > 0.001) videoAspect.toFloat() else (w / h)
        val viewAr = w / h
        val bw: Float
        val bh: Float
        if (ar > viewAr) {
            bw = w
            bh = w / ar
        } else {
            bh = h
            bw = h * ar
        }
        return RectF2((w - bw) * 0.5f, (h - bh) * 0.5f, bw, bh)
    }

    private fun clampTranslation() {
        if (viewportW <= 1f || viewportH <= 1f)
            return

        if (scale <= 1f + EPS) {
            scale = 1f
            tx = 0f
            ty = 0f
            return
        }

        val b = baseRect()
        val scaledW = scale * b.w
        val scaledH = scale * b.h

        tx = if (scaledW <= viewportW + EPS) {
            ((viewportW - scaledW) * 0.5f) - b.left
        } else {
            val minTx = viewportW - b.left - scaledW
            val maxTx = -b.left
            tx.coerceIn(minTx, maxTx)
        }

        ty = if (scaledH <= viewportH + EPS) {
            ((viewportH - scaledH) * 0.5f) - b.top
        } else {
            val minTy = viewportH - b.top - scaledH
            val maxTy = -b.top
            ty.coerceIn(minTy, maxTy)
        }
    }

    private fun applySurfaceTransform() {
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.translationX = tx
        target.translationY = ty
    }

    /**
     * Resize the SurfaceView to the visible video rectangle and ask Android for a high-resolution
     * backing buffer. This is the key part that makes Android compositor zoom high quality.
     */
    private fun updateSurfaceGeometry() {
        if (viewportW <= 1f || viewportH <= 1f)
            return

        val b = baseRect()
        val layoutW = b.w.roundToInt().coerceAtLeast(1)
        val layoutH = b.h.roundToInt().coerceAtLeast(1)

        val lp = target.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            if (lp.width != layoutW || lp.height != layoutH || lp.gravity != Gravity.CENTER) {
                lp.width = layoutW
                lp.height = layoutH
                lp.gravity = Gravity.CENTER
                target.layoutParams = lp
            }
        }
        lastLayoutW = layoutW
        lastLayoutH = layoutH

        updateFixedSurfaceBuffer(layoutW, layoutH)
    }

    private fun updateFixedSurfaceBuffer(layoutW: Int, layoutH: Int) {
        val sv = surfaceView ?: return

        val source = readRotatedDisplaySize()
        val image = try { MPVLib.getPropertyBoolean("current-tracks/video/image") == true } catch (_: Throwable) { false }

        val desiredW: Int
        val desiredH: Int
        if (source != null) {
            desiredW = source.w.coerceAtLeast(layoutW)
            desiredH = source.h.coerceAtLeast(layoutH)
        } else {
            desiredW = (layoutW * FALLBACK_BUFFER_SCALE).roundToInt()
            desiredH = (layoutH * FALLBACK_BUFFER_SCALE).roundToInt()
        }

        val capped = capBufferSize(
            desiredW = desiredW,
            desiredH = desiredH,
            minW = layoutW,
            minH = layoutH,
            maxDim = if (image) MAX_IMAGE_SURFACE_DIMENSION else MAX_VIDEO_SURFACE_DIMENSION,
            maxPixels = if (image) MAX_IMAGE_SURFACE_PIXELS else MAX_VIDEO_SURFACE_PIXELS
        )

        if (capped.w == lastBufferW && capped.h == lastBufferH)
            return

        try {
            sv.holder.setFixedSize(capped.w, capped.h)
            lastBufferW = capped.w
            lastBufferH = capped.h
        } catch (_: Throwable) {
            // Some old/vendor devices can reject very large fixed surfaces. Fall back to layout
            // size rather than risking a black player.
            try {
                sv.holder.setFixedSize(layoutW, layoutH)
                lastBufferW = layoutW
                lastBufferH = layoutH
            } catch (_: Throwable) {
                // Last resort: leave SurfaceHolder as-is.
            }
        }
    }

    private fun capBufferSize(
        desiredW: Int,
        desiredH: Int,
        minW: Int,
        minH: Int,
        maxDim: Int,
        maxPixels: Int
    ): SizeI {
        var w = desiredW.toFloat().coerceAtLeast(minW.toFloat())
        var h = desiredH.toFloat().coerceAtLeast(minH.toFloat())

        val dimScale = min(1f, min(maxDim / w, maxDim / h))
        val pixelScale = min(1f, sqrt(maxPixels.toFloat() / (w * h)))
        val s = min(dimScale, pixelScale)
        w *= s
        h *= s

        // Do not return below layout size; if the cap would go below layout size, layout wins.
        return SizeI(
            w.roundToInt().coerceAtLeast(minW).coerceAtLeast(1),
            h.roundToInt().coerceAtLeast(minH).coerceAtLeast(1)
        )
    }

    /**
     * mpv exposes encoded size (w/h), display size after aspect correction (dw/dh), and rotate.
     * Use display size for the Surface aspect so Android does not distort anamorphic or rotated
     * videos/images.
     */
    private fun readRotatedDisplaySize(): SizeI? {
        fun propInt(name: String): Int? {
            return try { MPVLib.getPropertyInt(name) } catch (_: Throwable) { null }
        }

        val w = propInt("video-params/dw")
            ?: propInt("video-out-params/dw")
            ?: propInt("dwidth")
            ?: propInt("video-params/w")
            ?: return null
        val h = propInt("video-params/dh")
            ?: propInt("video-out-params/dh")
            ?: propInt("dheight")
            ?: propInt("video-params/h")
            ?: return null

        if (w <= 0 || h <= 0)
            return null

        val rot = propInt("video-params/rotate") ?: 0
        return if (rot % 180 == 90)
            SizeI(h, w)
        else
            SizeI(w, h)
    }

    private fun ensureMpvZoomDisabled(force: Boolean = false) {
        if (resetMpvZoomSent && !force)
            return
        resetMpvZoomSent = true
        try {
            MPVLib.setPropertyDouble("video-zoom", 0.0)
            MPVLib.setPropertyDouble("video-pan-x", 0.0)
            MPVLib.setPropertyDouble("video-pan-y", 0.0)
        } catch (_: Throwable) {
            // mpv may not be initialized yet during early Activity setup or after shutdown.
        }
    }

    private data class RectF2(val left: Float, val top: Float, val w: Float, val h: Float)
    private data class SizeI(val w: Int, val h: Int)

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L

        // Used only until mpv reports real video/image dimensions.
        private const val FALLBACK_BUFFER_SCALE = 2f

        // Video path: keep memory reasonable and cover 4K/UHD well.
        private const val MAX_VIDEO_SURFACE_DIMENSION = 4096
        private const val MAX_VIDEO_SURFACE_PIXELS = 12_000_000

        // Image path: allow larger still images, but keep a hard cap to avoid vendor crashes.
        private const val MAX_IMAGE_SURFACE_DIMENSION = 6144
        private const val MAX_IMAGE_SURFACE_PIXELS = 24_000_000
    }
}
