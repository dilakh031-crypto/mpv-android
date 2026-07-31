package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.sqrt

// Contains only the essential code needed to get a picture on the screen.
//
// SurfaceView is deliberately used on Android 9. It gives the Exynos Note 8 a
// direct compositor surface, avoids the extra TextureView copy and preserves the
// platform's best available HDR/color path. Android N and newer synchronize
// SurfaceView transforms with the rest of the View hierarchy, so pinch zoom can
// still be implemented by scaling/translating this View.
abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
    SurfaceView(context, attrs), SurfaceHolder.Callback2 {

    init {
        holder.addCallback(this)
        holder.setKeepScreenOn(true)
        setZOrderOnTop(false)
        setZOrderMediaOverlay(false)
    }

    private var initialized = false

    /** Initialize libmpv. Call this once before the view is shown. */
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
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")

        initialized = true
        if (holder.surface.isValid)
            attachSurface(holder.surface, width, height)
        observeProperties()
    }

    /** Deinitialize libmpv. Call this once before the view is destroyed. */
    fun destroy() {
        initialized = false
        detachSurface()
        holder.removeCallback(this)
        surfaceSizeGeneration += 1
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

    /** Sets the VO to use. It is automatically disabled/enabled with the surface. */
    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    private var attachedSurface: Surface? = null
    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var customRenderSurfaceSize = false
    private var surfaceSizeGeneration = 0

    // Kept under the old property name so the surrounding activity does not need a
    // large mechanical change. With SurfaceView it is fired by Callback2 and by a
    // guarded fallback after a surface resize.
    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

    /**
     * Set the real Surface buffer size used by mpv without changing the on-screen
     * size. Requests are clamped defensively for the Galaxy Note 8 / Mali-G71 so a
     * malformed aspect ratio or huge scan cannot allocate an unsafe BufferQueue.
     */
    fun setRenderSurfaceSize(width: Int, height: Int) {
        val (safeWidth, safeHeight) = clampRenderSurfaceSize(width, height)
        customRenderSurfaceSize = true

        if (safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight)
            return

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        applyRenderSurfaceSize()
    }

    fun resetRenderSurfaceSize() {
        customRenderSurfaceSize = false
        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)

        if (holder.surface.isValid) {
            Log.d(TAG, "restoring surface size from layout")
            holder.setSizeFromLayout()
            updateMpvSurfaceSize(renderSurfaceWidth, renderSurfaceHeight)
            scheduleFrameReadyFallback()
        }
    }

    private fun clampRenderSurfaceSize(width: Int, height: Int): Pair<Int, Int> {
        val requestedWidth = width.coerceAtLeast(1).toDouble()
        val requestedHeight = height.coerceAtLeast(1).toDouble()
        val edgeScale = MAX_RENDER_SURFACE_EDGE / max(requestedWidth, requestedHeight)
        val pixelScale = sqrt(
            MAX_RENDER_SURFACE_PIXELS /
                (requestedWidth * requestedHeight).coerceAtLeast(1.0),
        )
        val scale = minOf(1.0, edgeScale, pixelScale)
        return (requestedWidth * scale).toInt().coerceAtLeast(1) to
            (requestedHeight * scale).toInt().coerceAtLeast(1)
    }

    private fun ensureRenderSurfaceSize(width: Int, height: Int) {
        if (customRenderSurfaceSize)
            return
        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)
    }

    private fun applyRenderSurfaceSize() {
        if (!holder.surface.isValid || renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        Log.d(TAG, "requesting fixed surface ${renderSurfaceWidth}x${renderSurfaceHeight}")
        holder.setFixedSize(renderSurfaceWidth, renderSurfaceHeight)
        updateMpvSurfaceSize(renderSurfaceWidth, renderSurfaceHeight)
        scheduleFrameReadyFallback()
    }

    private fun updateMpvSurfaceSize(width: Int, height: Int) {
        if (!initialized || attachedSurface == null)
            return
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    private fun attachSurface(surface: Surface, width: Int, height: Int) {
        if (!initialized || attachedSurface != null || !surface.isValid)
            return

        ensureRenderSurfaceSize(width, height)
        if (customRenderSurfaceSize)
            holder.setFixedSize(renderSurfaceWidth, renderSurfaceHeight)

        Log.w(TAG, "attaching surface ${renderSurfaceWidth}x${renderSurfaceHeight}")
        attachedSurface = surface
        MPVLib.attachSurface(surface)
        updateMpvSurfaceSize(renderSurfaceWidth, renderSurfaceHeight)
        MPVLib.setOptionString("force-window", "yes")

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            MPVLib.setPropertyString("vo", voInUse)
        }
        scheduleFrameReadyFallback()
    }

    private fun detachSurface() {
        if (attachedSurface == null)
            return

        Log.w(TAG, "detaching surface")
        surfaceSizeGeneration += 1
        try {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setPropertyString("force-window", "no")
            MPVLib.detachSurface()
        } catch (error: Throwable) {
            Log.w(TAG, "failed to detach mpv surface cleanly", error)
        } finally {
            // SurfaceView owns holder.surface; releasing it here races with the
            // compositor and was the source of the old detach/use-after-release risk.
            attachedSurface = null
        }
    }

    private fun scheduleFrameReadyFallback() {
        surfaceSizeGeneration += 1
        val generation = surfaceSizeGeneration
        postDelayed({
            if (generation == surfaceSizeGeneration && attachedSurface != null)
                onSurfaceTextureFrameAvailable?.invoke()
        }, FRAME_READY_FALLBACK_MS)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurface(holder.surface, width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!initialized)
            return
        if (attachedSurface == null)
            attachSurface(holder.surface, width, height)

        if (!customRenderSurfaceSize) {
            renderSurfaceWidth = width.coerceAtLeast(1)
            renderSurfaceHeight = height.coerceAtLeast(1)
        }
        updateMpvSurfaceSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        scheduleFrameReadyFallback()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        // Invalidate the delayed fallback so a real redraw does not produce a
        // second reveal callback shortly afterwards.
        surfaceSizeGeneration += 1
        onSurfaceTextureFrameAvailable?.invoke()
    }

    companion object {
        private const val TAG = "mpv"

        // Mali-G71 supports larger textures, but a 4096 edge and roughly 10 MP
        // keeps triple-buffered RGBA surfaces within a sane budget on the Note 8.
        private const val MAX_RENDER_SURFACE_EDGE = 4096.0
        private const val MAX_RENDER_SURFACE_PIXELS = 10_000_000.0
        private const val FRAME_READY_FALLBACK_MS = 120L
    }
}
