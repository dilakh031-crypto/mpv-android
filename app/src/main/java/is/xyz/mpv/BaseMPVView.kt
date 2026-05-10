package `is`.xyz.mpv

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

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
    private var textureTransformFrameCountdown = 0
    private var textureTransformFallback: Runnable? = null
    private var pendingTextureTransformReady: (() -> Unit)? = null
    private var textureTransformGeneration = 0

    /**
     * Set the real SurfaceTexture buffer size used by mpv without changing the
     * TextureView's on-screen size.
     *
     * When this size has a different aspect ratio from the view, the TextureView
     * content is fitted with a transform instead of stretching the video. This lets
     * zoomed playback use the media's own pixel dimensions directly, without adding
     * huge black-bar padding to the render surface.
     */
    fun setRenderSurfaceSize(width: Int, height: Int, onReady: (() -> Unit)? = null) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val modeChanged = !customRenderSurfaceSize
        customRenderSurfaceSize = true

        if (!modeChanged && safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight) {
            if (textureTransformFrameCountdown > 0)
                replacePendingTextureTransformCallback(onReady)
            else {
                applyTextureTransform()
                onReady?.invoke()
            }
            return
        }

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        applyRenderSurfaceSize(waitForNextFrames = true, onReady = onReady)
    }

    fun resetRenderSurfaceSize(onReady: (() -> Unit)? = null) {
        val modeChanged = customRenderSurfaceSize
        customRenderSurfaceSize = false
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)

        if (!modeChanged && safeWidth == renderSurfaceWidth && safeHeight == renderSurfaceHeight) {
            if (textureTransformFrameCountdown > 0)
                replacePendingTextureTransformCallback(onReady)
            else {
                applyTextureTransform()
                onReady?.invoke()
            }
            return
        }

        renderSurfaceWidth = safeWidth
        renderSurfaceHeight = safeHeight
        applyRenderSurfaceSize(waitForNextFrames = true, onReady = onReady)
    }

    private fun ensureRenderSurfaceSize(width: Int, height: Int) {
        if (customRenderSurfaceSize)
            return

        renderSurfaceWidth = width.coerceAtLeast(1)
        renderSurfaceHeight = height.coerceAtLeast(1)
    }

    private fun applyRenderSurfaceSize(
        waitForNextFrames: Boolean = false,
        onReady: (() -> Unit)? = null,
    ) {
        val texture = attachedTexture ?: run {
            onReady?.invoke()
            return
        }
        if (renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0) {
            onReady?.invoke()
            return
        }

        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)
        MPVLib.setPropertyString("android-surface-size", "${renderSurfaceWidth}x${renderSurfaceHeight}")

        if (waitForNextFrames) {
            // setDefaultBufferSize/android-surface-size is asynchronous. While a
            // video is playing, TextureView can still present one or two queued
            // frames from the old buffer after the request. Applying the new
            // matrix or resetting it during those queued frames is what causes
            // the one-frame tear when entering or leaving zoom. Wait for a small
            // number of texture updates before switching the matrix, with a
            // timeout fallback for paused videos/images that may only redraw once.
            beginDeferredTextureTransform(onReady)
        } else {
            if (textureTransformFrameCountdown > 0)
                finishDeferredTextureTransform()
            else
                applyTextureTransform()
            onReady?.invoke()
        }
    }

    private fun beginDeferredTextureTransform(onReady: (() -> Unit)?) {
        textureTransformGeneration += 1
        val generation = textureTransformGeneration
        textureTransformFrameCountdown = TEXTURE_TRANSFORM_UPDATE_DELAY
        pendingTextureTransformReady = onReady

        textureTransformFallback?.let { removeCallbacks(it) }
        val fallback = Runnable {
            if (generation == textureTransformGeneration && textureTransformFrameCountdown > 0)
                finishDeferredTextureTransform()
        }
        textureTransformFallback = fallback
        postDelayed(fallback, TEXTURE_TRANSFORM_FALLBACK_MS)
    }

    private fun replacePendingTextureTransformCallback(onReady: (() -> Unit)?) {
        if (onReady != null)
            pendingTextureTransformReady = onReady
    }

    private fun finishDeferredTextureTransform(cancelOnly: Boolean = false) {
        textureTransformFrameCountdown = 0
        textureTransformFallback?.let { removeCallbacks(it) }
        textureTransformFallback = null

        if (cancelOnly) {
            pendingTextureTransformReady = null
            return
        }

        applyTextureTransform()
        val callback = pendingTextureTransformReady
        pendingTextureTransformReady = null
        callback?.invoke()
    }

    private fun applyTextureTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 1f || viewHeight <= 1f || renderSurfaceWidth <= 0 || renderSurfaceHeight <= 0)
            return

        if (!customRenderSurfaceSize) {
            setTransform(null)
            invalidate()
            return
        }

        val bufferAspect = renderSurfaceWidth.toFloat() / renderSurfaceHeight.toFloat()
        val viewAspect = viewWidth / viewHeight
        val scaleX: Float
        val scaleY: Float

        if (bufferAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / bufferAspect
        } else {
            scaleX = bufferAspect / viewAspect
            scaleY = 1f
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth * 0.5f, viewHeight * 0.5f)
        setTransform(matrix)
        invalidate()
    }

    private fun attachSurfaceTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (attachedSurface != null)
            return

        attachedTexture = texture
        ensureRenderSurfaceSize(width, height)
        texture.setDefaultBufferSize(renderSurfaceWidth, renderSurfaceHeight)
        applyTextureTransform()

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
        finishDeferredTextureTransform(cancelOnly = true)
    }

    // Texture callbacks

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attachSurfaceTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        ensureRenderSurfaceSize(width, height)
        applyRenderSurfaceSize()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (!customRenderSurfaceSize)
            ensureRenderSurfaceSize(width, height)
        applyRenderSurfaceSize()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSurfaceTexture()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (textureTransformFrameCountdown > 0) {
            textureTransformFrameCountdown -= 1
            if (textureTransformFrameCountdown <= 0)
                finishDeferredTextureTransform()
        }
    }

    companion object {
        private const val TAG = "mpv"
        private const val TEXTURE_TRANSFORM_UPDATE_DELAY = 2
        private const val TEXTURE_TRANSFORM_FALLBACK_MS = 96L
    }
}
