package `is`.xyz.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {
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

        holder.addCallback(this)
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable surface callbacks to avoid using uninitialized mpv state
        holder.removeCallback(this)
        surfaceHandler.removeCallbacks(disableVoRunnable)

        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()

    protected abstract fun observeProperties()

    // --- Surface lifecycle smoothing ---
    // SurfaceView gets destroyed/recreated during rotation. Fully disabling/re-enabling the VO
    // for those short gaps can cause visible stutter/freezes.
    // We detach the surface immediately, but delay shutting down the VO briefly.
    // If a new surface appears quickly (typical rotation), we cancel the shutdown.
    private val surfaceHandler = Handler(Looper.getMainLooper())
    private val disableVoRunnable = Runnable {
        try {
            MPVLib.setPropertyString("vo", "null")
        } catch (_: Throwable) {
            // ignore
        }
    }

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

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Rotation may recreate the surface quickly; cancel any pending VO shutdown.
        surfaceHandler.removeCallbacks(disableVoRunnable)

        Log.w(TAG, "attaching surface")
        MPVLib.attachSurface(holder.surface)
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        // Ensure video output is enabled (it may have been disabled if the surface was gone longer).
        try {
            MPVLib.setPropertyString("vo", voInUse)
        } catch (_: Throwable) {
            // ignore
        }

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")

        // Cancel any previous shutdown and schedule a new one.
        surfaceHandler.removeCallbacks(disableVoRunnable)

        // Stop rendering to a surface that's about to go away.
        try {
            MPVLib.setPropertyString("force-window", "no")
        } catch (_: Throwable) {
            // ignore
        }

        // Detach immediately so mpv doesn't keep a reference to the old surface.
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachSurface()

        // If the surface does not reappear quickly (e.g. app goes to background), shut down VO.
        surfaceHandler.postDelayed(disableVoRunnable, 250L)
    }

    companion object {
        private const val TAG = "mpv"
    }
}
