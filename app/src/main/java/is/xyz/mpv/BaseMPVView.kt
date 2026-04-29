package `is`.xyz.mpv

import android.content.Context
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

    private var viewSurfaceWidth = 0
    private var viewSurfaceHeight = 0
    private var sourceRenderWidth = 0
    private var sourceRenderHeight = 0
    private var appliedRenderWidth = 0
    private var appliedRenderHeight = 0

    /**
     * Use the media's native dimensions as the SurfaceTexture buffer size.
     *
     * The player view may still be scaled by VideoZoomGestures, but mpv now renders
     * into a native-sized buffer instead of a screen-sized one. This keeps still
     * images and high resolution videos sharp when zoomed.
     */
    fun setSourceRenderSize(width: Int?, height: Int?) {
        val w = width ?: 0
        val h = height ?: 0
        sourceRenderWidth = if (w > 0) w else 0
        sourceRenderHeight = if (h > 0) h else 0
        applyRenderSurfaceSize()
    }

    private fun desiredRenderWidth(): Int = if (sourceRenderWidth > 0) sourceRenderWidth else viewSurfaceWidth
    private fun desiredRenderHeight(): Int = if (sourceRenderHeight > 0) sourceRenderHeight else viewSurfaceHeight

    private fun applyRenderSurfaceSize() {
        val texture = surfaceTexture ?: return
        val renderWidth = desiredRenderWidth()
        val renderHeight = desiredRenderHeight()
        if (renderWidth <= 0 || renderHeight <= 0)
            return

        applyTextureTransform(renderWidth, renderHeight)

        if (renderWidth == appliedRenderWidth && renderHeight == appliedRenderHeight)
            return

        Log.v(TAG, "setting render surface size to ${renderWidth}x${renderHeight}")
        texture.setDefaultBufferSize(renderWidth, renderHeight)
        MPVLib.setPropertyString("android-surface-size", "${renderWidth}x${renderHeight}")
        appliedRenderWidth = renderWidth
        appliedRenderHeight = renderHeight
    }

    private fun applyTextureTransform(renderWidth: Int, renderHeight: Int) {
        if (viewSurfaceWidth <= 0 || viewSurfaceHeight <= 0)
            return

        val matrix = Matrix()
        val viewAspect = viewSurfaceWidth.toFloat() / viewSurfaceHeight.toFloat()
        val renderAspect = renderWidth.toFloat() / renderHeight.toFloat()

        if (renderAspect > viewAspect) {
            val scaleY = viewAspect / renderAspect
            matrix.setScale(1f, scaleY, viewSurfaceWidth / 2f, viewSurfaceHeight / 2f)
        } else if (renderAspect < viewAspect) {
            val scaleX = renderAspect / viewAspect
            matrix.setScale(scaleX, 1f, viewSurfaceWidth / 2f, viewSurfaceHeight / 2f)
        }

        setTransform(matrix)
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        viewSurfaceWidth = width
        viewSurfaceHeight = height

        if (attachedSurface != null) {
            applyRenderSurfaceSize()
            return
        }

        Log.w(TAG, "attaching texture surface")
        // Configure the backing buffer before creating the Surface so mpv receives
        // the requested native-sized render target from the start.
        appliedRenderWidth = 0
        appliedRenderHeight = 0
        applyRenderSurfaceSize()
        val surface = Surface(texture)
        attachedSurface = surface

        MPVLib.attachSurface(surface)
        appliedRenderWidth = 0
        appliedRenderHeight = 0
        applyRenderSurfaceSize()
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
        appliedRenderWidth = 0
        appliedRenderHeight = 0
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        viewSurfaceWidth = width
        viewSurfaceHeight = height
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
