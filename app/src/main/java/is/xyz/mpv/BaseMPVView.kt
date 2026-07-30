package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlin.math.min

// Contains only the essential code needed to get a picture on the screen.

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    init {
        // Zoom/pan is applied with TextureView.setTransform(). The transformed texture does
        // not always cover the whole View (letterbox/pillarbox), so the TextureView must not
        // claim that every pixel is opaque. The player window itself is black.
        isOpaque = false
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
        removeCallbacks(applyRenderSurfaceSizeRunnable)
        renderSurfaceSizeApplyScheduled = false
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
    private var appliedRenderSurfaceWidth = 0
    private var appliedRenderSurfaceHeight = 0
    private var customRenderSurfaceSize = false
    private var renderSurfaceSizeApplyScheduled = false
    private var requestedRenderSurfaceGeneration = 0L
    private var appliedRenderSurfaceGeneration = 0L
    private var surfaceFrameSerial = 0L

    /**
     * Called from TextureView while a newly queued frame is being incorporated into the
     * hardware layer. A transform set by this callback is applied to that same draw pass.
     */
    var onSurfaceTextureFrameAvailable: ((SurfaceFrameInfo) -> Unit)? = null

    private val applyRenderSurfaceSizeRunnable = Runnable {
        renderSurfaceSizeApplyScheduled = false
        applyRenderSurfaceSizeNow()
    }

    /**
     * Set the real SurfaceTexture buffer size used by mpv without changing the
     * TextureView's on-screen size.
     *
     * Requests are coalesced to one update per display frame. This prevents a pinch gesture
     * from repeatedly reconfiguring the producer several times inside the same vsync while
     * still letting Android transform the previous frame continuously.
     */
    fun setRenderSurfaceSize(width: Int, height: Int): Long {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        customRenderSurfaceSize = true

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight)
            return requestedRenderSurfaceGeneration

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        requestedRenderSurfaceGeneration += 1L
        scheduleRenderSurfaceSizeApply()
        return requestedRenderSurfaceGeneration
    }

    /** Return to the original mpv-android behavior: output surface equals the View size. */
    fun resetRenderSurfaceSize(): Long {
        customRenderSurfaceSize = false
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight)
            return requestedRenderSurfaceGeneration

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        requestedRenderSurfaceGeneration += 1L
        scheduleRenderSurfaceSizeApply()
        return requestedRenderSurfaceGeneration
    }

    fun getRenderSurfaceLimits(): RenderSurfaceLimits = DEVICE_RENDER_SURFACE_LIMITS

    fun getSurfaceFrameSerial(): Long = surfaceFrameSerial

    private fun scheduleRenderSurfaceSizeApply() {
        if (attachedTexture == null)
            return
        if (renderSurfaceSizeApplyScheduled)
            return

        renderSurfaceSizeApplyScheduled = true
        postOnAnimation(applyRenderSurfaceSizeRunnable)
    }

    private fun ensureRenderSurfaceSize(width: Int, height: Int) {
        if (customRenderSurfaceSize)
            return

        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight)
            return

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        requestedRenderSurfaceGeneration += 1L
    }

    private fun applyRenderSurfaceSizeNow(force: Boolean = false) {
        val texture = attachedTexture ?: return
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return
        if (!force && renderSurfaceWidth == appliedRenderSurfaceWidth &&
            renderSurfaceHeight == appliedRenderSurfaceHeight
        ) return

        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
        appliedRenderSurfaceWidth = renderSurfaceWidth
        appliedRenderSurfaceHeight = renderSurfaceHeight
        appliedRenderSurfaceGeneration = requestedRenderSurfaceGeneration
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        attachedTexture = texture
        appliedRenderSurfaceWidth = 0
        appliedRenderSurfaceHeight = 0
        ensureRenderSurfaceSize(width, height)
        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)

        Log.w(TAG, "attaching texture surface ${renderSurfaceWidth}x${renderSurfaceHeight}")
        val surface = Surface(texture)
        attachedSurface = surface

        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
        appliedRenderSurfaceWidth = renderSurfaceWidth
        appliedRenderSurfaceHeight = renderSurfaceHeight
        appliedRenderSurfaceGeneration = requestedRenderSurfaceGeneration
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
        appliedRenderSurfaceWidth = 0
        appliedRenderSurfaceHeight = 0
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        ensureRenderSurfaceSize(width, height)
        // TextureView itself first resets the SurfaceTexture to the View dimensions. Reapply our
        // custom size immediately so mpv never renders a transient frame at a stale resolution.
        applyRenderSurfaceSizeNow(force = true)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        surfaceFrameSerial += 1L
        onSurfaceTextureFrameAvailable?.invoke(
            SurfaceFrameInfo(surfaceFrameSerial, appliedRenderSurfaceGeneration),
        )
    }

    data class SurfaceFrameInfo(
        val serial: Long,
        val renderSurfaceGeneration: Long,
    )

    data class RenderSurfaceLimits(val maxWidth: Int, val maxHeight: Int)

    companion object {
        private const val TAG = "mpv"
        private const val FALLBACK_MAX_RENDER_EDGE = 8192

        private val DEVICE_RENDER_SURFACE_LIMITS: RenderSurfaceLimits by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            queryRenderSurfaceLimits()
        }

        /**
         * Query the real GLES limits instead of imposing the previous hard-coded 8192x8192 cap.
         * The probe is normally executed before libmpv creates its EGL context.
         */
        private fun queryRenderSurfaceLimits(): RenderSurfaceLimits {
            val existingContext = EGL14.eglGetCurrentContext()
            if (existingContext != EGL14.EGL_NO_CONTEXT)
                return queryCurrentGlLimits()

            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY)
                return RenderSurfaceLimits(FALLBACK_MAX_RENDER_EDGE, FALLBACK_MAX_RENDER_EDGE)

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1))
                return RenderSurfaceLimits(FALLBACK_MAX_RENDER_EDGE, FALLBACK_MAX_RENDER_EDGE)

            var context = EGL14.EGL_NO_CONTEXT
            var surface = EGL14.EGL_NO_SURFACE
            return try {
                val configAttributes = intArrayOf(
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val numConfigs = IntArray(1)
                if (!EGL14.eglChooseConfig(
                        display,
                        configAttributes,
                        0,
                        configs,
                        0,
                        configs.size,
                        numConfigs,
                        0,
                    ) || numConfigs[0] <= 0 || configs[0] == null
                ) {
                    return RenderSurfaceLimits(FALLBACK_MAX_RENDER_EDGE, FALLBACK_MAX_RENDER_EDGE)
                }

                val contextAttributes = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE,
                )
                context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0,
                )
                if (context == EGL14.EGL_NO_CONTEXT)
                    return RenderSurfaceLimits(FALLBACK_MAX_RENDER_EDGE, FALLBACK_MAX_RENDER_EDGE)

                val pbufferAttributes = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE,
                )
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttributes, 0)
                if (surface == EGL14.EGL_NO_SURFACE ||
                    !EGL14.eglMakeCurrent(display, surface, surface, context)
                ) {
                    return RenderSurfaceLimits(FALLBACK_MAX_RENDER_EDGE, FALLBACK_MAX_RENDER_EDGE)
                }

                queryCurrentGlLimits()
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to query GLES render limits", t)
                RenderSurfaceLimits(FALLBACK_MAX_RENDER_EDGE, FALLBACK_MAX_RENDER_EDGE)
            } finally {
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
                // EGL_DEFAULT_DISPLAY is process-global and may already be owned by HWUI.
                // Do not terminate it here: that can invalidate another renderer's contexts.
            }
        }

        private fun queryCurrentGlLimits(): RenderSurfaceLimits {
            val maxTextureSize = IntArray(1)
            val maxViewportSize = IntArray(2)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewportSize, 0)

            val texture = maxTextureSize[0].takeIf { it > 0 } ?: FALLBACK_MAX_RENDER_EDGE
            val viewportWidth = maxViewportSize[0].takeIf { it > 0 } ?: texture
            val viewportHeight = maxViewportSize[1].takeIf { it > 0 } ?: texture
            return RenderSurfaceLimits(
                maxWidth = min(texture, viewportWidth),
                maxHeight = min(texture, viewportHeight),
            )
        }
    }
}
