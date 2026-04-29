package `is`.xyz.mpv

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlin.math.ceil
import kotlin.math.min

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    init {
        // TextureView is part of the normal View hierarchy. This makes high-zoom
        // scale/translation much smoother than transforming a SurfaceView layer,
        // especially on older Android devices where SurfaceView composition is
        // quantized by SurfaceFlinger/HWC.
        isOpaque = true
    }

    private var sourceVideoWidth = 0
    private var sourceVideoHeight = 0
    private var sourceVideoAspect = 0.0
    private var surfaceBufferWidth = 0
    private var surfaceBufferHeight = 0

    /**
     * Keep mpv's render target at the source video/image resolution instead of
     * the view/screen resolution. TextureView then scales that original-size
     * buffer to the view, so pinch zoom magnifies source pixels, not a
     * screen-sized render.
     */
    fun setSourceVideoSize(width: Int?, height: Int?, aspect: Double?) {
        sourceVideoWidth = width?.takeIf { it > 0 } ?: 0
        sourceVideoHeight = height?.takeIf { it > 0 } ?: 0
        sourceVideoAspect = aspect?.takeIf { it > 0.001 } ?: 0.0
        updateSurfaceBufferSize()
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

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        Log.w(TAG, "attaching texture surface")
        val (bufferWidth, bufferHeight) = chooseSurfaceBufferSize(width, height)
        texture.setDefaultBufferSize(bufferWidth, bufferHeight)
        surfaceBufferWidth = bufferWidth
        surfaceBufferHeight = bufferHeight
        updateTextureTransform(width, height, bufferWidth, bufferHeight)

        val surface = Surface(texture)
        attachedSurface = surface

        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("android-surface-size", "${bufferWidth}x${bufferHeight}")
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
        surfaceBufferWidth = 0
        surfaceBufferHeight = 0
        setTransform(null)
    }

    private fun updateSurfaceBufferSize(viewWidth: Int = width, viewHeight: Int = height) {
        if (viewWidth <= 0 || viewHeight <= 0)
            return

        val (bufferWidth, bufferHeight) = chooseSurfaceBufferSize(viewWidth, viewHeight)
        surfaceTexture?.let { texture ->
            if (bufferWidth != surfaceBufferWidth || bufferHeight != surfaceBufferHeight) {
                texture.setDefaultBufferSize(bufferWidth, bufferHeight)
                surfaceBufferWidth = bufferWidth
                surfaceBufferHeight = bufferHeight
                if (attachedSurface != null)
                    MPVLib.setPropertyString("android-surface-size", "${bufferWidth}x${bufferHeight}")
            }
        }
        updateTextureTransform(viewWidth, viewHeight, bufferWidth, bufferHeight)
    }

    private fun chooseSurfaceBufferSize(viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        val sourceWidth = sourceVideoWidth
        val sourceHeight = sourceVideoHeight
        if (sourceWidth <= 0 || sourceHeight <= 0)
            return Pair(viewWidth.coerceAtLeast(1), viewHeight.coerceAtLeast(1))

        val aspect = if (sourceVideoAspect > 0.001)
            sourceVideoAspect
        else
            sourceWidth.toDouble() / sourceHeight.toDouble()

        return if (aspect >= sourceWidth.toDouble() / sourceHeight.toDouble()) {
            Pair(ceil(sourceHeight * aspect).toInt().coerceAtLeast(1), sourceHeight)
        } else {
            Pair(sourceWidth, ceil(sourceWidth / aspect).toInt().coerceAtLeast(1))
        }
    }

    private fun updateTextureTransform(viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0)
            return

        val scale = min(viewWidth.toFloat() / bufferWidth.toFloat(), viewHeight.toFloat() / bufferHeight.toFloat())
        val dx = (viewWidth - bufferWidth * scale) * 0.5f
        val dy = (viewHeight - bufferHeight * scale) * 0.5f
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        setTransform(matrix)
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateSurfaceBufferSize(width, height)
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
