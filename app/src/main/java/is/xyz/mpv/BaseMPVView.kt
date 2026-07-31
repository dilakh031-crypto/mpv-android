package `is`.xyz.mpv

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

// Contains only the essential code needed to get a picture on the screen.
// SurfaceView is deliberately used instead of TextureView: it keeps video on the
// dedicated compositor path, which is more reliable for HDR, frame pacing and power.

abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
    SurfaceView(context, attrs), SurfaceHolder.Callback2 {

    data class RenderSurfaceLimits(
        val maxEdge: Int,
        val maxPixels: Long,
    )

    private val cachedRenderSurfaceLimits: RenderSurfaceLimits by lazy {
        calculateRenderSurfaceLimits()
    }

    init {
        holder.addCallback(this)
    }

    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        // Resolve the allocation ceiling before libmpv creates its own EGL context.
        // This keeps the temporary capability probe isolated from the video renderer.
        cachedRenderSurfaceLimits
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

        if (holder.surface?.isValid == true)
            attachSurface(holder)
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        holder.removeCallback(this)
        detachSurface()
        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    private var filePath: String? = null

    /** Set the first file to be played once the player is ready. */
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
    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var customRenderSurfaceSize = false
    private var preferredFrameRate = 0f

    /** The actual device-aware ceiling used by zoom surface allocation. */
    fun getRenderSurfaceLimits(): RenderSurfaceLimits = cachedRenderSurfaceLimits

    /**
     * Set the real Surface buffer size used by mpv without changing this view's layout size.
     * Requests are clamped to a GPU- and memory-aware ceiling before reaching SurfaceFlinger.
     */
    fun setRenderSurfaceSize(width: Int, height: Int) {
        val clamped = clampRenderSurfaceSize(width, height)
        customRenderSurfaceSize = true

        if (clamped.first == renderSurfaceWidth && clamped.second == renderSurfaceHeight)
            return

        renderSurfaceWidth = clamped.first
        renderSurfaceHeight = clamped.second
        applyRenderSurfaceSize()
    }

    fun resetRenderSurfaceSize() {
        customRenderSurfaceSize = false
        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)

        try {
            holder.setSizeFromLayout()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to restore SurfaceView size from layout", t)
        }
        updateMpvSurfaceSize(renderSurfaceWidth, renderSurfaceHeight)
    }

    /**
     * Ask Android to match the display mode to the video's frame rate where supported.
     * A zero/invalid rate clears the request. Android remains free to reject non-seamless changes.
     */
    fun setPreferredFrameRate(fps: Float) {
        preferredFrameRate = fps.takeIf { it.isFinite() && it in 1f..240f } ?: 0f
        applyPreferredFrameRate(attachedSurface)
    }

    private fun applyPreferredFrameRate(surface: Surface?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || surface?.isValid != true)
            return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    preferredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                @Suppress("DEPRECATION")
                surface.setFrameRate(
                    preferredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to apply preferred frame rate $preferredFrameRate", t)
        }
    }


    private fun clearPreferredFrameRate(surface: Surface?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || surface?.isValid != true)
            return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else {
                @Suppress("DEPRECATION")
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to clear preferred frame rate", t)
        }
    }

    private fun applyRenderSurfaceSize() {
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        try {
            holder.setFixedSize(renderSurfaceWidth, renderSurfaceHeight)
            updateMpvSurfaceSize(renderSurfaceWidth, renderSurfaceHeight)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resize video surface to ${renderSurfaceWidth}x${renderSurfaceHeight}", t)
            customRenderSurfaceSize = false
            try {
                holder.setSizeFromLayout()
            } catch (restoreError: Throwable) {
                Log.e(TAG, "Failed to restore video surface after resize failure", restoreError)
            }
        }
    }

    private fun attachSurface(surfaceHolder: SurfaceHolder) {
        if (attachedSurface != null)
            return

        val surface = surfaceHolder.surface
        if (!surface.isValid)
            return

        if (customRenderSurfaceSize) {
            applyRenderSurfaceSize()
        } else {
            renderSurfaceWidth = width.coerceAtLeast(1)
            renderSurfaceHeight = height.coerceAtLeast(1)
        }

        Log.i(TAG, "attaching SurfaceView surface ${renderSurfaceWidth}x${renderSurfaceHeight}")
        attachedSurface = surface
        MPVLib.attachSurface(surface)
        updateMpvSurfaceSize(renderSurfaceWidth, renderSurfaceHeight)
        MPVLib.setOptionString("force-window", "yes")
        applyPreferredFrameRate(surface)

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    private fun detachSurface() {
        if (attachedSurface == null)
            return

        Log.i(TAG, "detaching SurfaceView surface")
        try {
            clearPreferredFrameRate(attachedSurface)
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
        } catch (t: Throwable) {
            Log.w(TAG, "Error while detaching video surface", t)
        } finally {
            // SurfaceHolder owns this Surface. Releasing it ourselves can race the VO.
            attachedSurface = null
        }
    }

    private fun updateMpvSurfaceSize(surfaceWidth: Int, surfaceHeight: Int) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0)
            return
        MPVLib.setPropertyString("android-surface-size", "${surfaceWidth}x${surfaceHeight}")
    }

    private fun clampRenderSurfaceSize(width: Int, height: Int): Pair<Int, Int> {
        var safeWidth = width.coerceAtLeast(1).toLong()
        var safeHeight = height.coerceAtLeast(1).toLong()
        val limits = cachedRenderSurfaceLimits

        val edgeScale = min(
            limits.maxEdge.toDouble() / safeWidth.toDouble(),
            limits.maxEdge.toDouble() / safeHeight.toDouble(),
        ).coerceAtMost(1.0)
        if (edgeScale < 1.0) {
            safeWidth = (safeWidth * edgeScale).toLong().coerceAtLeast(1L)
            safeHeight = (safeHeight * edgeScale).toLong().coerceAtLeast(1L)
        }

        val pixels = safeWidth * safeHeight
        if (pixels > limits.maxPixels) {
            val pixelScale = kotlin.math.sqrt(limits.maxPixels.toDouble() / pixels.toDouble())
            safeWidth = (safeWidth * pixelScale).toLong().coerceAtLeast(1L)
            safeHeight = (safeHeight * pixelScale).toLong().coerceAtLeast(1L)
        }

        return safeWidth.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() to
            safeHeight.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun calculateRenderSurfaceLimits(): RenderSurfaceLimits {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClass = activityManager.memoryClass
        val lowRam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            activityManager.isLowRamDevice
        else
            memoryClass <= 128

        val gpuEdge = queryMaxTextureSize().coerceAtLeast(MIN_SAFE_TEXTURE_EDGE)
        val maxEdge = min(gpuEdge, HARD_MAX_TEXTURE_EDGE)
        val maxPixels = when {
            lowRam || memoryClass <= 192 -> 8_500_000L
            memoryClass <= 256 -> 12_000_000L
            memoryClass <= 512 -> 18_000_000L
            else -> 24_000_000L
        }.coerceAtMost(maxEdge.toLong() * maxEdge.toLong())

        Log.i(
            TAG,
            "Render surface limits: edge=$maxEdge pixels=$maxPixels " +
                "(GL=$gpuEdge, memoryClass=$memoryClass, lowRam=$lowRam)",
        )
        return RenderSurfaceLimits(maxEdge, maxPixels)
    }

    /** Query GL_MAX_TEXTURE_SIZE using a tiny temporary GLES2 pbuffer context. */
    private fun queryMaxTextureSize(): Int {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY)
                return DEFAULT_TEXTURE_EDGE

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1))
                return DEFAULT_TEXTURE_EDGE

            if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API))
                return DEFAULT_TEXTURE_EDGE

            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
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
            ) return DEFAULT_TEXTURE_EDGE

            val config = configs[0] ?: return DEFAULT_TEXTURE_EDGE
            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE,
            )
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0,
            )
            if (context == EGL14.EGL_NO_CONTEXT)
                return DEFAULT_TEXTURE_EDGE

            val pbufferAttributes = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttributes, 0)
            if (surface == EGL14.EGL_NO_SURFACE)
                return DEFAULT_TEXTURE_EDGE

            if (!EGL14.eglMakeCurrent(display, surface, surface, context))
                return DEFAULT_TEXTURE_EDGE

            val value = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0)
            value[0].takeIf { it > 0 } ?: DEFAULT_TEXTURE_EDGE
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to query GL_MAX_TEXTURE_SIZE", t)
            DEFAULT_TEXTURE_EDGE
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                try { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (_: Throwable) {}
                if (surface != EGL14.EGL_NO_SURFACE) try { EGL14.eglDestroySurface(display, surface) } catch (_: Throwable) {}
                if (context != EGL14.EGL_NO_CONTEXT) try { EGL14.eglDestroyContext(display, context) } catch (_: Throwable) {}
                try { EGL14.eglTerminate(display) } catch (_: Throwable) {}
                try { EGL14.eglReleaseThread() } catch (_: Throwable) {}
            }
        }
    }

    // SurfaceHolder callbacks

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurface(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!customRenderSurfaceSize) {
            renderSurfaceWidth = width.coerceAtLeast(1)
            renderSurfaceHeight = height.coerceAtLeast(1)
        }
        updateMpvSurfaceSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        // mpv owns drawing. PLAYBACK_RESTART is used by MPVActivity as the reliable
        // signal that a newly configured frame can be revealed.
    }

    companion object {
        private const val TAG = "mpv"
        private const val MIN_SAFE_TEXTURE_EDGE = 2048
        private const val DEFAULT_TEXTURE_EDGE = 4096
        private const val HARD_MAX_TEXTURE_EDGE = 8192
    }
}
