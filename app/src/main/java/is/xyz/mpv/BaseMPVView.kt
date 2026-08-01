package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

// Contains only the essential code needed to get a picture on the screen.

abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
    TextureView(context, attrs), TextureView.SurfaceTextureListener {

    init {
        isOpaque = true
    }

    /** Initialize libmpv. Call this once before the view is shown. */
    fun initialize(configDir: String, cacheDir: String) {
        MPVLib.create(context)

        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            MPVLib.setOptionString(opt, cacheDir)
        initOptions()
        MPVLib.setOptionString("vo", RENDER_API_VO)
        // The Render API context is created only after TextureView supplies its
        // Surface. Prevent mpv.conf from creating a VO during mpv_initialize().
        MPVLib.setOptionString("force-window", "no")

        MPVLib.init()

        postInitOptions()
        // mpv.conf is loaded during initialization and may contain its own VO.
        // Reassert libmpv before any file can start so the Render API remains the
        // sole owner of video output.
        MPVLib.setPropertyString("vo", RENDER_API_VO)
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.setOptionString("idle", "once")

        surfaceTextureListener = this
        if (isAvailable)
            surfaceTexture?.let { attachSurfaceTexture(it, width, height) }
        observeProperties()
    }

    /** Deinitialize libmpv. Call this once before the view is destroyed. */
    fun destroy() {
        surfaceTextureListener = null
        detachSurfaceTexture()
        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    private var filePath: String? = null
    private var attachedSurface: Surface? = null
    private var attachRetryCount = 0

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

    /**
     * Keep the existing preference entry point, but the embedded Render API must
     * always use vo=libmpv. The selected gpu/gpu-next value cannot own a second
     * Android window while this renderer is active.
     */
    fun setVo(@Suppress("UNUSED_PARAMETER") vo: String) {
        MPVLib.setOptionString("vo", RENDER_API_VO)
    }

    /** Set the first file to be played once the render context is ready. */
    fun playFile(filePath: String) {
        if (attachedSurface != null) {
            MPVLib.command(arrayOf("loadfile", filePath))
            this.filePath = null
        } else {
            this.filePath = filePath
        }
    }

    /**
     * Update the two off-screen mpv targets and the final on-screen rectangle.
     * The TextureView/Android surface itself always stays at the window size.
     */
    fun setRenderState(
        normalWidth: Int,
        normalHeight: Int,
        detailWidth: Int,
        detailHeight: Int,
        useDetail: Boolean,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Long {
        return MPVLib.setRenderState(
            normalWidth.coerceAtLeast(1),
            normalHeight.coerceAtLeast(1),
            detailWidth.coerceAtLeast(1),
            detailHeight.coerceAtLeast(1),
            useDetail,
            left,
            top,
            right,
            bottom,
        )
    }


    fun getPresentedRenderStateSerial(): Long = MPVLib.getPresentedRenderStateSerial()

    /** Invalidate retained FBO pixels before mpv starts decoding a new file. */
    fun beginNewMediaRenderState(): Long = MPVLib.beginNewMediaRenderState()

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null || width <= 0 || height <= 0)
            return

        MPVLib.setRenderState(
            width, height, width, height, false,
            0f, 0f, width.toFloat(), height.toFloat(),
        )
        val surface = Surface(texture)
        Log.w(TAG, "attaching Render API surface ${width}x${height}")
        val attached = MPVLib.attachSurface(surface, width, height)
        if (!attached) {
            Log.e(TAG, "failed to attach Render API surface")
            surface.release()
            scheduleAttachRetry(texture)
            return
        }

        attachRetryCount = 0
        attachedSurface = surface
        filePath?.let {
            MPVLib.command(arrayOf("loadfile", it))
            filePath = null
        }
    }


    private fun scheduleAttachRetry(texture: SurfaceTexture) {
        if (attachRetryCount >= MAX_ATTACH_RETRIES)
            return
        val attempt = ++attachRetryCount
        postDelayed({
            if (
                attachedSurface == null &&
                surfaceTextureListener === this &&
                isAvailable &&
                surfaceTexture === texture
            ) {
                attachSurfaceTexture(texture, width, height)
            }
        }, ATTACH_RETRY_DELAY_MS * attempt)
    }

    private fun detachSurfaceTexture() {
        val surface = attachedSurface ?: return
        Log.w(TAG, "detaching Render API surface")
        MPVLib.detachSurface()
        surface.release()
        attachedSurface = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null && width > 0 && height > 0)
            MPVLib.resizeSurface(width, height)
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
        private const val RENDER_API_VO = "libmpv"
        private const val MAX_ATTACH_RETRIES = 3
        private const val ATTACH_RETRY_DELAY_MS = 100L
    }
}
