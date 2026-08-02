package `is`.xyz.mpv

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Reads only media headers/metadata so the player can request the correct screen orientation
 * before mpv creates its first video surface. No bitmap or video frame is decoded.
 */
internal object MediaOrientationResolver {
    private const val ORIENTATION_ASPECT_THRESHOLD = 1.2

    enum class Orientation { LANDSCAPE, PORTRAIT, SQUARE, UNKNOWN }

    private data class Geometry(val width: Int, val height: Int, val rotation: Int = 0)

    fun resolve(context: Context, path: String): Orientation {
        if (!isLocallyReadablePath(path))
            return Orientation.UNKNOWN

        readWithMediaMetadataRetriever(context, path)?.let { return classify(it) }
        readImageGeometry(context, path)?.let { return classify(it) }
        return Orientation.UNKNOWN
    }

    private fun classify(geometry: Geometry): Orientation {
        var width = geometry.width
        var height = geometry.height

        val normalizedRotation = ((geometry.rotation % 360) + 360) % 360
        if (normalizedRotation == 90 || normalizedRotation == 270) {
            val tmp = width
            width = height
            height = tmp
        }
        return classifyDisplaySize(width, height)
    }

    /** Uses the same aspect threshold for the preflight probe and mpv's later geometry update. */
    fun classifyDisplaySize(width: Int, height: Int): Orientation {
        if (width <= 0 || height <= 0)
            return Orientation.UNKNOWN

        val ratio = width.toDouble() / height.toDouble()
        if (!ratio.isFinite() || ratio <= 0.0)
            return Orientation.UNKNOWN
        if (ratio in (1.0 / ORIENTATION_ASPECT_THRESHOLD)..ORIENTATION_ASPECT_THRESHOLD)
            return Orientation.SQUARE
        return if (ratio > 1.0) Orientation.LANDSCAPE else Orientation.PORTRAIT
    }

    private fun readWithMediaMetadataRetriever(context: Context, path: String): Geometry? {
        val retriever = MediaMetadataRetriever()
        return try {
            setRetrieverDataSource(retriever, context, path)

            val mime = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.lowercase(Locale.ROOT)
            val imageMedia = mime?.startsWith("image/") == true ||
                (mime.isNullOrBlank() && looksLikeImagePath(context, path))

            // Do not interpret embedded album art as the orientation of an audio file. For
            // image media, use the image-specific metadata so EXIF/container rotation is honored.
            if (!imageMedia) {
                val videoWidth = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                val videoHeight = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                if (videoWidth != null && videoHeight != null && videoWidth > 0 && videoHeight > 0) {
                    val rotation = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    return Geometry(videoWidth, videoHeight, rotation)
                }
            }

            if (imageMedia && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val imageWidth = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)
                    ?.toIntOrNull()
                val imageHeight = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT)
                    ?.toIntOrNull()
                if (imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0) {
                    val rotation = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_ROTATION)
                        ?.toIntOrNull() ?: 0
                    return Geometry(imageWidth, imageHeight, rotation)
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun setRetrieverDataSource(
        retriever: MediaMetadataRetriever,
        context: Context,
        path: String,
    ) {
        when (Uri.parse(path).scheme?.lowercase(Locale.ROOT)) {
            "content" -> retriever.setDataSource(context, Uri.parse(path))
            "file" -> {
                val filePath = Uri.parse(path).path
                    ?: throw IllegalArgumentException("Invalid file URI")
                retriever.setDataSource(filePath)
            }
            null -> retriever.setDataSource(path)
            else -> throw IllegalArgumentException("Non-local media URI")
        }
    }

    private fun readImageGeometry(context: Context, path: String): Geometry? {
        // Avoid opening known audio/video media as an image. Unknown content is still tried because
        // some document providers do not expose a MIME type or a useful filename extension.
        val declaredType = declaredMimeType(context, path)
        if (declaredType?.startsWith("audio/") == true || declaredType?.startsWith("video/") == true)
            return null
        if (declaredType == null && hasKnownNonImageExtension(path))
            return null

        // ExifInterface handles image metadata across the app's full minSdk range. Prefer its
        // stored dimensions because it also gives the display rotation.
        try {
            openInputStream(context, path)?.use { stream ->
                val exif = ExifInterface(stream)
                val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (width > 0 && height > 0)
                    return Geometry(width, height, exif.rotationDegrees)
            }
        } catch (_: Throwable) {
            // Fall through to a bounds-only read. Some valid images simply have no EXIF block.
        }

        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openInputStream(context, path)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0)
                Geometry(options.outWidth, options.outHeight)
            else
                null
        } catch (_: Throwable) {
            null
        }
    }

    private fun openInputStream(context: Context, path: String): InputStream? {
        return when (Uri.parse(path).scheme?.lowercase(Locale.ROOT)) {
            "content" -> context.contentResolver.openInputStream(Uri.parse(path))
            "file" -> Uri.parse(path).path?.let(::FileInputStream)
            null -> FileInputStream(path)
            else -> null
        }
    }

    private fun looksLikeImagePath(context: Context, path: String): Boolean {
        val declaredType = declaredMimeType(context, path)
        if (declaredType?.startsWith("image/") == true)
            return true
        return extension(path) in IMAGE_EXTENSIONS
    }

    private fun declaredMimeType(context: Context, path: String): String? {
        if (Uri.parse(path).scheme?.equals("content", ignoreCase = true) != true)
            return null
        return try {
            context.contentResolver.getType(Uri.parse(path))?.lowercase(Locale.ROOT)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasKnownNonImageExtension(path: String): Boolean {
        val ext = extension(path)
        return ext.isNotEmpty() && ext !in IMAGE_EXTENSIONS
    }

    private fun extension(path: String): String {
        val cleanPath = Uri.parse(path).lastPathSegment ?: path
        return cleanPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    private fun isLocallyReadablePath(path: String): Boolean {
        return when (Uri.parse(path).scheme?.lowercase(Locale.ROOT)) {
            null, "file", "content" -> true
            else -> false
        }
    }

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp",
        "heic", "heif", "avif", "dng", "tif", "tiff",
    )
}
