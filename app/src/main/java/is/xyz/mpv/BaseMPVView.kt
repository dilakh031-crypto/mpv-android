package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

// Contains only the essential code needed to get a picture on the screen.
//
// NOTE:
// This used to be a SurfaceView. SurfaceView transformations (scale/translate) are not
// guaranteed to be smooth because the underlying surface is composited out-of-band and
// position updates are applied via separate surface transactions.
//
// mpv-android implements pinch-to-zoom and panning by transforming the Android view.
// Using a TextureView makes these transforms part of the normal View compositor pipeline,
// eliminating the "jitter"/"shaking" that can happen when panning a transformed SurfaceView.

abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
    TextureView(context, attrs),
    TextureView.SurfaceTextureListener {

    private var initialized = false

    private var filePath: String? = null
    private var voInUse: String = "gpu"

    // Surface state (may exist before initialize() is called).
    private var surfaceTextureReady: SurfaceTexture? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceAttached = false

    init {
        // Always listen; attach is deferred until initialize() completes.
        surfaceTextureListener = this
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
        // could mess up VO init before a surface is attached
        MPVLib.setOptionString("force-window", "no")
        // need to idle at least once for playFile() logic to work
        MPVLib.setOptionString("idle", "once")

        observeProperties()

        initialized = true
        // If the TextureView surface already exists, attach now.
        attachIfPossible(reason = "initialize")
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Detach first so mpv stops referencing the Surface.
        detachIfAttached(reason = "destroy")
        initialized = false
        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    /**
     * Set the first file to be played once the player is ready.
     */
    fun playFile(filePath: String) {
        this.filePath = filePath
        // If we're already attached, we can load immediately.
        if (initialized && surfaceAttached) {
            MPVLib.command(arrayOf("loadfile", filePath))
            this.filePath = null
        }
    }

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    // TextureView callbacks

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surfaceTextureReady = st
        surfaceWidth = width
        surfaceHeight = height

        // Create the Surface immediately; attach is deferred until initialize() is done.
        surface?.release()
        surface = Surface(st)

        attachIfPossible(reason = "available")
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (initialized && surfaceAttached && width > 0 && height > 0) {
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        detachIfAttached(reason = "destroyed")
        surfaceTextureReady = null
        surfaceWidth = 0
        surfaceHeight = 0
        // We created the Surface wrapper; release it.
        surface?.release()
        surface = null
        // Let the framework release the SurfaceTexture.
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // No-op.
    }

    private fun attachIfPossible(reason: String) {
        if (!initialized) return
        if (surfaceAttached) return
        val s = surface ?: return

        Log.w(TAG, "attaching surface (TextureView) [$reason]")
        MPVLib.attachSurface(s)
        // Force mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        if (surfaceWidth > 0 && surfaceHeight > 0) {
            MPVLib.setPropertyString("android-surface-size", "${surfaceWidth}x$surfaceHeight")
        }

        surfaceAttached = true

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    private fun detachIfAttached(reason: String) {
        if (!initialized) return
        if (!surfaceAttached) return

        Log.w(TAG, "detaching surface (TextureView) [$reason]")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because setting a property
        // will not necessarily wait for VO deinit.
        MPVLib.detachSurface()
        surfaceAttached = false
    }

    companion object {
        private const val TAG = "mpv"
    }
}
