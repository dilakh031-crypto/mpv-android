package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

// Contains only the essential code needed to get a picture on the screen

/**
 * TextureView-backed mpv output.
 *
 * A SurfaceTexture buffer resize must not be performed underneath a live EGLSurface. mpv owns
 * that EGLSurface, so custom render-size changes are serialized as:
 *
 *   stop VO -> detach old Surface -> resize SurfaceTexture -> attach a new Surface -> restart VO
 *
 * The stop is asynchronous and we wait for mpv's command reply before touching the producer
 * surface. This preserves the high-resolution zoom buffer without racing libplacebo/EGL.
 */
abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
    TextureView(context, attrs), TextureView.SurfaceTextureListener {

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
        mpvAlive = true
        MPVLib.addObserver(surfaceCommandObserver)

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
        if (destroying)
            return
        destroying = true

        // Stop Java callbacks before terminating mpv. mpv_terminate_destroy() waits for the VO to
        // release EGL/native-window resources, so Java Surface objects are released afterwards.
        surfaceTextureListener = null
        if (mpvAlive)
            MPVLib.removeObserver(surfaceCommandObserver)

        if (mpvAlive) {
            MPVLib.destroy()
            mpvAlive = false
        }

        attachedSurface?.release()
        attachedSurface = null
        attachedTexture = null
        pendingAttach = null

        // onSurfaceTextureDestroyed() returns false while mpv is still using the texture. In that
        // case ownership was transferred to us and it is safe to release only after mpv is dead.
        ownedTextureToRelease?.release()
        ownedTextureToRelease = null

        stopCommandUserdata = null
        stopReason = null
        detachRequested = false
        frameGenerationPending = null
        removeCallbacks(renderSurfaceFrameTimeout)
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

    private var mpvAlive = false
    private var destroying = false

    private var attachedSurface: Surface? = null
    private var attachedTexture: SurfaceTexture? = null
    private var pendingAttach: PendingAttach? = null
    private var ownedTextureToRelease: SurfaceTexture? = null

    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var appliedSurfaceWidth = 0
    private var appliedSurfaceHeight = 0
    private var customRenderSurfaceSize = false

    // Every requested buffer geometry gets a monotonically increasing generation. Zoom code waits
    // for the first TextureView frame produced after that exact rebind instead of treating any
    // already-queued frame as proof that the new surface is active.
    private var requestedSurfaceGeneration = 0L
    private var appliedSurfaceGeneration = 0L
    private var readySurfaceGeneration = 0L
    private var frameGenerationPending: Long? = null

    private var stopCommandUserdata: Long? = null
    private var stopReason: StopReason? = null
    private var detachRequested = false
    private var nextSurfaceCommandUserdata = SURFACE_COMMAND_USERDATA_START

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null
    var onRenderSurfaceGenerationReady: ((Long) -> Unit)? = null
    var onRenderSurfaceGenerationFailed: ((Long) -> Unit)? = null

    /**
     * Set the real SurfaceTexture buffer size used by mpv without changing the TextureView's
     * on-screen size.
     *
     * The resize is intentionally asynchronous: this returns the generation representing the
     * request. Call [isRenderSurfaceGenerationReady] or listen to
     * [onRenderSurfaceGenerationReady] before assuming the new geometry is being displayed.
     */
    fun setRenderSurfaceSize(width: Int, height: Int): Long {
        customRenderSurfaceSize = true
        return requestRenderSurfaceSize(width, height)
    }

    fun resetRenderSurfaceSize(): Long {
        customRenderSurfaceSize = false
        return requestRenderSurfaceSize(width, height)
    }

    fun isRenderSurfaceGenerationReady(generation: Long): Boolean =
        generation <= readySurfaceGeneration

    private fun requestRenderSurfaceSize(width: Int, height: Int): Long {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight) {
            requestSurfaceRebindIfNeeded()
            return requestedSurfaceGeneration
        }

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        requestedSurfaceGeneration += 1L
        requestSurfaceRebindIfNeeded()
        return requestedSurfaceGeneration
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
        requestedSurfaceGeneration += 1L
    }

    /**
     * Coalesces rapid geometry updates. If a stop/rebind or a first-frame handoff is already in
     * progress, the latest requested width/height remains in renderSurfaceWidth/Height and will be
     * applied after the current generation is confirmed.
     */
    private fun requestSurfaceRebindIfNeeded() {
        if (!mpvAlive || destroying || attachedSurface == null || attachedTexture == null)
            return
        if (detachRequested || stopCommandUserdata != null || frameGenerationPending != null)
            return

        if (renderSurfaceWidth == appliedSurfaceWidth && renderSurfaceHeight == appliedSurfaceHeight) {
            markCurrentGenerationReady()
            return
        }

        beginVoStop(StopReason.REBIND)
    }

    private fun beginVoStop(reason: StopReason) {
        if (!mpvAlive || destroying || attachedSurface == null)
            return

        if (stopCommandUserdata != null) {
            if (reason == StopReason.DETACH)
                detachRequested = true
            return
        }

        if (reason == StopReason.DETACH)
            detachRequested = true

        val userdata = nextSurfaceCommandUserdata--
        stopCommandUserdata = userdata
        stopReason = reason

        val result = try {
            MPVLib.commandAsync(arrayOf("set", "vo", "null"), userdata)
        } catch (error: Exception) {
            Log.e(TAG, "failed to queue VO stop for $reason", error)
            Int.MIN_VALUE
        }

        if (result < 0) {
            Log.e(TAG, "mpv rejected VO stop for $reason: $result")
            stopCommandUserdata = null
            stopReason = null
            if (reason == StopReason.DETACH)
                detachRequested = false
        }
    }

    private fun handleSurfaceCommandReply(userdata: Long, error: Int) {
        if (userdata != stopCommandUserdata)
            return

        val reason = stopReason
        stopCommandUserdata = null
        stopReason = null

        if (!mpvAlive || destroying)
            return

        if (error < 0) {
            Log.e(TAG, "VO stop command failed for $reason: $error")
            if (detachRequested) {
                // Keep the SurfaceTexture alive rather than racing an EGLSurface. destroy() is the
                // final synchronous fallback and will release it after mpv_terminate_destroy().
                return
            }
            MPVLib.setPropertyString("vo", voInUse)
            return
        }

        if (detachRequested || reason == StopReason.DETACH) {
            performSafeDetach()
        } else {
            performSafeRebind()
        }
    }

    /** mpv has confirmed vo=null here, so its EGLSurface has been deinitialized. */
    private fun performSafeRebind() {
        val texture = attachedTexture ?: return
        val oldSurface = attachedSurface ?: return

        // A later request may have cancelled the resize while the VO stop was in flight.
        if (renderSurfaceWidth == appliedSurfaceWidth && renderSurfaceHeight == appliedSurfaceHeight) {
            markCurrentGenerationReady()
            MPVLib.setPropertyString("vo", voInUse)
            return
        }

        val targetWidth = renderSurfaceWidth.coerceAtLeast(1)
        val targetHeight = renderSurfaceHeight.coerceAtLeast(1)
        val targetGeneration = requestedSurfaceGeneration

        Log.d(TAG, "rebinding texture surface ${appliedSurfaceWidth}x${appliedSurfaceHeight} -> ${targetWidth}x${targetHeight} (generation $targetGeneration)")

        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
        oldSurface.release()
        attachedSurface = null

        try {
            // This is now safe: vo=null has destroyed mpv's EGLSurface before the producer's
            // default buffer geometry changes.
            texture.setDefaultBufferSize(targetWidth, targetHeight)
            val newSurface = Surface(texture)
            attachedSurface = newSurface
            MPVLib.attachSurface(newSurface)
            MPVLib.setPropertyString("android-surface-size", "${targetWidth}x${targetHeight}")
            MPVLib.setOptionString("force-window", "yes")

            appliedSurfaceWidth = targetWidth
            appliedSurfaceHeight = targetHeight
            appliedSurfaceGeneration = targetGeneration
            frameGenerationPending = targetGeneration
            if (customRenderSurfaceSize)
                armRenderSurfaceFrameTimeout()

            MPVLib.setPropertyString("vo", voInUse)
        } catch (error: Exception) {
            Log.e(TAG, "failed to rebind resized TextureView surface", error)
            recoverFromRebindFailure(texture, targetGeneration)
        }
    }

    /**
     * Synchronous Surface/JNI failures are rare, but leaving attachedSurface=null would make the
     * player unrecoverable until Android recreated the TextureView. Reattach a conservative
     * view-sized producer surface while the VO is still stopped, then report the failed HQ
     * generation so zoom state can downgrade cleanly.
     */
    private fun recoverFromRebindFailure(texture: SurfaceTexture, failedGeneration: Long) {
        try {
            // attachSurface() may have succeeded before a later call threw. Clearing wid is safe
            // because the VO is still null and also releases any native global Surface reference.
            MPVLib.detachSurface()
        } catch (detachError: Exception) {
            Log.w(TAG, "failed to clear partially attached surface", detachError)
        }

        attachedSurface?.release()
        attachedSurface = null
        removeCallbacks(renderSurfaceFrameTimeout)
        frameGenerationPending = null

        val fallbackWidth = width.coerceAtLeast(1)
        val fallbackHeight = height.coerceAtLeast(1)
        customRenderSurfaceSize = false
        renderSurfaceWidth = fallbackWidth
        renderSurfaceHeight = fallbackHeight
        requestedSurfaceGeneration += 1L
        val fallbackGeneration = requestedSurfaceGeneration

        try {
            texture.setDefaultBufferSize(fallbackWidth, fallbackHeight)
            val fallbackSurface = Surface(texture)
            attachedSurface = fallbackSurface
            MPVLib.attachSurface(fallbackSurface)
            MPVLib.setPropertyString("android-surface-size", "${fallbackWidth}x${fallbackHeight}")
            MPVLib.setOptionString("force-window", "yes")

            appliedSurfaceWidth = fallbackWidth
            appliedSurfaceHeight = fallbackHeight
            appliedSurfaceGeneration = fallbackGeneration
            frameGenerationPending = fallbackGeneration
            MPVLib.setPropertyString("vo", voInUse)
        } catch (fallbackError: Exception) {
            Log.e(TAG, "failed to restore view-sized TextureView surface", fallbackError)
            try {
                MPVLib.detachSurface()
            } catch (_: Exception) {
                // mpv teardown remains the final safe cleanup path.
            }
            attachedSurface?.release()
            attachedSurface = null
            MPVLib.setPropertyString("force-window", "no")
        }

        onRenderSurfaceGenerationFailed?.invoke(failedGeneration)
    }

    /** mpv has confirmed vo=null here, so releasing the producer surface cannot race EGL. */
    private fun performSafeDetach() {
        Log.d(TAG, "safely detaching texture surface")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()

        attachedSurface?.release()
        attachedSurface = null
        attachedTexture = null
        appliedSurfaceWidth = 0
        appliedSurfaceHeight = 0
        frameGenerationPending = null
        removeCallbacks(renderSurfaceFrameTimeout)
        detachRequested = false

        ownedTextureToRelease?.release()
        ownedTextureToRelease = null

        val next = pendingAttach
        pendingAttach = null
        if (next != null && !destroying)
            attachSurfaceTexture(next.texture, next.width, next.height)
    }

    private fun markCurrentGenerationReady() {
        appliedSurfaceGeneration = requestedSurfaceGeneration
        if (readySurfaceGeneration < appliedSurfaceGeneration) {
            readySurfaceGeneration = appliedSurfaceGeneration
            onRenderSurfaceGenerationReady?.invoke(readySurfaceGeneration)
        }
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (!mpvAlive || destroying)
            return

        if (attachedSurface != null || stopCommandUserdata != null || detachRequested) {
            // A new TextureView surface can arrive while the old one is still being detached (for
            // example around rotation/background transitions). Keep only the newest one.
            pendingAttach = PendingAttach(texture, width, height)
            if (attachedSurface != null)
                beginVoStop(StopReason.DETACH)
            return
        }

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

        appliedSurfaceWidth = renderSurfaceWidth
        appliedSurfaceHeight = renderSurfaceHeight
        appliedSurfaceGeneration = requestedSurfaceGeneration
        frameGenerationPending = appliedSurfaceGeneration.takeIf { it > readySurfaceGeneration }
        if (frameGenerationPending != null && customRenderSurfaceSize)
            armRenderSurfaceFrameTimeout()

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    private fun requestSafeDetach(textureOwnedByUs: SurfaceTexture?) {
        if (textureOwnedByUs != null)
            ownedTextureToRelease = textureOwnedByUs

        if (attachedSurface == null) {
            ownedTextureToRelease?.release()
            ownedTextureToRelease = null
            attachedTexture = null
            return
        }

        detachRequested = true
        beginVoStop(StopReason.DETACH)
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (surface !== attachedTexture)
            return
        if (customRenderSurfaceSize)
            return

        ensureRenderSurfaceSize(width, height)
        requestSurfaceRebindIfNeeded()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (surface !== attachedTexture) {
            pendingAttach = pendingAttach?.takeUnless { it.texture === surface }
            return true
        }

        // Returning false transfers SurfaceTexture ownership to us. This is essential: Android
        // must not free it while mpv's EGLSurface may still reference its native window.
        requestSafeDetach(surface)
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (surface !== attachedTexture)
            return

        onSurfaceTextureFrameAvailable?.invoke()

        val generation = frameGenerationPending ?: return
        frameGenerationPending = null
        removeCallbacks(renderSurfaceFrameTimeout)
        if (readySurfaceGeneration < generation) {
            readySurfaceGeneration = generation
            onRenderSurfaceGenerationReady?.invoke(generation)
        }

        // Geometry changes that arrived while waiting for this first frame are coalesced here.
        requestSurfaceRebindIfNeeded()
    }

    private fun armRenderSurfaceFrameTimeout() {
        removeCallbacks(renderSurfaceFrameTimeout)
        postDelayed(renderSurfaceFrameTimeout, RENDER_SURFACE_FRAME_TIMEOUT_MS)
    }

    private val renderSurfaceFrameTimeout = Runnable {
        val generation = frameGenerationPending ?: return@Runnable
        if (!customRenderSurfaceSize || generation != appliedSurfaceGeneration)
            return@Runnable

        Log.e(
            TAG,
            "custom render surface ${appliedSurfaceWidth}x${appliedSurfaceHeight} produced no frame; falling back to view-sized buffer",
        )
        frameGenerationPending = null
        onRenderSurfaceGenerationFailed?.invoke(generation)

        // This also covers silent GPU/driver failures where setDefaultBufferSize() succeeds but the
        // requested producer buffers cannot actually be rendered. Keep zoom transforms, but fall
        // back to a known-safe display-sized producer surface.
        customRenderSurfaceSize = false
        requestRenderSurfaceSize(width, height)
    }

    private val surfaceCommandObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Boolean) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun event(eventId: Int) = Unit

        override fun eventCommandReply(userdata: Long, error: Int) {
            // EventObserver callbacks run on mpv's event thread. Filter only after posting to the
            // View thread so stopCommandUserdata is never raced across threads.
            post { handleSurfaceCommandReply(userdata, error) }
        }
    }

    private data class PendingAttach(
        val texture: SurfaceTexture,
        val width: Int,
        val height: Int,
    )

    private enum class StopReason {
        REBIND,
        DETACH,
    }

    companion object {
        private const val TAG = "mpv"

        // Scrub commands use positive IDs. Reserve a distant negative range for Surface lifecycle
        // commands so unrelated COMMAND_REPLY observers can filter them unambiguously.
        private const val SURFACE_COMMAND_USERDATA_START = -0x4000_0000_0000_0000L
        private const val RENDER_SURFACE_FRAME_TIMEOUT_MS = 8_000L
    }
}
