package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    init {
        // TextureView is part of the normal View hierarchy. This keeps zoom/pan smooth.
        // Quality is handled separately below by increasing the SurfaceTexture buffer size
        // while zoomed, so Android scales a high-resolution mpv render instead of a
        // screen-resolution screenshot-like texture.
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
    private var surfaceViewWidth = 0
    private var surfaceViewHeight = 0
    private var renderBufferWidth = 0
    private var renderBufferHeight = 0
    private var zoomRenderScale = 1f

    /**
     * Called by VideoZoomGestures. Zoom/pan remains a TextureView transform for smoothness,
     * but mpv renders into a larger buffer while zoomed so high-resolution videos/images keep
     * their source detail when Android magnifies the TextureView.
     */
    fun setZoomRenderScale(scale: Float) {
        val normalized = if (scale <= 1f + ZOOM_RENDER_EPS) 1f else scale.coerceAtMost(MAX_ZOOM_SCALE_HINT)
        if (sameRenderScaleBucket(zoomRenderScale, normalized))
            return

        zoomRenderScale = normalized
        updateRenderBufferSize(force = false)
    }

    fun notifyVideoSizeChanged() {
        updateRenderBufferSize(force = false)
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        Log.w(TAG, "attaching texture surface")
        attachedTexture = texture
        surfaceViewWidth = width
        surfaceViewHeight = height
        chooseAndApplyRenderBuffer(texture, force = true)

        val surface = Surface(texture)
        attachedSurface = surface

        MPVLib.attachSurface(surface)
        setMpvSurfaceSize()
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
        renderBufferWidth = 0
        renderBufferHeight = 0
        zoomRenderScale = 1f
    }

    private fun updateRenderBufferSize(force: Boolean) {
        val texture = attachedTexture ?: return
        if (surfaceViewWidth <= 1 || surfaceViewHeight <= 1)
            return

        val changed = chooseAndApplyRenderBuffer(texture, force)
        if (changed)
            setMpvSurfaceSize()
    }

    private fun chooseAndApplyRenderBuffer(texture: SurfaceTexture, force: Boolean): Boolean {
        val size = chooseRenderBufferSize()
        if (!force && size.width == renderBufferWidth && size.height == renderBufferHeight)
            return false

        renderBufferWidth = size.width
        renderBufferHeight = size.height
        texture.setDefaultBufferSize(renderBufferWidth, renderBufferHeight)
        Log.w(TAG, "mpv texture buffer: ${renderBufferWidth}x${renderBufferHeight}, zoom=$zoomRenderScale")
        return true
    }

    private fun setMpvSurfaceSize() {
        if (renderBufferWidth > 1 && renderBufferHeight > 1)
            MPVLib.setPropertyString("android-surface-size", "${renderBufferWidth}x${renderBufferHeight}")
    }

    private fun chooseRenderBufferSize(): RenderSize {
        val viewW = surfaceViewWidth.coerceAtLeast(1)
        val viewH = surfaceViewHeight.coerceAtLeast(1)

        if (zoomRenderScale <= 1f + ZOOM_RENDER_EPS)
            return RenderSize(viewW, viewH)

        val videoSize = currentVideoSizeForDisplay()
        val videoW = videoSize?.width ?: 0
        val videoH = videoSize?.height ?: 0

        // The useful oversampling is limited by the source itself and by the size of
        // the fitted video rect, not by the full TextureView when letterboxing exists.
        val sourceScale = if (videoW > 0 && videoH > 0) {
            val videoAr = videoW.toFloat() / videoH.toFloat()
            val viewAr = viewW.toFloat() / viewH.toFloat()
            val contentW: Float
            val contentH: Float
            if (videoAr > viewAr) {
                contentW = viewW.toFloat()
                contentH = viewW.toFloat() / videoAr
            } else {
                contentH = viewH.toFloat()
                contentW = viewH.toFloat() * videoAr
            }
            min(videoW.toFloat() / contentW, videoH.toFloat() / contentH).coerceAtLeast(1f)
        } else {
            DEFAULT_ZOOM_RENDER_SCALE
        }

        val desiredScale = min(sourceScale, MAX_RENDER_SCALE).coerceAtLeast(1f)

        var outW = ceil(viewW * desiredScale).roundToInt().coerceAtLeast(viewW)
        var outH = ceil(viewH * desiredScale).roundToInt().coerceAtLeast(viewH)

        // Keep the buffer safe for older devices. This still preserves UHD detail on 1080p/1440p
        // screens, and avoids catastrophic memory/GPU pressure with 8K sources.
        val largest = max(outW, outH)
        if (largest > MAX_RENDER_DIMENSION) {
            val down = MAX_RENDER_DIMENSION.toFloat() / largest.toFloat()
            outW = max(viewW, (outW * down).roundToInt())
            outH = max(viewH, (outH * down).roundToInt())
        }

        return RenderSize(outW, outH)
    }

    private fun currentVideoSizeForDisplay(): RenderSize? {
        val w = MPVLib.getPropertyInt("video-params/w") ?: MPVLib.getPropertyInt("video-out-params/w") ?: return null
        val h = MPVLib.getPropertyInt("video-params/h") ?: MPVLib.getPropertyInt("video-out-params/h") ?: return null
        if (w <= 0 || h <= 0)
            return null

        val rotate = MPVLib.getPropertyInt("video-params/rotate") ?: MPVLib.getPropertyInt("video-out-params/rotate") ?: 0
        return if (rotate % 180 == 90)
            RenderSize(h, w)
        else
            RenderSize(w, h)
    }

    private fun sameRenderScaleBucket(a: Float, b: Float): Boolean {
        // The buffer has only two behavioral modes: normal and high-quality zoom.
        // Avoid reallocating on every pinch delta; one upgrade on zoom enter and one
        // downgrade on reset keeps gesture smooth.
        return (a <= 1f + ZOOM_RENDER_EPS) == (b <= 1f + ZOOM_RENDER_EPS)
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceViewWidth = width
        surfaceViewHeight = height
        updateRenderBufferSize(force = true)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private data class RenderSize(val width: Int, val height: Int)

    companion object {
        private const val TAG = "mpv"
        private const val ZOOM_RENDER_EPS = 0.01f
        private const val MAX_ZOOM_SCALE_HINT = 20f
        private const val DEFAULT_ZOOM_RENDER_SCALE = 2f
        private const val MAX_RENDER_SCALE = 4f
        private const val MAX_RENDER_DIMENSION = 4096
    }
}
