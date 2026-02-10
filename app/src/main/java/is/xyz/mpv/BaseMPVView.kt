package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

// Contains only the essential code needed to get a picture on the screen

/**
 * mpv output view.
 *
 * Using a SurfaceView can show visible jitter on some devices when the surface is
 * transformed (scale/translation) every frame (e.g. pan after zoom). TextureView keeps
 * the video inside the normal View hierarchy, so compositor transforms are smoother.
 */
abstract class BaseMPVView(context: Context, attrs: AttributeSet) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var mpvAlive = false

    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        MPVLib.create(context)
        mpvAlive = true

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

        // Note: setting the listener may immediately trigger callbacks if the TextureView
        // is already available.
        surfaceTextureListener = this
        // Safety net: if some init order prevents the immediate callback, attach on next loop.
        post {
            val st = surfaceTexture
            if (mpvAlive && isAvailable && st != null && attachedSurface == null) {
                onSurfaceTextureAvailable(st, width, height)
            }
        }
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable callbacks first to avoid re-entrancy.
        surfaceTextureListener = null

        detachIfAttached()
        mpvAlive = false

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

    private var attachedSurface: Surface? = null

    private fun detachIfAttached() {
        val s = attachedSurface ?: return
        attachedSurface = null

        if (!mpvAlive) {
            try { s.release() } catch (_: Throwable) {}
            return
        }

        Log.w(TAG, "detaching surface")
        try {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            // Note that before calling detachSurface() we need to be sure that libmpv
            // is done using the surface.
            // FIXME: There could be a race condition here, because setting a property may not
            // wait for VO deinit.
            MPVLib.detachSurface()
        } catch (_: Throwable) {
            // Ignore teardown races.
        }

        try { s.release() } catch (_: Throwable) {}
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (!mpvAlive)
            return

        // Ensure we don't have a stale surface.
        detachIfAttached()

        // Match producer buffer size to view size (reduces extra GPU scaling and helps stability).
        surfaceTexture.setDefaultBufferSize(width, height)

        Log.w(TAG, "attaching surface")
        val surface = Surface(surfaceTexture)
        attachedSurface = surface
        MPVLib.attachSurface(surface)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        MPVLib.setPropertyString("android-surface-size", "${width}x$height")

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (!mpvAlive)
            return
        surfaceTexture.setDefaultBufferSize(width, height)
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // no-op
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        detachIfAttached()
        // Returning true lets the system release the SurfaceTexture.
        return true
    }

    companion object {
        private const val TAG = "mpv"
    }
}
