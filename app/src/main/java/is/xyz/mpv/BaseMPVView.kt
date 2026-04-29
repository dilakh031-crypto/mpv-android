package `is`.xyz.mpv

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    init {
        // TextureView is part of the normal View hierarchy. This makes high-zoom
        // scale/translation much smoother than transforming a SurfaceView layer.
        //
        // Important for original-resolution buffers:
        // When the SurfaceTexture buffer aspect ratio differs from the view aspect ratio,
        // we use a TextureView transform matrix to preserve aspect ratio. In that state the
        // texture does not cover the whole view; if TextureView is marked opaque, Android may
        // leave the uncovered bars undefined during live buffer-size changes, which appears as
        // strong black-edge flicker. Keep it non-opaque and paint the background black so the
        // bars are stable.
        isOpaque = false
        setBackgroundColor(Color.BLACK)
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
    private val textureMatrix = Matrix()

    /**
     * Called by VideoZoomGestures.
     *
     * Previous version switched between screen-sized buffer and original-resolution buffer
     * exactly when zoom crossed 1x. That is what caused the black bars to flicker while a
     * video was playing: SurfaceTexture buffer-size changes are not visually atomic.
     *
     * The buffer is now independent from the current zoom value. It is kept at the original
     * video resolution whenever that resolution is known, even before zoom starts. Therefore
     * zooming in/out only transforms the TextureView; it no longer reallocates the live video
     * buffer during the pinch gesture.
     */
    fun setZoomRenderScale(scale: Float) = Unit

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
        textureMatrix.reset()
        setTransform(textureMatrix)
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
        if (!force && size.width == renderBufferWidth && size.height == renderBufferHeight) {
            updateTextureTransform()
            return false
        }

        // Apply the aspect-preserving transform before and after the resize. This prevents a
        // transient full-stretch frame in the black bars on devices that redraw immediately when
        // SurfaceTexture's default buffer size changes.
        renderBufferWidth = size.width
        renderBufferHeight = size.height
        updateTextureTransform()
        texture.setDefaultBufferSize(renderBufferWidth, renderBufferHeight)
        updateTextureTransform()

        Log.w(TAG, "mpv texture buffer: ${renderBufferWidth}x${renderBufferHeight}")
        return true
    }

    private fun updateTextureTransform() {
        textureMatrix.reset()

        if (surfaceViewWidth > 1 && surfaceViewHeight > 1 && renderBufferWidth > 1 && renderBufferHeight > 1) {
            val viewAr = surfaceViewWidth.toFloat() / surfaceViewHeight.toFloat()
            val bufferAr = renderBufferWidth.toFloat() / renderBufferHeight.toFloat()
            val cx = surfaceViewWidth * 0.5f
            val cy = surfaceViewHeight * 0.5f

            if (bufferAr > viewAr) {
                // Original video is wider than the view: keep full width and letterbox vertically.
                textureMatrix.setScale(1f, viewAr / bufferAr, cx, cy)
            } else {
                // Original video is taller than the view: keep full height and pillarbox horizontally.
                textureMatrix.setScale(bufferAr / viewAr, 1f, cx, cy)
            }
        }

        setTransform(textureMatrix)
    }

    private fun setMpvSurfaceSize() {
        if (renderBufferWidth > 1 && renderBufferHeight > 1)
            MPVLib.setPropertyString("android-surface-size", "${renderBufferWidth}x${renderBufferHeight}")
    }

    private fun chooseRenderBufferSize(): RenderSize {
        val viewW = surfaceViewWidth.coerceAtLeast(1)
        val viewH = surfaceViewHeight.coerceAtLeast(1)

        // Always use the original decoded video resolution exactly when it is known.
        // No safety cap, no fitting to screen, no scale factor.
        // This avoids reallocating when zoom starts/ends, which fixes the flicker.
        return currentVideoSizeForDisplay() ?: RenderSize(viewW, viewH)
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

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceViewWidth = width
        surfaceViewHeight = height
        updateRenderBufferSize(force = true)
        updateTextureTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private data class RenderSize(val width: Int, val height: Int)

    companion object {
        private const val TAG = "mpv"
    }
}
