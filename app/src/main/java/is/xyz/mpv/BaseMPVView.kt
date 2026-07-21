package `is`.xyz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.File
import kotlin.math.abs

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
        MPVLib.create(context)

        /* set normal options (user-supplied config can override) */
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            MPVLib.setOptionString(opt, cacheDir)
        initOptions()

        MPVLib.init()
        mpvInitialized = true
        captureConfiguredOsdSizes()
        applyAdaptiveOsdScale(force = true)

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
        surfaceTextureListener = null
        detachSurfaceTexture()

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

    private var renderSurfaceWidth = 0
    private var renderSurfaceHeight = 0
    private var customRenderSurfaceSize = false

    // mpv scales its regular OSD with the output height. That is a good default
    // for a normal window, but a tall, narrow video can still have the full
    // display height while providing only a small fraction of its width. Keep a
    // separate multiplier so callers can compensate for that geometry without
    // discarding the user's configured OSD size.
    private var mpvInitialized = false
    private var configuredOsdScale = 1.0
    private var configuredStatsFontSize = DEFAULT_STATS_FONT_SIZE
    private var configuredStatsFontSizeScriptOpt: String? = null
    private var requestedAdaptiveOsdScale = 1.0
    private var appliedAdaptiveOsdScale = Double.NaN
    private var statsFontSizeOverridden = false

    var onSurfaceTextureFrameAvailable: (() -> Unit)? = null

    /**
     * Shrink mpv-owned text to fit the visible video rectangle.
     *
     * [scale] is deliberately capped at 1: sufficiently large videos keep the
     * exact OSD size selected by mpv.conf. The built-in stats overlay supplies
     * its own ASS font size, so it is adjusted alongside the regular OSD.
     */
    fun setAdaptiveOsdScale(scale: Double) {
        requestedAdaptiveOsdScale = if (scale.isFinite())
            scale.coerceIn(MIN_ADAPTIVE_OSD_SCALE, 1.0)
        else
            1.0

        if (mpvInitialized)
            applyAdaptiveOsdScale()
    }

    private fun captureConfiguredOsdSizes() {
        configuredOsdScale = MPVLib.getPropertyDouble("osd-scale")
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: 1.0

        configuredStatsFontSizeScriptOpt = MPVLib.getPropertyString("script-opts")
            ?.let { STATS_FONT_SIZE_SCRIPT_OPT.find(it)?.groupValues?.getOrNull(1) }

        configuredStatsFontSize = configuredStatsFontSizeScriptOpt
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: readStatsFontSizeFromConfig()
            ?: DEFAULT_STATS_FONT_SIZE
    }

    private fun readStatsFontSizeFromConfig(): Double? {
        val config = File(context.filesDir, "script-opts/stats.conf")
        if (!config.isFile)
            return null

        return try {
            config.useLines { lines ->
                lines.mapNotNull { line ->
                    val setting = line.substringBefore('#').trim()
                    val separator = setting.indexOf('=')
                    if (separator <= 0 || setting.substring(0, separator).trim() != "font_size")
                        return@mapNotNull null

                    setting.substring(separator + 1).trim()
                        .toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it > 0.0 }
                }.lastOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyAdaptiveOsdScale(force: Boolean = false) {
        if (!mpvInitialized)
            return

        val scale = requestedAdaptiveOsdScale
        if (!force && appliedAdaptiveOsdScale.isFinite() &&
            abs(appliedAdaptiveOsdScale - scale) < OSD_SCALE_EPSILON
        ) return

        appliedAdaptiveOsdScale = scale
        MPVLib.setPropertyDouble("osd-scale", configuredOsdScale * scale)

        // stats.lua renders explicit ASS sizes, which do not inherit osd-scale.
        // script-opts is a key/value list, so appending the same key atomically
        // replaces its previous value and asks the live stats script to redraw.
        if (scale < 1.0 - OSD_SCALE_EPSILON) {
            val scaledFontSize = configuredStatsFontSize * scale
            MPVLib.command(arrayOf(
                "change-list",
                "script-opts",
                "append",
                "stats-font_size=${formatMpvNumber(scaledFontSize)}"
            ))
            statsFontSizeOverridden = true
        } else if (statsFontSizeOverridden) {
            val original = configuredStatsFontSizeScriptOpt
            MPVLib.command(arrayOf(
                "change-list",
                "script-opts",
                if (original == null) "remove" else "append",
                if (original == null) "stats-font_size" else "stats-font_size=$original"
            ))
            statsFontSizeOverridden = false
        }
    }

    private fun formatMpvNumber(value: Double): String {
        return String.format(java.util.Locale.US, "%.6f", value)
            .trimEnd('0')
            .trimEnd('.')
    }

    /**
     * Set the real SurfaceTexture buffer size used by mpv without changing the
     * TextureView's on-screen size.
     *
     * This intentionally accepts the requested size as-is. The caller decides the
     * size, so high-resolution media can be rendered at its original resolution
     * instead of being reduced to the display resolution before Android zooms it.
     */
    fun setRenderSurfaceSize(width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
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

        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

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

        if (filePath != null) {
            MPVLib.command(arrayOf("loadfile", filePath as String))
            filePath = null
        } else {
            // We disable video output when the context disappears, enable it back
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    private fun detachSurfaceTexture() {
        val surface = attachedSurface ?: return

        Log.w(TAG, "detaching texture surface")
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachSurface()
        surface.release()
        attachedSurface = null
        attachedTexture = null
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        ensureRenderSurfaceSize(width, height)
        applyRenderSurfaceSize()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        onSurfaceTextureFrameAvailable?.invoke()
    }

    companion object {
        private const val TAG = "mpv"
        private const val DEFAULT_STATS_FONT_SIZE = 20.0
        private const val MIN_ADAPTIVE_OSD_SCALE = 0.001
        private const val OSD_SCALE_EPSILON = 0.0001
        private val STATS_FONT_SIZE_SCRIPT_OPT =
            Regex("(?:^|,)stats-font_size=([^,]+)")
    }
}
