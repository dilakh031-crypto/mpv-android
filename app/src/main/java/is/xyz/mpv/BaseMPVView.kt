package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        destroying = false
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
        destroying = true
        surfaceTextureListener = null
        surfaceHandler.removeCallbacks(finishSurfaceDetachRunnable)
        pendingSurfaceAttach = null

        // mpv_terminate_destroy() is the synchronization point that guarantees
        // the VO has stopped using its Java Surface. The JNI layer releases its
        // global reference after that point; only then release our wrappers.
        if (attachedSurface != null) {
            try {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setPropertyString("force-window", "no")
            } catch (_: Throwable) {
                // Destruction still has to continue if mpv is already shutting down.
            }
        }

        val activeSurface = attachedSurface
        val pendingDetach = pendingSurfaceDetach
        attachedSurface = null
        attachedTexture = null
        pendingSurfaceDetach = null

        try {
            MPVLib.destroy()
        } finally {
            activeSurface?.release()
            pendingDetach?.let {
                it.surface.release()
                if (it.releaseTexture)
                    it.texture.release()
            }
        }
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

    private data class PendingSurfaceDetach(
        val surface: Surface,
        val texture: SurfaceTexture,
        val releaseTexture: Boolean,
        val deadlineUptimeMs: Long,
    )

    private data class PendingSurfaceAttach(
        val texture: SurfaceTexture,
        var width: Int,
        var height: Int,
    )

    private val surfaceHandler = Handler(Looper.getMainLooper())
    private var pendingSurfaceDetach: PendingSurfaceDetach? = null
    private var pendingSurfaceAttach: PendingSurfaceAttach? = null
    private var destroying = false

    private val finishSurfaceDetachRunnable = object : Runnable {
        override fun run() {
            val pending = pendingSurfaceDetach ?: return
            if (destroying)
                return

            val voConfigured = try {
                MPVLib.getPropertyBoolean("vo-configured")
            } catch (_: Throwable) {
                null
            }

            if (voConfigured != false && SystemClock.uptimeMillis() < pending.deadlineUptimeMs) {
                surfaceHandler.postDelayed(this, SURFACE_DETACH_POLL_MS)
                return
            }

            if (voConfigured != false)
                Log.w(TAG, "timed out waiting for video output to release texture surface")
            finishPendingSurfaceDetach(pending)
        }
    }

    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var customRenderSurfaceSize = false

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

    /**
     * Set the real SurfaceTexture buffer size used by mpv without changing the
     * TextureView's on-screen size.
     *
     * The caller chooses the high-resolution size; this layer only enforces the
     * renderer's absolute edge ceiling and falls back to the view size if a
     * vendor SurfaceTexture rejects the allocation.
     */
    fun setRenderSurfaceSize(width: Int, height: Int) {
        val safeWidth = width.coerceIn(1, MAX_RENDER_SURFACE_EDGE)
        val safeHeight = height.coerceIn(1, MAX_RENDER_SURFACE_EDGE)
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
        val texture = attachedTexture ?: return
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        if (!setTextureBufferSize(texture, renderSurfaceWidth, renderSurfaceHeight)) {
            // Keep playback alive if a vendor rejects a high-resolution buffer.
            // The zoom model remains active and can request high quality again
            // after the next surface recreation.
            renderSurfaceWidth = width.coerceAtLeast(1)
            renderSurfaceHeight = height.coerceAtLeast(1)
            setTextureBufferSize(texture, renderSurfaceWidth, renderSurfaceHeight)
        }
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
    }

    private fun setTextureBufferSize(texture: SurfaceTexture, width: Int, height: Int): Boolean {
        return try {
            texture.setDefaultBufferSize(width, height)
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "failed to set texture buffer to ${width}x$height", error)
            false
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "not enough memory for texture buffer ${width}x$height", error)
            false
        }
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (destroying)
            return
        if (pendingSurfaceDetach != null) {
            pendingSurfaceAttach = PendingSurfaceAttach(texture, width, height)
            return
        }
        if (attachedSurface != null)
            return

        if (pendingSurfaceAttach?.texture === texture)
            pendingSurfaceAttach = null
        attachedTexture = texture
        ensureRenderSurfaceSize(width, height)
        if (!setTextureBufferSize(texture, renderSurfaceWidth, renderSurfaceHeight)) {
            renderSurfaceWidth = width.coerceAtLeast(1)
            renderSurfaceHeight = height.coerceAtLeast(1)
            setTextureBufferSize(texture, renderSurfaceWidth, renderSurfaceHeight)
        }

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

    private fun requestSurfaceDetach(releaseTexture: Boolean) {
        if (pendingSurfaceDetach != null)
            return
        val surface = attachedSurface ?: return
        val texture = attachedTexture ?: return

        attachedSurface = null
        attachedTexture = null

        val pending = PendingSurfaceDetach(
            surface = surface,
            texture = texture,
            releaseTexture = releaseTexture,
            deadlineUptimeMs = SystemClock.uptimeMillis() + SURFACE_DETACH_TIMEOUT_MS,
        )
        pendingSurfaceDetach = pending

        Log.w(TAG, "waiting for video output before detaching texture surface")
        try {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            surfaceHandler.post(finishSurfaceDetachRunnable)
        } catch (error: Throwable) {
            Log.e(TAG, "failed to stop video output before surface detach", error)
            finishPendingSurfaceDetach(pending)
        }
    }

    private fun finishPendingSurfaceDetach(pending: PendingSurfaceDetach) {
        if (pendingSurfaceDetach !== pending)
            return
        surfaceHandler.removeCallbacks(finishSurfaceDetachRunnable)

        try {
            MPVLib.detachSurface()
        } catch (error: Throwable) {
            Log.e(TAG, "failed to detach texture surface from mpv", error)
        } finally {
            pending.surface.release()
            if (pending.releaseTexture)
                pending.texture.release()
            pendingSurfaceDetach = null
        }

        val next = pendingSurfaceAttach
        pendingSurfaceAttach = null
        if (!destroying && next != null)
            attachSurfaceTexture(next.texture, next.width, next.height)
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (surface === attachedTexture) {
            ensureRenderSurfaceSize(width, height)
            applyRenderSurfaceSize()
        } else if (surface === pendingSurfaceAttach?.texture) {
            pendingSurfaceAttach?.width = width
            pendingSurfaceAttach?.height = height
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (surface === attachedTexture) {
            // Returning false transfers SurfaceTexture ownership to us. It is
            // released only after vo-configured becomes false.
            requestSurfaceDetach(releaseTexture = true)
            return false
        }

        if (surface === pendingSurfaceAttach?.texture)
            pendingSurfaceAttach = null
        return surface !== pendingSurfaceDetach?.texture
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (surface === attachedTexture)
            onSurfaceTextureFrameAvailable?.invoke()
    }

    companion object {
        private const val TAG = "mpv"
        private const val MAX_RENDER_SURFACE_EDGE = 8192
        private const val SURFACE_DETACH_POLL_MS = 16L
        private const val SURFACE_DETACH_TIMEOUT_MS = 2000L
    }
}
