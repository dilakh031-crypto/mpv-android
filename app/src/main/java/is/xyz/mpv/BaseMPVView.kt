package `is`.xyz.mpv

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    init {
        // TextureView is part of the normal View hierarchy. This makes high-zoom
        // scale/translation much smoother than transforming a SurfaceView layer,
        // especially on older Android devices where SurfaceView composition is
        // quantized by SurfaceFlinger/HWC.
        isOpaque = true
    }

    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        MPVLib.create(context)

        /* set normal options (user-supplied config can override) */
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            MPVLib.setOptionString(opt, cacheDir)
        initOptions()

        MPVLib.init()

        /* set hardcoded options */
        postInitOptions()
        captureBaseOsdMargins()
        mpvInitialized = true
        // could mess up VO init before surfaceCreated() is called
        MPVLib.setOptionString("force-window", "no")
        // need to idle at least once for playFile() logic to work
        MPVLib.setOptionString("idle", "once")

        surfaceTextureListener = this
        if (isAvailable) {
            surfaceTexture?.let { attachSurfaceTexture(it, width, height) }
        }
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable texture callbacks to avoid using uninitialized mpv state.
        surfaceTextureListener = null
        detachSurfaceTexture()

        MPVLib.destroy()
        mpvInitialized = false
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()

    protected abstract fun observeProperties()

    private var filePath: String? = null

    /**
     * Set the first file to be played once the player is ready.
     */
    fun playFile(filePath: String) {
        if (attachedSurface != null) {
            MPVLib.command(arrayOf("loadfile", filePath))
            this.filePath = null
        } else {
            this.filePath = filePath
        }
    }

    private var voInUse: String = "gpu"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    private var attachedSurface: Surface? = null
    private var attachedTexture: SurfaceTexture? = null

    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var customRenderSurfaceSize = false

    private var mpvInitialized = false
    private var baseOsdMarginX = DEFAULT_OSD_MARGIN
    private var baseOsdMarginY = DEFAULT_OSD_MARGIN
    private var osdInsetXFraction = 0.0
    private var osdInsetYFraction = 0.0
    private var appliedOsdMarginX = Double.NaN
    private var appliedOsdMarginY = Double.NaN
    private var appliedVideoClipBounds: Rect? = null

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

    /**
     * Clip everything rendered by mpv (video, subtitles, OSD and script overlays)
     * to the video's local rectangle. The bounds are expressed in this View's
     * untransformed coordinates, so they continue to follow the image when the
     * TextureView is zoomed or panned.
     */
    fun setVideoClipBounds(left: Float, top: Float, right: Float, bottom: Float) {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0)
            return

        // Round toward the inside so even fractional aspect-fit edges cannot
        // expose a row or column of OSD pixels in the surrounding black bars.
        val clippedLeft = ceil(left.coerceIn(0f, viewWidth.toFloat()).toDouble()).toInt()
        val clippedTop = ceil(top.coerceIn(0f, viewHeight.toFloat()).toDouble()).toInt()
        val clippedRight = floor(right.coerceIn(0f, viewWidth.toFloat()).toDouble()).toInt()
        val clippedBottom = floor(bottom.coerceIn(0f, viewHeight.toFloat()).toDouble()).toInt()

        val bounds = if (
            clippedLeft <= 0 && clippedTop <= 0 &&
            clippedRight >= viewWidth && clippedBottom >= viewHeight
        ) {
            null
        } else if (clippedRight > clippedLeft && clippedBottom > clippedTop) {
            Rect(clippedLeft, clippedTop, clippedRight, clippedBottom)
        } else {
            // Transient/invalid geometry must never make the whole player disappear.
            null
        }

        if (bounds == appliedVideoClipBounds)
            return
        appliedVideoClipBounds = bounds?.let { Rect(it) }
        clipBounds = bounds
    }

    /**
     * Reserve the centered letterbox/pillarbox area in mpv's OSD layout.
     *
     * mpv's margins are measured in 720-high scaled pixels when
     * osd-scale-by-window is enabled, and in real surface pixels otherwise.
     * Storing fractions lets the same safe rectangle survive render-buffer
     * changes used by high-quality zoom.
     */
    fun setOsdContentInsets(horizontalFraction: Double, verticalFraction: Double) {
        val insetX = horizontalFraction.coerceIn(0.0, MAX_CENTERED_INSET_FRACTION)
        val insetY = verticalFraction.coerceIn(0.0, MAX_CENTERED_INSET_FRACTION)
        if (
            abs(insetX - osdInsetXFraction) < GEOMETRY_EPSILON &&
            abs(insetY - osdInsetYFraction) < GEOMETRY_EPSILON
        ) {
            return
        }

        osdInsetXFraction = insetX
        osdInsetYFraction = insetY
        applyOsdContentMargins()
    }

    /**
     * Set the real SurfaceTexture buffer size used by mpv without changing the
     * TextureView's on-screen size.
     *
     * This intentionally accepts the requested size as-is. The caller decides the
     * size, so high-resolution media can be rendered at its original resolution
     * instead of being reduced to the display resolution before Android zooms it.
     */
    fun setRenderSurfaceSize(width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        customRenderSurfaceSize = true

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight)
            return

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        applyRenderSurfaceSize()
    }

    fun resetRenderSurfaceSize() {
        customRenderSurfaceSize = false
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight)
            return

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        applyRenderSurfaceSize()
    }

    private fun ensureRenderSurfaceSize(width: Int, height: Int) {
        if (customRenderSurfaceSize)
            return

        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)
    }

    private fun applyRenderSurfaceSize() {
        val texture = attachedTexture ?: return
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
        applyOsdContentMargins()
    }

    private fun captureBaseOsdMargins() {
        baseOsdMarginX = readNumericProperty("osd-margin-x") ?: DEFAULT_OSD_MARGIN
        baseOsdMarginY = readNumericProperty("osd-margin-y") ?: DEFAULT_OSD_MARGIN
        appliedOsdMarginX = Double.NaN
        appliedOsdMarginY = Double.NaN
    }

    private fun readNumericProperty(name: String): Double? {
        return MPVLib.getPropertyInt(name)?.toDouble()
            ?: MPVLib.getPropertyString(name)?.toDoubleOrNull()
    }

    private fun applyOsdContentMargins() {
        if (!mpvInitialized || renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        val scaleByWindow = MPVLib.getPropertyBoolean("osd-scale-by-window") != false
        val pixelsToMarginUnits = if (scaleByWindow)
            OSD_REFERENCE_HEIGHT / renderSurfaceHeight.toDouble()
        else
            1.0

        val insetX = renderSurfaceWidth * osdInsetXFraction * pixelsToMarginUnits
        val insetY = renderSurfaceHeight * osdInsetYFraction * pixelsToMarginUnits
        val maxMarginX = ((renderSurfaceWidth / 2.0) - 1.0)
            .coerceAtLeast(0.0) * pixelsToMarginUnits
        val maxMarginY = ((renderSurfaceHeight / 2.0) - 1.0)
            .coerceAtLeast(0.0) * pixelsToMarginUnits
        val marginX = (baseOsdMarginX + insetX)
            .coerceIn(0.0, minOf(maxMarginX, MAX_OSD_MARGIN))
            .roundToInt()
        val marginY = (baseOsdMarginY + insetY)
            .coerceIn(0.0, minOf(maxMarginY, MAX_OSD_MARGIN))
            .roundToInt()

        val marginXValue = marginX.toDouble()
        val marginYValue = marginY.toDouble()
        if (appliedOsdMarginX.isNaN() || abs(appliedOsdMarginX - marginXValue) >= MARGIN_EPSILON) {
            MPVLib.setPropertyInt("osd-margin-x", marginX)
            appliedOsdMarginX = marginXValue
        }
        if (appliedOsdMarginY.isNaN() || abs(appliedOsdMarginY - marginYValue) >= MARGIN_EPSILON) {
            MPVLib.setPropertyInt("osd-margin-y", marginY)
            appliedOsdMarginY = marginYValue
        }
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        attachedTexture = texture
        ensureRenderSurfaceSize(width, height)
        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)

        Log.w(TAG, "attaching texture surface ${renderSurfaceWidth}x${renderSurfaceHeight}")
        val surface = Surface(texture)
        attachedSurface = surface

        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")
        applyOsdContentMargins()

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    private fun detachSurfaceTexture() {
        val surface = attachedSurface ?: return

        Log.w(TAG, "detaching texture surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachSurface()
        surface.release()
        attachedSurface = null
        attachedTexture = null
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        ensureRenderSurfaceSize(width, height)
        applyRenderSurfaceSize()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        onSurfaceTextureFrameAvailable?.invoke()
    }

    companion object {
        private const val TAG = "mpv"
        private const val DEFAULT_OSD_MARGIN = 16.0
        private const val OSD_REFERENCE_HEIGHT = 720.0
        private const val MAX_OSD_MARGIN = 8192.0
        private const val MAX_CENTERED_INSET_FRACTION = 0.5
        private const val GEOMETRY_EPSILON = 0.000001
        private const val MARGIN_EPSILON = 0.01
    }
}
