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
import kotlin.math.min

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    /**
     * Largest square edge that both the GLES viewport and texture path can use
     * on this device. Zoom rendering uses the real device limit instead of a
     * hard-coded 8192-pixel ceiling.
     */
    val maxRenderSurfaceEdge: Int = queryRenderSurfaceEdgeLimit()

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

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

    /**
     * Set mpv's render-window size without changing the TextureView's on-screen
     * size.
     *
     * mpv's Android EGL context applies android-surface-size to the underlying
     * ANativeWindow. Do not also call SurfaceTexture.setDefaultBufferSize() here:
     * doing both creates two independently timed geometry changes and can expose
     * one frame with the old content mapped through the new dimensions.
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

    private fun ensureRenderSurfaceSize(width: Int, height: Int) {
        if (customRenderSurfaceSize)
            return

        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)
    }

    private fun applyRenderSurfaceSize() {
        if (attachedTexture == null)
            return
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

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
        // A producer-side buffer resize can also cause this callback. Only a
        // real View resize should drive the automatic (non-custom) surface size.
        if (!customRenderSurfaceSize) {
            ensureRenderSurfaceSize(this.width, this.height)
            applyRenderSurfaceSize()
        }
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
        private const val FALLBACK_MAX_RENDER_SURFACE_EDGE = 8192

        /**
         * SurfaceTexture requires both dimensions to fit GL_MAX_TEXTURE_SIZE and
         * GL_MAX_VIEWPORT_DIMS. Query them once with a tiny temporary GLES
         * context, before libmpv creates its own context.
         */
        private fun queryRenderSurfaceEdgeLimit(): Int {
            val previousDisplay = EGL14.eglGetCurrentDisplay()
            val previousContext = EGL14.eglGetCurrentContext()
            val previousDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val previousReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE

            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY)
                    return FALLBACK_MAX_RENDER_SURFACE_EDGE

                val versions = IntArray(2)
                if (!EGL14.eglInitialize(display, versions, 0, versions, 1))
                    return FALLBACK_MAX_RENDER_SURFACE_EDGE

                val configAttributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
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
                    ) || configCount[0] <= 0
                ) {
                    return FALLBACK_MAX_RENDER_SURFACE_EDGE
                }
                val config = configs[0] ?: return FALLBACK_MAX_RENDER_SURFACE_EDGE

                context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
                if (context == EGL14.EGL_NO_CONTEXT)
                    return FALLBACK_MAX_RENDER_SURFACE_EDGE

                surface = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                    0,
                )
                if (surface == EGL14.EGL_NO_SURFACE ||
                    !EGL14.eglMakeCurrent(display, surface, surface, context)
                ) {
                    return FALLBACK_MAX_RENDER_SURFACE_EDGE
                }

                val textureSize = IntArray(1)
                val renderbufferSize = IntArray(1)
                val viewportSize = IntArray(2)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureSize, 0)
                GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, renderbufferSize, 0)
                GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewportSize, 0)

                val edgeLimit = min(
                    min(textureSize[0], renderbufferSize[0]),
                    min(viewportSize[0], viewportSize[1]),
                )
                if (edgeLimit > 0)
                    return edgeLimit
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to query GLES render-surface limits", error)
            } finally {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != EGL14.EGL_NO_SURFACE)
                        EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT)
                        EGL14.eglDestroyContext(display, context)
                }
                if (previousDisplay != EGL14.EGL_NO_DISPLAY &&
                    previousContext != EGL14.EGL_NO_CONTEXT
                ) {
                    EGL14.eglMakeCurrent(
                        previousDisplay,
                        previousDrawSurface,
                        previousReadSurface,
                        previousContext,
                    )
                }
            }
            return FALLBACK_MAX_RENDER_SURFACE_EDGE
        }
    }
}
