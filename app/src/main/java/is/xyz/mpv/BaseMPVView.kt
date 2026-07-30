package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    private val maximumRenderSurfaceEdge = queryMaximumRenderSurfaceEdge()

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
    private var customRenderSurfaceSize = false

    private var displaySizedFallbackQualityActive = false
    private var savedFallbackDscale: String? = null
    private var savedFallbackCorrectDownscaling: String? = null

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

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
     * Use a proper convolution downscaler only while an oversized image has to
     * be rendered directly into the display-sized surface.
     *
     * The app normally starts mpv with the fast profile, whose bilinear
     * downscaler can make a large still image look lower-resolution even when
     * the output buffer itself exactly matches the screen. Save and restore the
     * user's/runtime values so video playback and explicit scaler choices are
     * not changed permanently.
     */
    fun setDisplaySizedFallbackQuality(enabled: Boolean) {
        val shouldEnable = enabled &&
            MPVLib.getPropertyString("current-tracks/video/image") == "yes"

        if (shouldEnable == displaySizedFallbackQualityActive)
            return

        if (shouldEnable) {
            savedFallbackDscale = MPVLib.getPropertyString("dscale") ?: "bilinear"
            savedFallbackCorrectDownscaling =
                MPVLib.getPropertyString("correct-downscaling") ?: "no"

            val currentDscale = savedFallbackDscale
            if (currentDscale.isNullOrBlank() ||
                currentDscale == "bilinear" ||
                currentDscale == "bicubic_fast") {
                MPVLib.setPropertyString("dscale", "mitchell")
            }
            MPVLib.setPropertyString("correct-downscaling", "yes")
        } else {
            savedFallbackDscale?.let { MPVLib.setPropertyString("dscale", it) }
            savedFallbackCorrectDownscaling?.let {
                MPVLib.setPropertyString("correct-downscaling", it)
            }
            savedFallbackDscale = null
            savedFallbackCorrectDownscaling = null
        }

        displaySizedFallbackQualityActive = shouldEnable
    }

    fun getMaximumRenderSurfaceEdge(): Int = maximumRenderSurfaceEdge

    private fun ensureRenderSurfaceSize(width: Int, height: Int) {
        if (customRenderSurfaceSize)
            return

        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)
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
        ensureRenderSurfaceSize(width, height)
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
        ensureRenderSurfaceSize(width, height)
        applyRenderSurfaceSize()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        onSurfaceTextureFrameAvailable?.invoke()
    }

    private fun queryMaximumRenderSurfaceEdge(): Int {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var initialized = false

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY)
                return FALLBACK_MAX_TEXTURE_SIZE

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1))
                return FALLBACK_MAX_TEXTURE_SIZE
            initialized = true

            EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)

            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    display,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.size,
                    configCount,
                    0,
                ) || configCount[0] < 1) {
                return FALLBACK_MAX_TEXTURE_SIZE
            }
            val config = configs[0] ?: return FALLBACK_MAX_TEXTURE_SIZE

            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            eglContext = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0,
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT)
                return FALLBACK_MAX_TEXTURE_SIZE

            val surfaceAttributes = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            eglSurface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                surfaceAttributes,
                0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE)
                return FALLBACK_MAX_TEXTURE_SIZE

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
                return FALLBACK_MAX_TEXTURE_SIZE

            val textureSize = IntArray(1)
            val renderbufferSize = IntArray(1)
            val viewportSize = IntArray(2)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureSize, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, renderbufferSize, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewportSize, 0)

            val detected = minOf(
                textureSize[0],
                renderbufferSize[0],
                viewportSize[0],
                viewportSize[1],
            ).coerceAtLeast(FALLBACK_MAX_TEXTURE_SIZE)
            Log.i(TAG, "GPU safe render-surface edge: $detected")
            return detected
        } catch (e: Throwable) {
            Log.w(TAG, "failed to query GPU render-surface limit", e)
            return FALLBACK_MAX_TEXTURE_SIZE
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY && initialized) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE)
                    EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
        private const val FALLBACK_MAX_TEXTURE_SIZE = 2048
    }
}
