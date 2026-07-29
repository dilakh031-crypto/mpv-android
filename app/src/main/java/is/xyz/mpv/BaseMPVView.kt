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
        // The native compositor always publishes a full opaque window-sized frame.
        // Keeping the TextureView opaque avoids an unnecessary full-screen blend.
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

    private var voInUse: String = "libmpv"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(@Suppress("UNUSED_PARAMETER") vo: String) {
        // The render API owns the OpenGL context. mpv still performs all video
        // scaling/aspect/panscan work, but must expose it through the libmpv VO.
        voInUse = "libmpv"
        MPVLib.setOptionString("vo", voInUse)
    }

    private var attachedSurface: Surface? = null

    fun submitRenderState(
        renderWidth: Int,
        renderHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        scale: Float,
        translationX: Float,
        translationY: Float,
        fitScaleX: Float,
        fitScaleY: Float,
        fitTranslationX: Float,
        fitTranslationY: Float,
    ) {
        MPVLib.setRenderState(
            renderWidth,
            renderHeight,
            viewWidth,
            viewHeight,
            scale,
            translationX,
            translationY,
            fitScaleX,
            fitScaleY,
            fitTranslationX,
            fitTranslationY,
        )
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        texture.setDefaultBufferSize(safeWidth, safeHeight)

        Log.w(TAG, "attaching texture surface ${safeWidth}x${safeHeight}")
        val surface = Surface(texture)
        if (!MPVLib.attachSurface(surface, safeWidth, safeHeight)) {
            surface.release()
            Log.e(TAG, "could not initialize the libmpv render surface")
            return
        }
        attachedSurface = surface
        // Re-enable the render-API VO before force-window or loadfile can create
        // a new video output after a TextureView recreation.
        MPVLib.setPropertyString("vo", voInUse)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        }
    }

    private fun detachSurfaceTexture() {
        val surface = attachedSurface ?: return

        Log.w(TAG, "detaching texture surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // detachSurface synchronously stops the render thread and frees mpv's
        // render context before the Java Surface is released.
        MPVLib.detachSurface()
        surface.release()
        attachedSurface = null
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        surface.setDefaultBufferSize(safeWidth, safeHeight)
        MPVLib.resizeRenderSurface(safeWidth, safeHeight)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // The native renderer performs frame ownership and geometry transitions.
    }

    companion object {
        private const val TAG = "mpv"
    }
}
