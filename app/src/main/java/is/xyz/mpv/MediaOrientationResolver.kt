package `is`.xyz.mpv

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.net.URLConnection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Lightweight media-orientation probe used before mpv produces its first frame.
 *
 * Local files and content URIs are inspected through Android's container extractor first. This is
 * normally much faster than MediaMetadataRetriever for large HEVC/Dolby Vision/Matroska files and
 * avoids opening the player in the caller's orientation while metadata is still being discovered.
 * Network streams and synthetic mpv URLs are deliberately left to mpv's runtime callbacks.
 */
internal object MediaOrientationResolver {
    enum class Orientation {
        LANDSCAPE,
        PORTRAIT,
        SQUARE,
        UNKNOWN,
    }

    fun canResolve(path: String): Boolean = isProbeablePath(path)

    fun resolve(context: Context, path: String): Orientation {
        if (!canResolve(path))
            return Orientation.UNKNOWN

        val mimeType = detectMimeType(context, path)
        return when {
            mimeType?.startsWith("image/", ignoreCase = true) == true -> {
                resolveImage(context, path).takeIf(::isKnown)
                    ?: resolveVideo(context, path)
            }

            mimeType?.startsWith("video/", ignoreCase = true) == true -> {
                resolveVideo(context, path).takeIf(::isKnown)
                    ?: resolveImage(context, path)
            }

            looksLikeImage(path) -> {
                resolveImage(context, path).takeIf(::isKnown)
                    ?: resolveVideo(context, path)
            }

            else -> {
                // Unknown MIME types are common with SAF/document providers. Try the container
                // extractor first: it obtains video dimensions without decoding a frame and also
                // rejects ordinary images quickly. This prevents BitmapFactory or retriever setup
                // from consuming the entire launch budget before video metadata is reached.
                resolveVideo(context, path).takeIf(::isKnown)
                    ?: resolveImage(context, path)
            }
        }
    }

    /**
     * Classifies every genuinely non-square geometry. A previous 1.2 threshold treated ratios
     * such as 1.1:1 as square even though the requested behavior is landscape for any width >
     * height and portrait for any height > width.
     */
    fun classify(
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
    ): Orientation = classifyScaled(
        width = width.toDouble(),
        height = height.toDouble(),
        rotationDegrees = rotationDegrees,
    )

    private fun classifyScaled(
        width: Double,
        height: Double,
        rotationDegrees: Int = 0,
        sampleAspectWidth: Int = 1,
        sampleAspectHeight: Int = 1,
    ): Orientation {
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0)
            return Orientation.UNKNOWN

