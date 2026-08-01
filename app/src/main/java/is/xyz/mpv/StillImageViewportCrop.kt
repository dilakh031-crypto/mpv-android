package `is`.xyz.mpv

import android.os.SystemClock
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * CPU-side viewport crop used only for still images larger than the viewport.
 *
 * mpv/FFmpeg still decode the full source image, but this filter crops and scales
 * the visible region before video output. The activation threshold is the actual
 * Android viewport size rather than a hard-coded GPU texture-size constant.
 */
internal object StillImageViewportMath {
    data class Input(
        val viewWidth: Int,
        val viewHeight: Int,
        val contentLeft: Double,
        val contentTop: Double,
        val contentWidth: Double,
        val contentHeight: Double,
        val logicalScale: Double,
        val translationX: Double,
        val translationY: Double,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val rotation: Int,
        val fastScaling: Boolean,
    )

    data class Affine(
        val scaleX: Double,
        val scaleY: Double,
        val offsetX: Double,
        val offsetY: Double,
    )

    data class Request(
        val cropX: Int,
        val cropY: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val scaledWidth: Int,
        val scaledHeight: Int,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val padX: Int,
        val padY: Int,
        val scaleFlags: String,
        val affine: Affine,
    ) {
        val graphKey: String = listOf(
            cropX, cropY, cropWidth, cropHeight,
            scaledWidth, scaledHeight, canvasWidth, canvasHeight,
            padX, padY, scaleFlags,
        ).joinToString(":")

        fun filterEntry(): String {
            val graph = buildString {
                append("crop=w=").append(cropWidth)
                append(":h=").append(cropHeight)
                append(":x=").append(cropX)
                append(":y=").append(cropY)
                append(":exact=1")
                append(",scale=w=").append(scaledWidth)
                append(":h=").append(scaledHeight)
                append(":flags=").append(scaleFlags)
                append(",pad=w=").append(canvasWidth)
                append(":h=").append(canvasHeight)
                append(":x=").append(padX)
                append(":y=").append(padY)
                append(":color=black")
                append(",setsar=1")
            }
            return "@$FILTER_LABEL:lavfi=[$graph]"
        }
    }

    data class ViewTransform(
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
    ) {
        companion object {
            val IDENTITY = ViewTransform(1f, 1f, 0f, 0f)
        }
    }

