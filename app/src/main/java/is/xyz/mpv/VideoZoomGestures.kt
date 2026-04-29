--- START OF FILE app/src/main/java/is/xyz/mpv/VideoZoomGestures.kt ---
package `is`.xyz.mpv

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * High-FPS pinch-to-zoom + pan for mpv output.
 *
 * Design goals (Samsung-like):
 *  - 60fps/120fps zoom/pan (compositor-level by transforming the Android view)
 *  - While zoomed: one-finger pan, double-tap resets, seeking disabled
 *  - Black bars (letter/pillarbox) are treated as "not part of video":
 *      * They remain visible at small zoom.
 *      * They disappear naturally once the zoom reaches the point where the
 *        video content fills the viewport.
 *      * Panning is clamped to the *video content rect*, not the whole view,
 *        so you can't pan into the black bars.
 *
 * IMPORTANT:
 *  - Touch input must come from an UNTRANSFORMED overlay view (gesture layer),
 *    not from the transformed video view itself.
 */
internal class VideoZoomGestures(
    private val target: View,
) {
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** video aspect ratio (rotation already applied). 0 => unknown */
    private var videoAspect = 0.0

    private val touchSlop = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()

    // Linear scale factor (1.0 = normal)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var didDrag = false

    private var panFrameX = 0f
    private var panFrameY = 0f

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                didDrag = true
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
                // Our transform is: screen = scale * content + translation (pivot at 0,0).
                // To zoom around focus F (in screen coords) we must update translation as:
                //   t' = k * t + (1 - k) * F, where k = newScale / oldScale.
                //
                // The old implementation used (oldScale - newScale) * F, which becomes
                // increasingly wrong when already zoomed/panned, causing noticeable drift.
                val fx = detector.focusX
                val fy = detector.focusY
                val k = newScale / oldScale
                tx = (k * tx) + ((1f - k) * fx)
                ty = (k * ty) + ((1f - k) * fy)
                scale = newScale

                clampTranslationToVideoContent()
                applyToView()
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
            applyToView()
        }
    }

    fun setVideoAspect(aspect: Double?) {
        videoAspect = aspect ?: 0.0
        if (isZoomed()) {
            clampTranslationToVideoContent()
            applyToView()
        }
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        scale = 1f
        tx = 0f
        ty = 0f
        didDrag = false
        lastTapTime = 0L
        applyToView()
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
        // If one finger lifts and another remains down, update downX/downY and panFrameX/panFrameY so we don't jump.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && isZoomed()) {
            lastTapTime = 0L
            didDrag = true
            if (e.pointerCount >= 2) {
                val upIdx = e.actionIndex
                val remainIdx = if (upIdx == 0) 1 else 0
                val remainX = e.getX(remainIdx)
                val remainY = e.getY(remainIdx)
                downX = remainX
                downY = remainY
                panFrameX = remainX
                panFrameY = remainY
                downTime = SystemClock.uptimeMillis()
            }
            return true
        }

        // Multi-touch (or active pinch) should always be consumed.
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            lastTapTime = 0L
            didDrag = true
            return true
        }

        if (!isZoomed())
            return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                panFrameX = e.x
                panFrameY = e.y
                downTime = SystemClock.uptimeMillis()
                didDrag = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!didDrag) {
                    val dist = hypot(e.x - downX, e.y - downY)
                    if (dist >= touchSlop) {
                        didDrag = true
                        // Reset frame to the current point so we don't jump by the touchSlop distance
                        panFrameX = e.x
                        panFrameY = e.y
                    }
                }

                if (didDrag) {
                    if (!scaleDetector.isInProgress) {
                        val dx = e.x - panFrameX
                        val dy = e.y - panFrameY
                        tx += dx
                        ty += dy
                        
                        clampTranslationToVideoContent()
                        applyToView()
                    }
                    panFrameX = e.x
                    panFrameY = e.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val wasTap = !didDrag && (now - downTime) < DOUBLE_TAP_TIMEOUT

                if (!wasTap) {
                    lastTapTime = 0L
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
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                lastTapTime = 0L
                didDrag = false
                return true
            }
        }

        return true
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
            tx = 0f
            ty = 0f
            return
        }

        val c = contentRect()

        // Clamp independently per axis.
        // Use the transformed content rect (not the transformed whole view) so black bars are never "pan space".
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
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = scale
        target.scaleY = scale
        target.translationX = tx
        target.translationY = ty
    }

    private data class ContentRect(val ox: Float, val oy: Float, val w: Float, val h: Float)

    companion object {
        private const val EPS = 0.001f
        private const val MIN_SCALE = 1f
        // Increased maximum zoom from 6x to 20x.
        private const val MAX_SCALE = 20f
        private const val DOUBLE_TAP_TIMEOUT = 300L
    }
}
--- END OF FILE app/src/main/java/is/xyz/mpv/VideoZoomGestures.kt ---