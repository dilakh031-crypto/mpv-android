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
    private var attachedTexture: SurfaceTexture? = null

    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var renderSurfaceOverridden = false

    /**
     * Sets the real mpv/SurfaceTexture render-buffer size without changing the
     * TextureView's on-screen size.
     *
     * Normal zoom still uses Android's smooth TextureView transform, but mpv now
     * renders more source pixels into that texture while zoomed. This avoids the
     * low-detail "screen-resolution screenshot" look on 4K/8K media.
     */
    fun setRenderSurfaceSize(width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight)
            return

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        renderSurfaceOverridden = safeWidth != this.width || safeHeight != this.height
        applyRenderSurfaceSize()
    }

    fun resetRenderSurfaceSize() {
        renderSurfaceOverridden = false
        val w = if (width > 1) width else renderSurfaceWidth
        val h = if (height > 1) height else renderSurfaceHeight
        if (w > 1 && h > 1)
            setRenderSurfaceSize(w, h)
    }

    private fun desiredSurfaceWidth(fallbackWidth: Int): Int {
        return if (renderSurfaceWidth > 1) renderSurfaceWidth else fallbackWidth.coerceAtLeast(1)
    }

    private fun desiredSurfaceHeight(fallbackHeight: Int): Int {
        return if (renderSurfaceHeight > 1) renderSurfaceHeight else fallbackHeight.coerceAtLeast(1)
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
        renderSurfaceWidth = if (renderSurfaceOverridden) desiredSurfaceWidth(width) else width.coerceAtLeast(1)
        renderSurfaceHeight = if (renderSurfaceOverridden) desiredSurfaceHeight(height) else height.coerceAtLeast(1)
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
        if (!renderSurfaceOverridden) {
            renderSurfaceWidth = width.coerceAtLeast(1)
            renderSurfaceHeight = height.coerceAtLeast(1)
        }
        applyRenderSurfaceSize()
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
