package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

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
    private var attachedSurfaceTexture: SurfaceTexture? = null

    private var viewSurfaceWidth = 0
    private var viewSurfaceHeight = 0
    private var requestedRenderWidth: Int? = null
    private var requestedRenderHeight: Int? = null
    private var currentRenderWidth = 0
    private var currentRenderHeight = 0

    /**
     * Request the actual mpv/SurfaceTexture render size.
     *
     * Passing null falls back to the view size. Passing the decoded video size keeps
     * zoom sourced from the original media resolution instead of magnifying a
     * screen-sized render buffer.
     */
    fun setRenderSurfaceSize(width: Int?, height: Int?) {
        if (width != null && height != null && width > 0 && height > 0) {
            requestedRenderWidth = width
            requestedRenderHeight = height
        } else {
            requestedRenderWidth = null
            requestedRenderHeight = null
        }
        updateRenderSurfaceSize()
    }

    private fun updateRenderSurfaceSize() {
        val width = requestedRenderWidth ?: viewSurfaceWidth
        val height = requestedRenderHeight ?: viewSurfaceHeight
        if (width <= 0 || height <= 0)
            return
        if (width == currentRenderWidth && height == currentRenderHeight)
            return

        currentRenderWidth = width
        currentRenderHeight = height

        Log.v(TAG, "render surface size ${width}x${height}")
        attachedSurfaceTexture?.setDefaultBufferSize(width, height)
        if (attachedSurface != null)
            MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        Log.w(TAG, "attaching texture surface")
        attachedSurfaceTexture = texture
        viewSurfaceWidth = width
        viewSurfaceHeight = height
        updateRenderSurfaceSize()
        if (currentRenderWidth <= 0 || currentRenderHeight <= 0) {
            currentRenderWidth = width.coerceAtLeast(1)
            currentRenderHeight = height.coerceAtLeast(1)
            texture.setDefaultBufferSize(currentRenderWidth, currentRenderHeight)
        }

        val surface = Surface(texture)
        attachedSurface = surface

        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("android-surface-size", "${currentRenderWidth}x${currentRenderHeight}")
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
        attachedSurfaceTexture = null
        currentRenderWidth = 0
        currentRenderHeight = 0
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        viewSurfaceWidth = width
        viewSurfaceHeight = height
        updateRenderSurfaceSize()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    companion object {
        private const val TAG = "mpv"
    }
}
