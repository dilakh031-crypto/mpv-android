package `is`.xyz.mpv

import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pinch-to-zoom + pan for mpv output.
 *
 * Rendering model:
 *  - mpv always renders into the Android SurfaceTexture. We never use mpv's video-pan or
 *    video-zoom for finger movement.
 *  - At scale 1 the surface is exactly the TextureView size, matching stock mpv-android.
 *    mpv therefore performs the normal downscale and all configured scale/dscale options,
 *    including correct-downscaling.
 *  - While a pinch is moving, Android updates only the content matrix so touch remains fluid.
 *    When the pinch settles, mpv renders the exact visible-detail resolution; at maximum zoom
 *    the request reaches the source pixel resolution. Android performs only the final spatial
 *    transform and keeps finger movement independent from mpv's render loop.
 *  - Surface geometry and TextureView transforms are committed from
 *    onSurfaceTextureUpdated(). Android invokes that callback after updateTexImage() but before
 *    applying the TextureView matrix for the same draw, so an old frame is never shown with a
 *    new aspect transform.
 */
internal class VideoZoomGestures(
    private val target: BaseMPVView,
) {
    private val renderLimits = target.getRenderSurfaceLimits()
    private val textureMatrix = Matrix()
    private val textureMatrixValues = FloatArray(9)

    private var viewWidth = 0f
    private var viewHeight = 0f

    private var committedGeometry = VideoGeometry.UNKNOWN
    private var desiredGeometry = VideoGeometry.UNKNOWN
    private var expectedMenuGeometry: VideoGeometry? = null

    private var activeSurfaceLayout = SurfaceLayout.VIEWPORT
    private var requestedSurfaceLayout = SurfaceLayout.VIEWPORT

    private var visualCommitPending = false
    private var requiredCommitFrameSerial = 0L
    private var requiredCommitSurfaceGeneration = 0L
    private var hasRenderedFrame = false
    private var awaitingNewFileGeometry = false
    private var resetTransformOnNextCommit = false
    private var viewportFrameFitOverride: SurfaceFitTransform? = null
    private var warnedAboutSurfaceLimit = false

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

    // When a pinch returns close enough to normal size, finish it through the same delayed
    // reset path as double-tap. ScaleGestureDetector can still report in-progress from
    // onScaleEnd on some Android versions.
    private var pendingPinchDoubleTapReset = false
    private var maxDetailRequestedDuringGesture = false

    // Coalesce transform updates to vsync. This does not animate or smooth the image; it merely
    // avoids writing multiple matrices during one display frame.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var applyScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        applyScheduled = false
        clampTranslationToVideoContent()
        applyToView()
    }

    init {
        // Clear all legacy View-property transforms. Only TextureView.setTransform() owns video
        // geometry now, so the regular View hierarchy and touch coordinates remain stable.
        target.pivotX = 0f
        target.pivotY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.translationX = 0f
        target.translationY = 0f
        target.alpha = 1f
        target.setTransform(null)
    }

    private val scaleDetector = ScaleGestureDetector(
        target.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastTapTime = 0L
                pendingPinchDoubleTapReset = false
                maxDetailRequestedDuringGesture = false
                panActive = false
                canBeTap = false

                // Request the zoom surface before the first visible scale step. The current frame
                // remains transformable while mpv prepares the higher-resolution buffer.
                updateRenderSurfaceForCurrentState()
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
                if (!maxDetailRequestedDuringGesture &&
                    scale >= MAX_SCALE - MAX_SCALE_FULL_DETAIL_EPS
                ) {
                    maxDetailRequestedDuringGesture = true
                    updateRenderSurfaceForCurrentState()
                }
                scheduleApply()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pendingPinchDoubleTapReset || scale <= PINCH_DOUBLE_TAP_RESET_SCALE) {
                    pendingPinchDoubleTapReset = true
                    resetLikeDoubleTapAfterPinch()
                } else {
                    resetPanFilters(detector.focusX, detector.focusY, SystemClock.uptimeMillis())
                    updateRenderSurfaceForCurrentState()
                }
            }
        },
    )

    fun setMetrics(width: Float, height: Float) {
        val oldWidth = viewWidth
        val oldHeight = viewHeight
        val oldContent = if (oldWidth > 1f && oldHeight > 1f)
            contentRect(committedGeometry, oldWidth, oldHeight)
        else
            null
        val oldCenterInContent: Pair<Double, Double>? =
            if (scale > 1f + EPS && oldContent != null &&
                oldContent.w > 1f && oldContent.h > 1f
            ) {
                val normalizedX = (
                    ((oldWidth * 0.5f) - tx) / scale - oldContent.ox
                    ) / oldContent.w
                val normalizedY = (
                    ((oldHeight * 0.5f) - ty) / scale - oldContent.oy
                    ) / oldContent.h
                Pair(
                    normalizedX.coerceIn(0.0, 1.0),
                    normalizedY.coerceIn(0.0, 1.0),
                )
            } else {
                null
            }

        viewWidth = width
        viewHeight = height
        refreshMetricsFromTarget()

        val sizeChanged = oldWidth > 1f && oldHeight > 1f &&
            (abs(oldWidth - viewWidth) > 0.5f || abs(oldHeight - viewHeight) > 0.5f)

        if (sizeChanged && oldCenterInContent != null) {
            // Preserve the source point under the screen center across rotation/resize instead of
            // preserving an absolute pixel translation from the old orientation.
            val newContent = contentRect(committedGeometry, viewWidth, viewHeight)
            tx = viewWidth * 0.5 - scale *
                (newContent.ox + oldCenterInContent.first * newContent.w)
            ty = viewHeight * 0.5 - scale *
                (newContent.oy + oldCenterInContent.second * newContent.h)
        }

        if (sizeChanged && hasRenderedFrame && activeSurfaceLayout == SurfaceLayout.VIEWPORT) {
            // TextureView maps the old viewport buffer to the new View bounds immediately.
            // Compensate that implicit stretch until mpv supplies a frame rendered for the new
            // dimensions. This makes portrait/landscape changes geometrically continuous.
            viewportFrameFitOverride = viewportResizeFit(
                oldWidth = oldWidth,
                oldHeight = oldHeight,
                newWidth = viewWidth,
                newHeight = viewHeight,
                geometry = committedGeometry,
            )
        }

        if (isZoomed() || scaleDetector.isInProgress)
            clampTranslationToVideoContent()

        // START_FILE may be followed by a configuration change before FILE_LOADED. Keep the old
        // frame, backing surface and transform coherent until authoritative new-file geometry is
        // available; only compensate the TextureView's implicit viewport stretch above.
        if (awaitingNewFileGeometry) {
            scheduleApply()
            return
        }

        val generation = updateRenderSurfaceForCurrentState()
        if (sizeChanged || !hasRenderedFrame)
            requestVisualCommit(generation)
        scheduleApply()
    }

    fun setVideoGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
    ) {
        if (awaitingNewFileGeometry)
            return
        setVideoGeometryInternal(aspect, pixelSize, panscanValue, forceCommit = false)
    }

    fun setNewFileGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
    ) {
        awaitingNewFileGeometry = false
        setVideoGeometryInternal(aspect, pixelSize, panscanValue, forceCommit = true)
    }

    private fun setVideoGeometryInternal(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
        forceCommit: Boolean,
    ) {
        val geometry = VideoGeometry(
            aspect = aspect?.takeIf { it > 0.001 } ?: 0.0,
            pixelWidth = pixelSize?.first?.coerceAtLeast(0) ?: 0,
            pixelHeight = pixelSize?.second?.coerceAtLeast(0) ?: 0,
            panscan = panscanValue ?: 0.0,
        )

        val expected = expectedMenuGeometry
        if (expected != null) {
            // Ignore the intermediate property notifications generated by setting aspect and
            // panscan separately. Do not resize the producer until mpv reports the complete final
            // pair; otherwise a frame for the first property could enter the new surface.
            if (!geometry.matches(expected))
                return
            expectedMenuGeometry = null
            desiredGeometry = geometry
            requestVisualCommit(updateRenderSurfaceForCurrentState())
            return
        }

        if (!forceCommit && geometry.matches(desiredGeometry))
            return

        desiredGeometry = geometry
        requestVisualCommit(updateRenderSurfaceForCurrentState())
    }

    /**
     * Stage a complete aspect-menu state without exposing it over the current frame. mpv still
     * owns the actual aspect calculation; this only suppresses the unavoidable intermediate
     * notifications from the two file-local property writes.
     */
    fun expectAspectMenuGeometry(
        aspect: Double?,
        pixelSize: Pair<Int, Int>?,
        panscanValue: Double?,
    ) {
        val geometry = VideoGeometry(
            aspect = aspect?.takeIf { it > 0.001 } ?: 0.0,
            pixelWidth = pixelSize?.first?.coerceAtLeast(0) ?: 0,
            pixelHeight = pixelSize?.second?.coerceAtLeast(0) ?: 0,
            panscan = panscanValue ?: 0.0,
        )
        expectedMenuGeometry = geometry
        desiredGeometry = geometry
        // Keep the current frame, surface and transform untouched until both mpv properties
        // resolve to this final pair. setVideoGeometryInternal() starts the frame transaction.
    }

    /** Called by BaseMPVView from TextureView.onSurfaceTextureUpdated(). */
    fun onSurfaceFrameAvailable(info: BaseMPVView.SurfaceFrameInfo) {
        hasRenderedFrame = true
        if (!visualCommitPending || expectedMenuGeometry != null)
            return
        if (info.serial < requiredCommitFrameSerial ||
            info.renderSurfaceGeneration < requiredCommitSurfaceGeneration
        ) return

        committedGeometry = desiredGeometry
        activeSurfaceLayout = requestedSurfaceLayout
        viewportFrameFitOverride = null
        visualCommitPending = false

        if (resetTransformOnNextCommit) {
            scale = 1f
            tx = 0.0
            ty = 0.0
            resetTransformOnNextCommit = false
        }
        clampTranslationToVideoContent()
        // This callback occurs before TextureView applies its matrix for the current draw.
        applyToView()
    }

    fun isZoomed(): Boolean = scale > 1f + EPS

    fun shouldBlockOtherGestures(e: MotionEvent): Boolean {
        return isZoomed() || pendingPinchDoubleTapReset || scaleDetector.isInProgress || e.pointerCount > 1
    }

    fun reset() {
        resetTransformState()
        requestVisualCommit(updateRenderSurfaceForCurrentState())
        applyToView()
    }

    fun resetForNewFile() {
        // Stop the old gesture, but retain its visual transform and backing surface. FILE_LOADED
        // will provide authoritative geometry and the next frame will atomically reset zoom,
        // surface layout and aspect together. This avoids exposing an old frame through a
        // new-file transform for a fraction of a second.
        cancelGestureStatePreservingTransform()
        desiredGeometry = VideoGeometry.UNKNOWN
        expectedMenuGeometry = null
        awaitingNewFileGeometry = true
        resetTransformOnNextCommit = true
        viewportFrameFitOverride = null
        visualCommitPending = false
        requiredCommitFrameSerial = 0L
        requiredCommitSurfaceGeneration = 0L
    }

    fun prepareForWindowExit() {
        reset()
    }

    private fun resetTransformState() {
        cancelGestureStatePreservingTransform()
        scale = 1f
        tx = 0.0
        ty = 0.0
        resetTransformOnNextCommit = false
        target.alpha = 1f
    }

    private fun cancelGestureStatePreservingTransform() {
        if (applyScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            applyScheduled = false
        }

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

        // Pointer transitions during pinch: if one finger lifts and another remains down,
        // rebase pan input so there is no jump.
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

    /** Compute the visible texture rect within the view at base scale. */
    private fun contentRect(
        geometry: VideoGeometry = committedGeometry,
        width: Float = viewWidth,
        height: Float = viewHeight,
    ): ContentRect {
        val w = width
        val h = height
        if (w <= 1f || h <= 1f)
            return ContentRect(0f, 0f, w, h)

        // In viewport mode mpv has already cropped panscan content into the SurfaceTexture, so
        // Android sees the complete View as its movable texture. The uncropped rect is still used
        // separately for source-detail calculations below.
        if (geometry.isPanscanActive())
            return ContentRect(0f, 0f, w, h)

        return unclippedVideoRect(geometry, w, h)
    }

    /**
     * mpv's displayed video rectangle before clipping to the output surface. For panscan this
     * grows linearly from contain to cover, matching mpv's aspect_calc_panscan calculation.
     */
    private fun unclippedVideoRect(
        geometry: VideoGeometry,
        width: Float = viewWidth,
        height: Float = viewHeight,
    ): ContentRect {
        val w = width
        val h = height
        if (w <= 1f || h <= 1f)
            return ContentRect(0f, 0f, w, h)

        val ar = if (geometry.aspect > 0.001) geometry.aspect.toFloat() else (w / h)
        val viewAr = w / h

        val containWidth: Float
        val containHeight: Float
        if (ar > viewAr) {
            containWidth = w
            containHeight = w / ar
        } else {
            containHeight = h
            containWidth = h * ar
        }

        val panscan = geometry.panscan.toFloat().coerceIn(0f, 1f)
        if (panscan <= EPS) {
            return ContentRect(
                ox = (w - containWidth) * 0.5f,
                oy = (h - containHeight) * 0.5f,
                w = containWidth,
                h = containHeight,
            )
        }

        val coverWidth: Float
        val coverHeight: Float
        if (ar > viewAr) {
            coverHeight = h
            coverWidth = h * ar
        } else {
            coverWidth = w
            coverHeight = w / ar
        }

        val videoWidth = containWidth + (coverWidth - containWidth) * panscan
        val videoHeight = containHeight + (coverHeight - containHeight) * panscan
        return ContentRect(
            ox = (w - videoWidth) * 0.5f,
            oy = (h - videoHeight) * 0.5f,
            w = videoWidth,
            h = videoHeight,
        )
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
        val fit = viewportFrameFitOverride
            ?: surfaceFitTransform(activeSurfaceLayout, committedGeometry)

        textureMatrixValues[Matrix.MSCALE_X] = scale * fit.scaleX
        textureMatrixValues[Matrix.MSKEW_X] = 0f
        textureMatrixValues[Matrix.MTRANS_X] = (tx + scale * fit.translationX).toFloat()
        textureMatrixValues[Matrix.MSKEW_Y] = 0f
        textureMatrixValues[Matrix.MSCALE_Y] = scale * fit.scaleY
        textureMatrixValues[Matrix.MTRANS_Y] = (ty + scale * fit.translationY).toFloat()
        textureMatrixValues[Matrix.MPERSP_0] = 0f
        textureMatrixValues[Matrix.MPERSP_1] = 0f
        textureMatrixValues[Matrix.MPERSP_2] = 1f

        textureMatrix.setValues(textureMatrixValues)
        target.setTransform(textureMatrix)
    }


    private fun viewportResizeFit(
        oldWidth: Float,
        oldHeight: Float,
        newWidth: Float,
        newHeight: Float,
        geometry: VideoGeometry,
    ): SurfaceFitTransform {
        if (oldWidth <= 1f || oldHeight <= 1f || newWidth <= 1f || newHeight <= 1f)
            return SurfaceFitTransform.IDENTITY

        val oldContent = contentRect(geometry, oldWidth, oldHeight)
        val stretchedOldContent = ContentRect(
            ox = oldContent.ox / oldWidth * newWidth,
            oy = oldContent.oy / oldHeight * newHeight,
            w = oldContent.w / oldWidth * newWidth,
            h = oldContent.h / oldHeight * newHeight,
        )
        val newContent = contentRect(geometry, newWidth, newHeight)
        if (stretchedOldContent.w <= 1f || stretchedOldContent.h <= 1f)
            return SurfaceFitTransform.IDENTITY

        val scaleX = newContent.w / stretchedOldContent.w
        val scaleY = newContent.h / stretchedOldContent.h
        return SurfaceFitTransform(
            scaleX = scaleX,
            scaleY = scaleY,
            translationX = (newContent.ox - scaleX * stretchedOldContent.ox).toDouble(),
            translationY = (newContent.oy - scaleY * stretchedOldContent.oy).toDouble(),
        )
    }

    private fun surfaceFitTransform(
        layout: SurfaceLayout,
        geometry: VideoGeometry,
    ): SurfaceFitTransform {
        if (layout != SurfaceLayout.MEDIA || viewWidth <= 1f || viewHeight <= 1f)
            return SurfaceFitTransform.IDENTITY

        val c = contentRect(geometry)
        if (c.w <= 1f || c.h <= 1f)
            return SurfaceFitTransform.IDENTITY

        return SurfaceFitTransform(
            scaleX = c.w / viewWidth,
            scaleY = c.h / viewHeight,
            translationX = c.ox.toDouble(),
            translationY = c.oy.toDouble(),
        )
    }

    /**
     * Request the surface that mpv should render next.
     *
     * @return the SurfaceTexture generation that must be active before a pending visual state
     * can be committed.
     */
    private fun updateRenderSurfaceForCurrentState(): Long {
        refreshMetricsFromTarget()
        if (viewWidth <= 1f || viewHeight <= 1f)
            return 0L

        val zooming = !resetTransformOnNextCommit &&
            (isZoomed() || scaleDetector.isInProgress)
        val geometry = desiredGeometry

        val layout = if (zooming && !geometry.isPanscanActive() && geometry.aspect > 0.001)
            SurfaceLayout.MEDIA
        else
            SurfaceLayout.VIEWPORT

        val generation: Long
        if (!zooming) {
            requestedSurfaceLayout = SurfaceLayout.VIEWPORT
            generation = target.resetRenderSurfaceSize()
        } else {
            val base = if (layout == SurfaceLayout.MEDIA)
                contentRect(geometry)
            else
                ContentRect(0f, 0f, viewWidth, viewHeight)

            if (base.w <= 1f || base.h <= 1f) {
                requestedSurfaceLayout = SurfaceLayout.VIEWPORT
                generation = target.resetRenderSurfaceSize()
            } else {
                val fullDetailScale = originalDetailScale(base, geometry)
                val targetScale = targetQualityScale(fullDetailScale)
                val limitedScale = limitQualityScale(targetScale, base)

                val bufferWidth = ceil(base.w.toDouble() * limitedScale)
                    .toInt()
                    .coerceIn(1, renderLimits.maxWidth)
                val bufferHeight = ceil(base.h.toDouble() * limitedScale)
                    .toInt()
                    .coerceIn(1, renderLimits.maxHeight)

                requestedSurfaceLayout = layout
                generation = target.setRenderSurfaceSize(bufferWidth, bufferHeight)
            }
        }

        if (requestedSurfaceLayout != activeSurfaceLayout)
            requestVisualCommit(generation)

        return generation
    }

    private fun requestVisualCommit(surfaceGeneration: Long) {
        visualCommitPending = true
        requiredCommitFrameSerial = max(
            requiredCommitFrameSerial,
            target.getSurfaceFrameSerial() + 1L,
        )
        requiredCommitSurfaceGeneration = max(
            requiredCommitSurfaceGeneration,
            surfaceGeneration,
        )

        // Before any frame exists there is no old picture to protect. We still leave the commit
        // pending so the first real frame receives the final transform inside its draw callback.
        if (!hasRenderedFrame)
            requiredCommitFrameSerial = 1L
    }

    private fun originalDetailScale(base: ContentRect, geometry: VideoGeometry): Double {
        if (geometry.pixelWidth <= 1 || geometry.pixelHeight <= 1 || base.w <= 1f || base.h <= 1f)
            return 1.0

        // A panscan Surface remains viewport-shaped so mpv can own cropping and OSD placement.
        // Its visible source, however, is scaled according to the larger pre-clipping video rect.
        // Using that rect avoids allocating a gigantic viewport buffer while still reaching one
        // rendered pixel per source pixel in the visible crop at maximum zoom.
        val detailBase = if (geometry.isPanscanActive())
            unclippedVideoRect(geometry)
        else
            base
        val scaleX = geometry.pixelWidth.toDouble() / detailBase.w.toDouble().coerceAtLeast(1.0)
        val scaleY = geometry.pixelHeight.toDouble() / detailBase.h.toDouble().coerceAtLeast(1.0)
        return max(scaleX, scaleY).coerceAtLeast(1.0)
    }

    private fun targetQualityScale(fullDetailScale: Double): Double {
        if (fullDetailScale <= 1.0)
            return 1.0

        // At maximum zoom request the complete source-detail buffer even when the source needs a
        // little more than 20x to reach one source pixel per display pixel.
        if (scale >= MAX_SCALE - MAX_SCALE_FULL_DETAIL_EPS)
            return fullDetailScale

        return min(fullDetailScale, scale.toDouble().coerceAtLeast(1.0))
    }

    private fun limitQualityScale(requested: Double, base: ContentRect): Double {
        val maxByWidth = renderLimits.maxWidth.toDouble() / base.w.toDouble().coerceAtLeast(1.0)
        val maxByHeight = renderLimits.maxHeight.toDouble() / base.h.toDouble().coerceAtLeast(1.0)
        val limited = requested.coerceAtMost(min(maxByWidth, maxByHeight)).coerceAtLeast(1.0)

        if (!warnedAboutSurfaceLimit && limited + 0.001 < requested) {
            warnedAboutSurfaceLimit = true
            Log.w(
                TAG,
                "Source-detail surface limited by GLES capability " +
                    "${renderLimits.maxWidth}x${renderLimits.maxHeight}",
            )
        }
        return limited
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

    private data class VideoGeometry(
        val aspect: Double,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val panscan: Double,
    ) {
        fun isPanscanActive(): Boolean = panscan > EPS.toDouble()

        fun matches(other: VideoGeometry): Boolean {
            return abs(aspect - other.aspect) < GEOMETRY_EPS &&
                pixelWidth == other.pixelWidth &&
                pixelHeight == other.pixelHeight &&
                abs(panscan - other.panscan) < GEOMETRY_EPS
        }

        companion object {
            val UNKNOWN = VideoGeometry(0.0, 0, 0, 0.0)
        }
    }

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

    private enum class SurfaceLayout {
        VIEWPORT,
        MEDIA,
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
        private const val TAG = "mpv-zoom"
        private const val EPS = 0.001f
        private const val GEOMETRY_EPS = 0.0005
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 20f
        private const val MAX_SCALE_FULL_DETAIL_EPS = 0.02f
        private const val PINCH_DOUBLE_TAP_RESET_SCALE = 1.001f
        private const val DOUBLE_TAP_TIMEOUT = 300L

        private const val DEFAULT_FRAME_DT = 1f / 60f
        private const val MIN_FILTER_DT = 1f / 240f
        private const val MAX_FILTER_DT = 1f / 30f

        // Filtering is deliberately disabled at normal zoom. It only appears when finger sensor
        // noise becomes visible because the image is deeply magnified.
        private const val FILTER_START_SCALE = 10f
        private const val FILTER_MIN_CUTOFF_AT_START = 12f
        private const val FILTER_MIN_CUTOFF_AT_MAX = 6f
        private const val FILTER_BETA_AT_START = 0.020f
        private const val FILTER_BETA_AT_MAX = 0.050f
        private const val FILTER_D_CUTOFF = 1.0f
    }
}