        val safeSarWidth = sampleAspectWidth.takeIf { it > 0 } ?: 1
        val safeSarHeight = sampleAspectHeight.takeIf { it > 0 } ?: 1
        var displayWidth = width * safeSarWidth.toDouble()
        var displayHeight = height * safeSarHeight.toDouble()

        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation == 90 || rotation == 270) {
            val oldWidth = displayWidth
            displayWidth = displayHeight
            displayHeight = oldWidth
        }

        val largest = max(displayWidth, displayHeight)
        if (largest <= 0.0)
            return Orientation.UNKNOWN

        // Only numerical noise is considered square. Even a slightly rectangular file follows
        // its real orientation, as requested.
        if (abs(displayWidth - displayHeight) / largest <= SQUARE_EPSILON)
            return Orientation.SQUARE
        return if (displayWidth > displayHeight) Orientation.LANDSCAPE else Orientation.PORTRAIT
    }

    private fun isKnown(orientation: Orientation): Boolean = orientation != Orientation.UNKNOWN

    private fun isProbeablePath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("content://") -> true
            lower.startsWith("file://") -> true
            !lower.contains("://") -> true
            else -> false
        }
    }

    private fun detectMimeType(context: Context, path: String): String? {
        return try {
            if (path.startsWith("content://", ignoreCase = true)) {
                val resolver = context.contentResolver
                val uri = Uri.parse(path)
                resolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                    ?: queryDisplayName(resolver, uri)?.let(URLConnection::guessContentTypeFromName)
                    ?: URLConnection.guessContentTypeFromName(uri.lastPathSegment)
            } else {
                URLConnection.guessContentTypeFromName(path.substringBefore('?').substringBefore('#'))
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun looksLikeImage(path: String): Boolean {
        val clean = path.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return IMAGE_EXTENSIONS.any(clean::endsWith)
    }

    private fun queryDisplayName(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst())
                    return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveVideo(context: Context, path: String): Orientation {
        resolveVideoWithExtractor(context, path).takeIf(::isKnown)?.let { return it }
        return resolveVideoWithRetriever(context, path)
    }

    /**
     * Reads only the container's video-track format. No frame is decoded.
     */
    private fun resolveVideoWithExtractor(context: Context, path: String): Orientation {
        return when {
            path.startsWith("content://", ignoreCase = true) -> {
                val uri = Uri.parse(path)
                resolveVideoWithExtractorDescriptor(context, uri).takeIf(::isKnown)
                    ?: resolveWithExtractor { extractor ->
                        extractor.setDataSource(context, uri, emptyMap())
                    }
            }

            path.startsWith("file://", ignoreCase = true) -> {
                val filePath = Uri.parse(path).path ?: return Orientation.UNKNOWN
                resolveWithExtractor { extractor -> extractor.setDataSource(filePath) }
            }

            else -> resolveWithExtractor { extractor -> extractor.setDataSource(path) }
        }
    }

    private fun resolveVideoWithExtractorDescriptor(context: Context, uri: Uri): Orientation {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                val length = descriptor.declaredLength
                if (length < 0L)
                    return@use Orientation.UNKNOWN

                resolveWithExtractor { extractor ->
                    extractor.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        length,
                    )
                }
            } ?: Orientation.UNKNOWN
        } catch (_: Throwable) {
            Orientation.UNKNOWN
        }
    }

    private inline fun resolveWithExtractor(
        configure: (MediaExtractor) -> Unit,
    ): Orientation {
        val extractor = MediaExtractor()
        return try {
            configure(extractor)
            readExtractorOrientation(extractor)
        } catch (_: Throwable) {
            Orientation.UNKNOWN
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
                // Ignore vendor-specific release failures.
            }
        }
    }

    private fun readExtractorOrientation(extractor: MediaExtractor): Orientation {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.stringOrNull(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/", ignoreCase = true))
                continue

            val codedWidth = format.positiveIntOrNull(MediaFormat.KEY_WIDTH) ?: continue
            val codedHeight = format.positiveIntOrNull(MediaFormat.KEY_HEIGHT) ?: continue
            val width = format.croppedDimension(
                fallback = codedWidth,
                startKey = "crop-left",
                endKey = "crop-right",
            )
            val height = format.croppedDimension(
                fallback = codedHeight,
                startKey = "crop-top",
                endKey = "crop-bottom",
            )
            val rotation = format.intOrNull(MediaFormat.KEY_ROTATION)
                ?: format.intOrNull("rotation-degrees")
                ?: 0
            val sarWidth = format.positiveIntOrNull("sar-width") ?: 1
            val sarHeight = format.positiveIntOrNull("sar-height") ?: 1

            return classifyScaled(
                width = width.toDouble(),
                height = height.toDouble(),
                rotationDegrees = rotation,
                sampleAspectWidth = sarWidth,
                sampleAspectHeight = sarHeight,
            )
        }
        return Orientation.UNKNOWN
    }

    private fun resolveVideoWithRetriever(context: Context, path: String): Orientation {
        return when {
            path.startsWith("content://", ignoreCase = true) -> {
                val uri = Uri.parse(path)
                resolveVideoWithRetrieverDescriptor(context, uri).takeIf(::isKnown)
                    ?: resolveWithRetriever { retriever -> retriever.setDataSource(context, uri) }
            }

            path.startsWith("file://", ignoreCase = true) -> {
                val filePath = Uri.parse(path).path ?: return Orientation.UNKNOWN
                resolveWithRetriever { retriever -> retriever.setDataSource(filePath) }
            }

            else -> resolveWithRetriever { retriever -> retriever.setDataSource(path) }
        }
    }

    private fun resolveVideoWithRetrieverDescriptor(context: Context, uri: Uri): Orientation {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                val length = descriptor.declaredLength
                if (length < 0L)
                    return@use Orientation.UNKNOWN

                resolveWithRetriever { retriever ->
                    retriever.setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        length,
                    )
                }
            } ?: Orientation.UNKNOWN
        } catch (_: Throwable) {
            Orientation.UNKNOWN
        }
    }

    private inline fun resolveWithRetriever(
        configure: (MediaMetadataRetriever) -> Unit,
    ): Orientation {
        val retriever = MediaMetadataRetriever()
        return try {
            configure(retriever)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return Orientation.UNKNOWN
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return Orientation.UNKNOWN
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            classify(width, height, rotation)
        } catch (_: Throwable) {
            Orientation.UNKNOWN
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // Ignore vendor-specific release failures.
            }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? {
        return try {
            if (containsKey(key)) getInteger(key) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun MediaFormat.positiveIntOrNull(key: String): Int? =
        intOrNull(key)?.takeIf { it > 0 }

    private fun MediaFormat.stringOrNull(key: String): String? {
        return try {
            if (containsKey(key)) getString(key) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun MediaFormat.croppedDimension(
        fallback: Int,
        startKey: String,
        endKey: String,
    ): Int {
        val start = intOrNull(startKey) ?: return fallback
        val end = intOrNull(endKey) ?: return fallback
        return (end - start + 1).takeIf { it > 0 } ?: fallback
    }

    private fun resolveImage(context: Context, path: String): Orientation {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            when {
                path.startsWith("content://", ignoreCase = true) -> {
                    context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                        BitmapFactory.decodeStream(input, null, bounds)
                    } ?: return Orientation.UNKNOWN
                }

                path.startsWith("file://", ignoreCase = true) -> {
                    val filePath = Uri.parse(path).path ?: return Orientation.UNKNOWN
                    BitmapFactory.decodeFile(filePath, bounds)
                }

                path.contains("://") -> return Orientation.UNKNOWN
                else -> BitmapFactory.decodeFile(path, bounds)
            }
        } catch (_: Throwable) {
            return Orientation.UNKNOWN
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            return Orientation.UNKNOWN

        return classify(bounds.outWidth, bounds.outHeight, readImageRotation(context, path))
    }

    private fun readImageRotation(context: Context, path: String): Int {
        val exifOrientation = try {
            when {
                path.startsWith("content://", ignoreCase = true) ->
                    readContentExifOrientation(context, Uri.parse(path))

                path.startsWith("file://", ignoreCase = true) -> {
                    val filePath = Uri.parse(path).path ?: return 0
                    readFileExifOrientation(filePath)
                }

                path.contains("://") -> ExifInterface.ORIENTATION_UNDEFINED
                else -> readFileExifOrientation(path)
            }
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_UNDEFINED
        }

        return when (exifOrientation) {
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90 -> 90

            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270 -> 270

            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            else -> 0
        }
    }

    private fun readFileExifOrientation(path: String): Int {
        if (!File(path).isFile)
            return ExifInterface.ORIENTATION_UNDEFINED
        return ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )
    }

    @Suppress("DEPRECATION")
    private fun readContentExifOrientation(context: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return ExifInterface.ORIENTATION_UNDEFINED

        return context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        } ?: ExifInterface.ORIENTATION_UNDEFINED
    }

    private const val SQUARE_EPSILON = 0.0001

    private val IMAGE_EXTENSIONS = arrayOf(
        ".avif", ".bmp", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".png",
        ".webp", ".dng", ".tif", ".tiff",
    )
}
