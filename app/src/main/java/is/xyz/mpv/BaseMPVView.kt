package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

// Contains only the essential code needed to get a picture on the screen.
//
// Keep this as a real SurfaceView. mpv renders directly into the window-sized
// Android Surface; zoom/pan are renderer properties and never Android View transforms.
abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

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
        holder.removeCallback(this)
        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    private var filePath: String? = null
    private var surfaceAttached = false

    /** Set the first/current file to be played once the player surface is ready. */
    fun playFile(filePath: String) {
        if (surfaceAttached) {
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

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // This is intentionally always the real SurfaceView size. Never substitute media
        // dimensions or a zoom-dependent buffer here: mpv must rasterize directly to display pixels.
        if (width > 0 && height > 0)
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface ${width}x${height}")
        MPVLib.attachSurface(holder.surface)
        surfaceAttached = true

        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not.
        MPVLib.setOptionString("force-window", "yes")

        // Give mpv the actual output size immediately. surfaceChanged() will update it again
        // if Android subsequently relayouts/rotates the SurfaceView.
        if (width > 0 && height > 0)
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")

        val pending = filePath
        if (pending != null) {
            MPVLib.command(arrayOf("loadfile", pending))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back.
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        surfaceAttached = false
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because setting a property
        // does not necessarily wait for VO deinit.
        MPVLib.detachSurface()
    }

    companion object {
        private const val TAG = "mpv"
    }
}