    fun sourceExceedsViewport(
        sourceWidth: Int,
        sourceHeight: Int,
        rotation: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean {
        if (sourceWidth <= 1 || sourceHeight <= 1 ||
            viewportWidth <= 1 || viewportHeight <= 1
        ) return false

        val normalizedRotation = normalizeRotation(rotation)
        if (normalizedRotation !in SUPPORTED_ROTATIONS)
            return false

        val displayWidth = if (normalizedRotation % 180 == 0) sourceWidth else sourceHeight
        val displayHeight = if (normalizedRotation % 180 == 0) sourceHeight else sourceWidth
        return displayWidth > viewportWidth || displayHeight > viewportHeight
    }

    fun compute(input: Input): Request? {
        val vw = input.viewWidth
        val vh = input.viewHeight
        if (vw <= 1 || vh <= 1 ||
            input.contentWidth <= 0.0 || input.contentHeight <= 0.0 ||
            input.logicalScale <= 0.0 ||
            input.sourceWidth <= 1 || input.sourceHeight <= 1
        ) return null

        val rotation = normalizeRotation(input.rotation)
        if (rotation !in SUPPORTED_ROTATIONS)
            return null

        val displayWidth = if (rotation % 180 == 0) input.sourceWidth else input.sourceHeight
        val displayHeight = if (rotation % 180 == 0) input.sourceHeight else input.sourceWidth

        val contentScreenLeft = input.logicalScale * input.contentLeft + input.translationX
        val contentScreenTop = input.logicalScale * input.contentTop + input.translationY
        val contentScreenWidth = input.logicalScale * input.contentWidth
        val contentScreenHeight = input.logicalScale * input.contentHeight
        val contentScreenRight = contentScreenLeft + contentScreenWidth
        val contentScreenBottom = contentScreenTop + contentScreenHeight

        val visibleLeft = max(0.0, contentScreenLeft)
        val visibleTop = max(0.0, contentScreenTop)
        val visibleRight = min(vw.toDouble(), contentScreenRight)
        val visibleBottom = min(vh.toDouble(), contentScreenBottom)
        if (visibleRight - visibleLeft < 0.5 || visibleBottom - visibleTop < 0.5)
            return null

        val displayLeft = floor(
            ((visibleLeft - contentScreenLeft) / contentScreenWidth) * displayWidth,
        ).toInt().coerceIn(0, displayWidth - 1)
        val displayTop = floor(
            ((visibleTop - contentScreenTop) / contentScreenHeight) * displayHeight,
        ).toInt().coerceIn(0, displayHeight - 1)
        val displayRight = ceil(
            ((visibleRight - contentScreenLeft) / contentScreenWidth) * displayWidth,
        ).toInt().coerceIn(displayLeft + 1, displayWidth)
        val displayBottom = ceil(
            ((visibleBottom - contentScreenTop) / contentScreenHeight) * displayHeight,
        ).toInt().coerceIn(displayTop + 1, displayHeight)

        val rawCrop = displayToRawCrop(
            displayLeft = displayLeft,
            displayTop = displayTop,
            displayRight = displayRight,
            displayBottom = displayBottom,
            sourceWidth = input.sourceWidth,
            sourceHeight = input.sourceHeight,
            rotation = rotation,
        )

        // Derive placement from the exact integer source crop so rounding cannot
        // create a one-pixel seam or expose uninitialized padding at an edge.
        val exactScreenLeft = contentScreenLeft +
            displayLeft.toDouble() / displayWidth * contentScreenWidth
        val exactScreenTop = contentScreenTop +
            displayTop.toDouble() / displayHeight * contentScreenHeight
        val exactScreenRight = contentScreenLeft +
            displayRight.toDouble() / displayWidth * contentScreenWidth
        val exactScreenBottom = contentScreenTop +
            displayBottom.toDouble() / displayHeight * contentScreenHeight

        val outputLeft = floor(max(0.0, exactScreenLeft)).toInt().coerceIn(0, vw - 1)
        val outputTop = floor(max(0.0, exactScreenTop)).toInt().coerceIn(0, vh - 1)
        val outputRight = ceil(min(vw.toDouble(), exactScreenRight))
            .toInt().coerceIn(outputLeft + 1, vw)
        val outputBottom = ceil(min(vh.toDouble(), exactScreenBottom))
            .toInt().coerceIn(outputTop + 1, vh)
        val outputWidth = outputRight - outputLeft
        val outputHeight = outputBottom - outputTop

        val preRotationPlacement = displayToPreRotationPlacement(
            displayLeft = outputLeft,
            displayTop = outputTop,
            displayWidth = outputWidth,
            displayHeight = outputHeight,
            canvasDisplayWidth = vw,
            canvasDisplayHeight = vh,
            rotation = rotation,
        )

        val downscaleX = rawCrop.width.toDouble() / preRotationPlacement.width
        val downscaleY = rawCrop.height.toDouble() / preRotationPlacement.height
        val flags = when {
            input.fastScaling -> "fast_bilinear"
            max(downscaleX, downscaleY) > 1.15 -> "area"
            else -> "lanczos"
        }

        return Request(
            cropX = rawCrop.x,
            cropY = rawCrop.y,
            cropWidth = rawCrop.width,
            cropHeight = rawCrop.height,
            scaledWidth = preRotationPlacement.width,
            scaledHeight = preRotationPlacement.height,
            canvasWidth = preRotationPlacement.canvasWidth,
            canvasHeight = preRotationPlacement.canvasHeight,
            padX = preRotationPlacement.x,
            padY = preRotationPlacement.y,
            scaleFlags = flags,
            affine = Affine(
                scaleX = contentScreenWidth / displayWidth,
                scaleY = contentScreenHeight / displayHeight,
                offsetX = contentScreenLeft,
                offsetY = contentScreenTop,
            ),
        )
    }

    fun predictiveTransform(displayed: Request, desired: Request): ViewTransform {
        val old = displayed.affine
        val next = desired.affine
        if (old.scaleX == 0.0 || old.scaleY == 0.0)
            return ViewTransform.IDENTITY

        val kx = next.scaleX / old.scaleX
        val ky = next.scaleY / old.scaleY
        return ViewTransform(
            scaleX = kx.toFloat(),
            scaleY = ky.toFloat(),
            translationX = (next.offsetX - kx * old.offsetX).toFloat(),
            translationY = (next.offsetY - ky * old.offsetY).toFloat(),
        )
    }

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
    private data class Placement(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val canvasWidth: Int,
        val canvasHeight: Int,
    )

    private fun displayToRawCrop(
        displayLeft: Int,
        displayTop: Int,
        displayRight: Int,
        displayBottom: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        rotation: Int,
    ): Rect = when (rotation) {
        0 -> Rect(
            displayLeft,
            displayTop,
            displayRight - displayLeft,
            displayBottom - displayTop,
        )
        90 -> Rect(
            displayTop,
            sourceHeight - displayRight,
            displayBottom - displayTop,
            displayRight - displayLeft,
        )
        180 -> Rect(
            sourceWidth - displayRight,
            sourceHeight - displayBottom,
            displayRight - displayLeft,
            displayBottom - displayTop,
        )
        270 -> Rect(
            sourceWidth - displayBottom,
            displayLeft,
            displayBottom - displayTop,
            displayRight - displayLeft,
        )
        else -> error("Unsupported rotation")
    }

    private fun displayToPreRotationPlacement(
        displayLeft: Int,
        displayTop: Int,
        displayWidth: Int,
        displayHeight: Int,
        canvasDisplayWidth: Int,
        canvasDisplayHeight: Int,
        rotation: Int,
    ): Placement = when (rotation) {
        0 -> Placement(
            displayLeft, displayTop, displayWidth, displayHeight,
            canvasDisplayWidth, canvasDisplayHeight,
        )
        90 -> Placement(
            displayTop,
            canvasDisplayWidth - (displayLeft + displayWidth),
            displayHeight,
            displayWidth,
            canvasDisplayHeight,
            canvasDisplayWidth,
        )
        180 -> Placement(
            canvasDisplayWidth - (displayLeft + displayWidth),
            canvasDisplayHeight - (displayTop + displayHeight),
            displayWidth,
            displayHeight,
            canvasDisplayWidth,
            canvasDisplayHeight,
        )
        270 -> Placement(
            canvasDisplayHeight - (displayTop + displayHeight),
            displayLeft,
            displayHeight,
            displayWidth,
            canvasDisplayHeight,
            canvasDisplayWidth,
        )
        else -> error("Unsupported rotation")
    }

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    const val FILTER_LABEL = "mpv_android_image_viewport"
    private val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)
}

