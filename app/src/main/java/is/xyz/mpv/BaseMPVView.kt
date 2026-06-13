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
            surfaceTexture?.let {
                primaryTexture = it
                usePrimaryRenderSurface()
            }
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
        detachCurrentRenderSurface()
        primaryTexture = null

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
        if (currentSurface != null) {
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

    private enum class SurfaceOwner {
        NONE,
        PRIMARY,
        EXTERNAL,
    }

    private var currentSurface: Surface? = null
    private var currentTexture: SurfaceTexture? = null
    private var currentSurfaceOwner = SurfaceOwner.NONE
    private var primaryTexture: SurfaceTexture? = null

    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var customRenderSurfaceSize = false
    private var onPrimaryFrameRendered: (() -> Unit)? = null

    fun setOnPrimaryFrameRenderedListener(listener: (() -> Unit)?) {
        onPrimaryFrameRendered = listener
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

    /**
     * Move mpv back to the normal player TextureView. This is the path used at
     * base scale so mpv remains responsible for the final screen-sized downscale
     * and its configured scalers/filters keep working before zoom starts.
     */
    fun usePrimaryRenderSurface(): Boolean {
        val texture = primaryTexture ?: return false
        customRenderSurfaceSize = false
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        return attachRenderSurface(texture, safeWidth, safeHeight, SurfaceOwner.PRIMARY)
    }

    /**
     * Move mpv to an external TextureView owned by the zoom layer. The caller
     * supplies a media-aspect, original-detail buffer size. The primary TextureView
     * remains in the hierarchy with its last frame, so the user never sees a blank
     * frame while mpv renders the first zoom-layer frame.
     */
    fun useExternalRenderSurface(texture: SurfaceTexture, width: Int, height: Int): Boolean {
        customRenderSurfaceSize = true
        return attachRenderSurface(
            texture,
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            SurfaceOwner.EXTERNAL,
        )
    }

    fun detachExternalRenderSurface(texture: SurfaceTexture) {
        if (currentSurfaceOwner == SurfaceOwner.EXTERNAL && currentTexture === texture)
            detachCurrentRenderSurface()
    }

    private fun applyRenderSurfaceSize() {
        val texture = currentTexture ?: return
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
    }

    private fun attachRenderSurface(
        texture: SurfaceTexture,
        surfaceWidth: Int,
        surfaceHeight: Int,
        owner: SurfaceOwner,
    ): Boolean {
        val safeWidth = surfaceWidth.coerceAtLeast(1)
        val safeHeight = surfaceHeight.coerceAtLeast(1)

        if (currentTexture === texture && currentSurfaceOwner == owner) {
            renderSurfaceWidth = safeWidth
            renderSurfaceHeight = safeHeight
            applyRenderSurfaceSize()
            return true
        }

        texture.setDefaultBufferSize(safeWidth, safeHeight)
        val newSurface = Surface(texture)
        val oldSurface = currentSurface
        val hadSurface = oldSurface != null

        currentTexture = texture
        currentSurface = newSurface
        currentSurfaceOwner = owner
        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight

        Log.w(TAG, "attaching ${owner.name.lowercase()} texture surface ${renderSurfaceWidth}x${renderSurfaceHeight}")
        MPVLib.attachSurface(newSurface)
        oldSurface?.release()

        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else if (!hadSurface) {
            // We disable video output when the context disappears, enable it back.
            // During zoom we only switch mpv's wid between two already-live
            // TextureViews, so avoid forcing a VO reinit on every zoom transition.
            MPVLib.setPropertyString("vo", voInUse)
        }

        return true
    }

    private fun detachCurrentRenderSurface() {
        val surface = currentSurface ?: return

        Log.w(TAG, "detaching texture surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachSurface()
        surface.release()
        currentSurface = null
        currentTexture = null
        currentSurfaceOwner = SurfaceOwner.NONE
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        primaryTexture = surface
        usePrimaryRenderSurface()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (primaryTexture !== surface)
            primaryTexture = surface

        if (currentSurfaceOwner == SurfaceOwner.PRIMARY && currentTexture === surface) {
            customRenderSurfaceSize = false
            renderSurfaceWidth = width.coerceAtLeast(1)
            renderSurfaceHeight = height.coerceAtLeast(1)
            applyRenderSurfaceSize()
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (currentSurfaceOwner == SurfaceOwner.PRIMARY && currentTexture === surface)
            detachCurrentRenderSurface()
        if (primaryTexture === surface)
            primaryTexture = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (currentSurfaceOwner == SurfaceOwner.PRIMARY && currentTexture === surface)
            onPrimaryFrameRendered?.invoke()
    }

    companion object {
        private const val TAG = "mpv"
    }
}
