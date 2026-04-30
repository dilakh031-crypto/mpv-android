package `is`.xyz.mpv

/**
 * Compatibility layer for older in-tree zoom/original-resolution changes that
 * called setRenderSurfaceSize() with nullable video-params values, and in one
 * variant also passed the display aspect.
 *
 * BaseMPVView intentionally owns the real implementation as:
 *     setRenderSurfaceSize(width: Int, height: Int)
 *
 * These overloads only sanitize nullable mpv properties and then delegate to the
 * real two-Int implementation. The optional aspect argument is intentionally not
 * used by the current TextureView zoom path; aspect handling is performed by
 * VideoZoomGestures through contentRect()/setVideoAspect().
 */
internal fun BaseMPVView.setRenderSurfaceSize(width: Int?, height: Int?) {
    if (width == null || height == null || width <= 0 || height <= 0) {
        resetRenderSurfaceSize()
        return
    }

    setRenderSurfaceSize(width, height)
}

@Suppress("UNUSED_PARAMETER")
internal fun BaseMPVView.setRenderSurfaceSize(width: Int?, height: Int?, displayAspect: Double?) {
    setRenderSurfaceSize(width, height)
}