/**
 * Serializes costly filter reconfigurations and keeps only the newest viewport.
 * The View transform predicts movement between 30 fps CPU crop updates, so touch
 * input remains tied to the finger while libavfilter prepares the next frame.
 */
internal class StillImageViewportCropController(
    private val target: View,
    private val onDisplayStateChanged: () -> Unit,
) {
    private sealed interface Action {
        data class Add(val request: StillImageViewportMath.Request) : Action
        data object Remove : Action
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-image-viewport-crop").apply { isDaemon = true }
    }

    private var desired: StillImageViewportMath.Request? = null
    private var displayed: StillImageViewportMath.Request? = null
    private var commandInFlight = false
    private var awaitingFrame: Action? = null
    private var pumpPosted = false
    private var generation = 0L
    private var lastCommandUptimeMs = Long.MIN_VALUE
    private var released = false
    private var disabledUntilNextFile = false

    val isAvailable: Boolean
        get() = !released && !disabledUntilNextFile

    val hasDisplayedCrop: Boolean
        get() = displayed != null

    val wantsOrDisplaysCrop: Boolean
        get() = desired != null || displayed != null || awaitingFrame is Action.Add

    fun setDesired(request: StillImageViewportMath.Request?) {
        if (released)
            return
        if (request != null && disabledUntilNextFile)
            return

        val shown = displayed
        // If integer crop/pad output is unchanged, keep the already-rendered
        // frame and let the predictive View transform carry sub-pixel movement.
        if (request != null && shown != null && request.graphKey == shown.graphKey) {
            desired = request
            return
        }

        desired = request
        pump()
    }

    fun currentPredictiveTransform(): StillImageViewportMath.ViewTransform {
        val shown = displayed ?: return StillImageViewportMath.ViewTransform.IDENTITY
        val next = desired ?: return StillImageViewportMath.ViewTransform.IDENTITY
        return StillImageViewportMath.predictiveTransform(shown, next)
    }

    fun onSurfaceTextureFrameAvailable() {
        val completed = awaitingFrame ?: return
        awaitingFrame = null
        when (completed) {
            is Action.Add -> displayed = completed.request
            Action.Remove -> displayed = null
        }
        onDisplayStateChanged()
        pump()
    }

    /** Remove the app-owned filter while the player is hidden or being destroyed. */
    fun removeBlocking() {
        if (released)
            return
        generation += 1L
        target.removeCallbacks(pumpRunnable)
        pumpPosted = false
        desired = null
        displayed = null
        awaitingFrame = null
        disabledUntilNextFile = false
        commandInFlight = false
        try {
            executor.submit { removeFilter() }.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            try { removeFilter() } catch (_: Throwable) {}
        }
    }

    fun release() {
        if (released)
            return
        removeBlocking()
        released = true
        executor.shutdownNow()
    }

    private fun pump() {
        if (released || commandInFlight || awaitingFrame != null)
            return

        val next = desired
        val shown = displayed
        if (next == null && shown == null)
            return
        if (next != null && shown != null && next.graphKey == shown.graphKey)
            return

        val now = SystemClock.uptimeMillis()
        val elapsed = if (lastCommandUptimeMs == Long.MIN_VALUE) Long.MAX_VALUE
            else now - lastCommandUptimeMs
        if (elapsed < MIN_COMMAND_INTERVAL_MS) {
            if (!pumpPosted) {
                pumpPosted = true
                target.postDelayed(pumpRunnable, MIN_COMMAND_INTERVAL_MS - elapsed)
            }
            return
        }

        val action: Action = if (next != null) Action.Add(next) else Action.Remove
        val requestGeneration = generation
        commandInFlight = true
        lastCommandUptimeMs = now
        executor.execute {
            val success = try {
                when (action) {
                    is Action.Add -> MPVLib.command(
                        arrayOf("no-osd", "vf", "add", action.request.filterEntry()),
                    )
                    Action.Remove -> removeFilter()
                }
                true
            } catch (_: Throwable) {
                false
            }

            target.post {
                if (released || requestGeneration != generation)
                    return@post
                commandInFlight = false
                if (!success) {
                    desired = null
                    displayed = null
                    awaitingFrame = null
                    disabledUntilNextFile = true
                    onDisplayStateChanged()
                    return@post
                }
                awaitingFrame = action
                // A filter replacement on a still image normally produces a new
                // SurfaceTexture frame. If a broken driver does not report it,
                // fail back to the old bounded-surface path rather than deadlock.
                target.postDelayed({ handleFrameTimeout(action, requestGeneration) }, FRAME_TIMEOUT_MS)
            }
        }
    }

    private fun handleFrameTimeout(action: Action, requestGeneration: Long) {
        if (released || requestGeneration != generation || awaitingFrame !== action)
            return

        generation += 1L
        awaitingFrame = null
        desired = null
        displayed = null
        commandInFlight = false
        disabledUntilNextFile = true
        executor.execute {
            try { removeFilter() } catch (_: Throwable) {}
        }
        onDisplayStateChanged()
    }

    private val pumpRunnable = Runnable {
        pumpPosted = false
        pump()
    }

    private fun removeFilter() {
        MPVLib.command(
            arrayOf("no-osd", "vf", "remove", "@${StillImageViewportMath.FILTER_LABEL}"),
        )
    }

    companion object {
        private const val MIN_COMMAND_INTERVAL_MS = 33L
        private const val FRAME_TIMEOUT_MS = 900L
    }
}
