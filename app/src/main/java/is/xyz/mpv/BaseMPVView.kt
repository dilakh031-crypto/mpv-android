package `is`.xyz.mpv

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
    SurfaceView(context, attrs),
    SurfaceHolder.Callback2 {

    init {
        setZOrderOnTop(false)
        setZOrderMediaOverlay(false)
        holder.setFormat(PixelFormat.OPAQUE)
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

        holder.addCallback(this)
        if (holder.surface.isValid)
            attachSurface(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
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

    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var customRenderSurfaceSize = false

    var onSurfaceFrameAvailable: (() -> Unit)? = null

    /**
     * Set the real Surface buffer size used by mpv without changing the
     * SurfaceView's on-screen layout size.
     */
    fun setRenderSurfaceSize(width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        customRenderSurfaceSize = true

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight) {
            scheduleSurfaceFrameSignal()
            return
        }

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        applyRenderSurfaceSize()
    }

    fun resetRenderSurfaceSize() {
        customRenderSurfaceSize = false
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight) {
            holder.setSizeFromLayout()
            scheduleSurfaceFrameSignal()
            return
        }

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        holder.setSizeFromLayout()
        applyAndroidSurfaceSize()
        scheduleSurfaceFrameSignal()
    }

    private fun ensureRenderSurfaceSize(width: Int, height: Int) {
        if (customRenderSurfaceSize)
            return

        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)
    }

    private fun applyRenderSurfaceSize() {
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        holder.setFixedSize(renderSurfaceWidth, renderSurfaceHeight)
        applyAndroidSurfaceSize()
        scheduleSurfaceFrameSignal()
    }

    private fun applyAndroidSurfaceSize() {
        if (attachedSurface == null || renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        MPVLib.setPropertyString(
            "android-surface-size",
            "${renderSurfaceWidth}x${renderSurfaceHeight}",
        )
    }

    private fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        ensureRenderSurfaceSize(width, height)
        if (customRenderSurfaceSize)
            holder.setFixedSize(renderSurfaceWidth, renderSurfaceHeight)

        Log.w(TAG, "attaching surface ${renderSurfaceWidth}x${renderSurfaceHeight}")
        attachedSurface = surface

        MPVLib.attachSurface(surface)
        applyAndroidSurfaceSize()
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            MPVLib.setPropertyString("vo", voInUse)
        }

        scheduleSurfaceFrameSignal()
    }

    private fun detachSurface() {
        if (attachedSurface == null)
            return

        Log.w(TAG, "detaching surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachSurface()
        attachedSurface = null
    }

    private fun scheduleSurfaceFrameSignal() {
        postOnAnimation {
            postOnAnimation {
                if (attachedSurface != null)
                    onSurfaceFrameAvailable?.invoke()
            }
        }
    }

    // Surface callbacks

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurface(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ensureRenderSurfaceSize(width, height)
        applyAndroidSurfaceSize()
        scheduleSurfaceFrameSignal()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        onSurfaceFrameAvailable?.invoke()
    }

    companion object {
        private const val TAG = "mpv"
    }
}
