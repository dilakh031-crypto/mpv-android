package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

// Contains only the essential code needed to get a picture on the screen

/**
 * IMPORTANT ABOUT SurfaceView vs TextureView
 *
 * mpv-android historically used a SurfaceView for best playback efficiency.
 * However, SurfaceView is composed in a separate surface layer. On some devices/Android
 * versions, applying large scale/translation transforms to a SurfaceView (e.g. 10x-20x
 * zoom + pan) can result in visible "shimmer"/"jitter" during movement due to
 * SurfaceFlinger/HWC quantization and asynchronous surface position updates.
 *
 * TextureView is part of the normal View hierarchy and its transforms are applied in the
 * same composition pass as other UI, which makes high zoom/pan much more stable.
 *
 * This project implements pinch-to-zoom by transforming the video view itself
 * (see VideoZoomGestures). Therefore, we use TextureView here to avoid jitter
 * when zoomed to large scales.
 */
abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
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
        observeProperties()

        mpvInitialized = true

        // If the TextureView surface is already available, attach immediately.
        if (isAvailable) {
            val st = surfaceTexture
            if (st != null)
                onSurfaceTextureAvailable(st, width, height)
        }
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Best-effort detach if we're still attached.
        if (surfaceAttached) {
            try {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setPropertyString("force-window", "no")
                MPVLib.detachSurface()
            } catch (_: Throwable) {
                // Ignore; we're shutting down anyway.
            }
            try { surface?.release() } catch (_: Throwable) {}
            surface = null
            surfaceAttached = false
        }
        mpvInitialized = false

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
        this.filePath = filePath
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

    // TextureView callbacks

    private var mpvInitialized: Boolean = false
    private var surfaceAttached: Boolean = false
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        if (!mpvInitialized)
            return
        Log.w(TAG, "attaching surface")

        // If we're reusing a SurfaceTexture (rare), detach first.
        if (surfaceAttached) {
            try { MPVLib.detachSurface() } catch (_: Throwable) {}
            try { surface?.release() } catch (_: Throwable) {}
            surface = null
            surfaceAttached = false
        }

        surface = Surface(st)
        MPVLib.attachSurface(surface!!)
        surfaceAttached = true

        // Tell mpv the surface size (important for correct scaling).
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")

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

    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        if (!mpvInitialized || !surfaceAttached)
            return
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
        if (!mpvInitialized || !surfaceAttached)
            return true

        Log.w(TAG, "detaching surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachSurface()
        surfaceAttached = false
        try { surface?.release() } catch (_: Throwable) {}
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
        // no-op
    }

    companion object {
        private const val TAG = "mpv"
    }
}
