package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

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

        // Keep every mpv OSD/script overlay in real display pixels instead of
        // letting a tall/narrow render target scale text only from its height.
        // The configured osd-scale is preserved and multiplied by an adaptive
        // factor whenever the display/video geometry changes.
        configuredOsdScale = (MPVLib.getPropertyDouble("osd-scale") ?: 1.0)
            .coerceAtLeast(MIN_OSD_SCALE)
        MPVLib.setPropertyBoolean("osd-scale-by-window", false)
        mpvInitialized = true
        updateAdaptiveOsdScale(width.toFloat(), height.toFloat(), null)

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

        mpvInitialized = false
        MPVLib.destroy()
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
    private var configuredOsdScale = 1.0
    private var appliedOsdScale = Double.NaN

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

    /**
     * Size mpv's text overlays from the actual display target, not from a
     * media-aspect SurfaceTexture. Opposite phone/video orientations reduce the
     * scale further so wide diagnostics (for example stats.lua) stay readable.
     */
    fun updateAdaptiveOsdScale(viewWidth: Float, viewHeight: Float, videoAspect: Double?) {
        if (!mpvInitialized || viewWidth <= 1f || viewHeight <= 1f)
            return

        val width = viewWidth.toDouble()
        val height = viewHeight.toDouble()

        // 1280x720 is the neutral OSD design area. Using both dimensions avoids
        // oversized text on tall portrait displays where height-only scaling is
        // much too aggressive.
        val displayFactor = min(width / OSD_REFERENCE_WIDTH, height / OSD_REFERENCE_HEIGHT)

        var contentFactor = 1.0
        val aspect = videoAspect ?: 0.0
        if (aspect > 0.001) {
            val viewAspect = width / height
            val contentWidth: Double
            val contentHeight: Double
            if (aspect > viewAspect) {
                contentWidth = width
                contentHeight = width / aspect
            } else {
                contentHeight = height
                contentWidth = height * aspect
            }

            val occupiedFraction = min(
                (contentWidth / width).coerceIn(0.0, 1.0),
                (contentHeight / height).coerceIn(0.0, 1.0),
            )
            contentFactor = sqrt(occupiedFraction).coerceIn(MIN_CONTENT_OSD_FACTOR, 1.0)
        }

        val adaptiveFactor = (displayFactor * contentFactor)
            .coerceIn(MIN_ADAPTIVE_OSD_FACTOR, MAX_ADAPTIVE_OSD_FACTOR)
        val requestedScale = configuredOsdScale * adaptiveFactor

        if (!appliedOsdScale.isNaN() && abs(appliedOsdScale - requestedScale) < 0.001)
            return

        appliedOsdScale = requestedScale
        MPVLib.setPropertyDouble("osd-scale", requestedScale)
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

        private const val OSD_REFERENCE_WIDTH = 1280.0
        private const val OSD_REFERENCE_HEIGHT = 720.0
        private const val MIN_OSD_SCALE = 0.01
        private const val MIN_CONTENT_OSD_FACTOR = 0.55
        private const val MIN_ADAPTIVE_OSD_FACTOR = 0.65
        private const val MAX_ADAPTIVE_OSD_FACTOR = 1.50
    }
}
