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
        // TextureView remains in the normal View hierarchy for smooth zoom/pan.
        // The render buffer is upgraded separately to the source video size as soon
        // as mpv reports that size, so zooming does not have to resize the buffer.
        isOpaque = true
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
        // Do not resize SurfaceTexture here. The previous attempts crashed because
        // buffer allocation was coupled to the file loading path. The buffer is upgraded
        // only after mpv reports valid video-params and the surface is already attached.
        sourceBufferPrepared = false
        pendingSourceBufferUpdate = true
        lastSourceWidth = 0
        lastSourceHeight = 0

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
    private var lastSourceWidth = 0
    private var lastSourceHeight = 0
    private var sourceBufferPrepared = false
    private var pendingSourceBufferUpdate = false
    private val textureMatrix = Matrix()

    /**
     * Called by VideoZoomGestures. Zoom itself must not resize the SurfaceTexture; that
     * resize is what caused black-bar flicker while the video was playing. This method is
     * only a fallback in case the user zooms before the video-size callback has prepared
     * the source-resolution buffer.
     */
    fun setZoomRenderScale(scale: Float) {
        if (scale > 1f + ZOOM_RENDER_EPS && !sourceBufferPrepared)
            requestSourceRenderBuffer()
    }

    /** Called when mpv reports width/height/aspect/rotation changes. */
    fun notifyVideoSizeChanged() {
        requestSourceRenderBuffer()
    }

    private fun requestSourceRenderBuffer() {
        pendingSourceBufferUpdate = true
        post { applyPendingSourceRenderBuffer() }
    }

    private fun applyPendingSourceRenderBuffer() {
        if (!pendingSourceBufferUpdate)
            return

        val texture = attachedTexture ?: return
        if (surfaceViewWidth <= 1 || surfaceViewHeight <= 1)
            return

        val source = currentVideoSizeForDisplay() ?: return
        if (source.width <= 1 || source.height <= 1)
            return

        pendingSourceBufferUpdate = false

        // If the source did not change and the buffer is already prepared, do nothing.
        // This is the key point: no resize on zoom-in and no resize on zoom-out.
        if (sourceBufferPrepared && source.width == lastSourceWidth && source.height == lastSourceHeight)
            return

        lastSourceWidth = source.width
        lastSourceHeight = source.height
        sourceBufferPrepared = true
        applyRenderBuffer(texture, source.width, source.height)
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        Log.w(TAG, "attaching texture surface")
        attachedTexture = texture
        surfaceViewWidth = width
        surfaceViewHeight = height

        // Safe initial buffer. This avoids the crash path while a file is being opened.
        applyRenderBuffer(texture, width.coerceAtLeast(1), height.coerceAtLeast(1))

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

        // If video-params were delivered before the TextureView became available,
        // apply the source-resolution buffer now, after the surface is safely attached.
        if (pendingSourceBufferUpdate)
            post { applyPendingSourceRenderBuffer() }
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
        lastSourceWidth = 0
        lastSourceHeight = 0
        sourceBufferPrepared = false
        pendingSourceBufferUpdate = false
        textureMatrix.reset()
        setTransform(textureMatrix)
    }

    private fun applyRenderBuffer(texture: SurfaceTexture, width: Int, height: Int) {
        if (width == renderBufferWidth && height == renderBufferHeight) {
            updateTextureTransform()
            return
        }

        renderBufferWidth = width
        renderBufferHeight = height
        texture.setDefaultBufferSize(renderBufferWidth, renderBufferHeight)
        updateTextureTransform()
        setMpvSurfaceSize()
        Log.w(TAG, "mpv texture buffer: ${renderBufferWidth}x${renderBufferHeight}, sourcePrepared=$sourceBufferPrepared")
    }

    private fun updateTextureTransform() {
        textureMatrix.reset()

        if (surfaceViewWidth > 1 && surfaceViewHeight > 1 && renderBufferWidth > 1 && renderBufferHeight > 1) {
            val viewAr = surfaceViewWidth.toFloat() / surfaceViewHeight.toFloat()
            val bufferAr = renderBufferWidth.toFloat() / renderBufferHeight.toFloat()
            val cx = surfaceViewWidth * 0.5f
            val cy = surfaceViewHeight * 0.5f

            if (bufferAr > viewAr) {
                // Buffer/video is wider than the view: keep full width and letterbox vertically.
                textureMatrix.setScale(1f, viewAr / bufferAr, cx, cy)
            } else if (bufferAr < viewAr) {
                // Buffer/video is taller than the view: keep full height and pillarbox horizontally.
                textureMatrix.setScale(bufferAr / viewAr, 1f, cx, cy)
            }
        }

        setTransform(textureMatrix)
    }

    private fun setMpvSurfaceSize() {
        if (renderBufferWidth > 1 && renderBufferHeight > 1)
            MPVLib.setPropertyString("android-surface-size", "${renderBufferWidth}x${renderBufferHeight}")
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
        updateTextureTransform()
        if (!sourceBufferPrepared) {
            attachedTexture?.let { applyRenderBuffer(it, width.coerceAtLeast(1), height.coerceAtLeast(1)) }
        }
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
    }
}
