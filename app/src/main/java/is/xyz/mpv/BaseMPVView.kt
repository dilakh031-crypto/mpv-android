package `is`.xyz.mpv

import android.content.Context
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
        detachRenderSurface()

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
        if (surfaceAttached) {
            MPVLib.command(arrayOf("loadfile", filePath))
            this.filePath = null
        } else {
            this.filePath = filePath
        }
    }

    private var voInUse: String = "libmpv"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(@Suppress("UNUSED_PARAMETER") vo: String) {
        // Android owns the window Surface. mpv renders the filtered frame into
        // an app-owned off-screen OpenGL target through the libmpv render API.
        voInUse = "libmpv"
        MPVLib.setOptionString("vo", voInUse)
    }

    private var surfaceAttached = false
    var onRenderSurfaceAttached: (() -> Unit)? = null

    fun submitRenderState(
        renderWidth: Int,
        renderHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        scale: Float,
        translationX: Float,
        translationY: Float,
        fitScaleX: Float,
        fitScaleY: Float,
        fitTranslationX: Float,
        fitTranslationY: Float,
        geometrySerial: Long,
    ) {
        MPVLib.setRenderState(
            renderWidth,
            renderHeight,
            viewWidth,
            viewHeight,
            scale,
            translationX,
            translationY,
            fitScaleX,
            fitScaleY,
            fitTranslationX,
            fitTranslationY,
            geometrySerial,
        )
    }

    fun beginRenderTransaction() = MPVLib.beginRenderTransaction()
    fun endRenderTransaction() = MPVLib.endRenderTransaction()

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (surfaceAttached) {
            MPVLib.resizeRenderSurface(width.coerceAtLeast(1), height.coerceAtLeast(1))
        } else {
            // A just-created Surface can briefly report 0x0 on older devices.
            // Retry once Android supplies its real geometry.
            attachRenderSurface(holder, width, height)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachRenderSurface(holder, width, height)
    }

    private fun attachRenderSurface(
        holder: SurfaceHolder,
        requestedWidth: Int,
        requestedHeight: Int,
    ) {
        if (surfaceAttached)
            return

        val safeWidth = requestedWidth.coerceAtLeast(1)
        val safeHeight = requestedHeight.coerceAtLeast(1)
        Log.w(TAG, "attaching Android render surface ${safeWidth}x$safeHeight")
        if (!MPVLib.attachSurface(holder.surface, safeWidth, safeHeight)) {
            Log.e(TAG, "could not initialize the Android/libmpv render pipeline")
            return
        }
        surfaceAttached = true
        // Enable the VO and synchronize the app compositor as one publication
        // transaction. This lets the callback read current video-out-params,
        // while the worker is still forbidden from exposing an old transform.
        MPVLib.beginRenderTransaction()
        try {
            // The render context must exist before the libmpv VO is enabled.
            MPVLib.setPropertyString("vo", voInUse)
            // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
            MPVLib.setOptionString("force-window", "yes")
            onRenderSurfaceAttached?.invoke()
        } finally {
            MPVLib.endRenderTransaction()
        }

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachRenderSurface()
    }

    private fun detachRenderSurface() {
        if (!surfaceAttached)
            return

        Log.w(TAG, "detaching Android render surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // This synchronously stops both GL threads and frees mpv's render
        // context before Android invalidates the Surface.
        MPVLib.detachSurface()
        surfaceAttached = false
    }

    companion object {
        private const val TAG = "mpv"
    }
}
