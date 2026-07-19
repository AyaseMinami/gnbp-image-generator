package io.github.ayaseminami.gnbp.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import io.github.ayaseminami.gnbp.provider.ReferenceImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class BoundedImagePreparer(
    private val maxDimension: Int = DEFAULT_MAX_DIMENSION,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    private val maxSourceBytes: Long = DEFAULT_MAX_SOURCE_BYTES,
    private val maxEncodedBytes: Int = DEFAULT_MAX_ENCODED_BYTES,
    private val openSource: (File) -> InputStream = ::FileInputStream,
) {
    init {
        require(maxDimension > 0) { "Maximum image dimension must be positive" }
        require(jpegQuality in 1..100) { "JPEG quality must be between 1 and 100" }
        require(maxSourceBytes > 0) { "Maximum source size must be positive" }
        require(maxEncodedBytes > 0) { "Maximum encoded size must be positive" }
    }

    fun prepare(
        file: File,
        displayName: String,
        sourceMimeType: String,
    ): ImagePreparationResult {
        if (!file.isFile) return ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
        if (file.length() > maxSourceBytes) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.TooLarge)
        }
        if (!sourceMimeType.startsWith("image/")) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.InvalidImage)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openSource(file).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
        } catch (_: IOException) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
        } catch (_: SecurityException) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
        } catch (_: IllegalArgumentException) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.InvalidImage)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.InvalidImage)
        }

        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = (largestDimension / maxDimension).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            openSource(file).use { input -> BitmapFactory.decodeStream(input, null, options) }
        } catch (_: IOException) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
        } catch (_: SecurityException) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
        } catch (_: IllegalArgumentException) {
            return ImagePreparationResult.Failed(ImagePreparationFailure.InvalidImage)
        } ?: return ImagePreparationResult.Failed(ImagePreparationFailure.InvalidImage)

        var prepared = decoded
        return try {
            val decodedLargest = maxOf(decoded.width, decoded.height)
            if (decodedLargest > maxDimension) {
                val scale = maxDimension.toDouble() / decodedLargest
                prepared = decoded.scale(
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    filter = true,
                )
            }
            val output = LimitedByteArrayOutputStream(maxEncodedBytes)
            val compressed = runCatching {
                prepared.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
            }.getOrDefault(false)
            if (!compressed || output.exceeded) {
                ImagePreparationResult.Failed(ImagePreparationFailure.EncodingFailed)
            } else {
                ImagePreparationResult.Prepared(
                    reference = ReferenceImage(
                        bytes = output.toByteArray(),
                        mimeType = OUTPUT_MIME_TYPE,
                        displayName = sanitizeDisplayName(displayName, DEFAULT_REFERENCE_NAME),
                    ),
                    width = prepared.width,
                    height = prepared.height,
                )
            }
        } finally {
            if (prepared !== decoded) prepared.recycle()
            decoded.recycle()
        }
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 1536
        const val DEFAULT_JPEG_QUALITY = 85
        const val OUTPUT_MIME_TYPE = "image/jpeg"
        private const val DEFAULT_MAX_SOURCE_BYTES = 64L * 1024L * 1024L
        private const val DEFAULT_MAX_ENCODED_BYTES = 16 * 1024 * 1024
        private const val DEFAULT_REFERENCE_NAME = "reference-image.jpg"
    }
}

private class LimitedByteArrayOutputStream(
    private val limit: Int,
) : OutputStream() {
    private val delegate = ByteArrayOutputStream(minOf(limit, INITIAL_CAPACITY))
    var exceeded: Boolean = false
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        if (!exceeded) delegate.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        if (!exceeded) delegate.write(bytes, offset, length)
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun ensureCapacity(additionalBytes: Int) {
        if (delegate.size() > limit - additionalBytes) {
            exceeded = true
            throw ImageSizeLimitException()
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024 * 1024
    }
}

private class ImageSizeLimitException : RuntimeException()

internal fun sanitizeDisplayName(value: String?, fallback: String): String {
    val leaf = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val sanitized = leaf.map { character ->
        if (character.isLetterOrDigit() || character in ".-_ ") character else '_'
    }.joinToString("").trim().take(120)
    return sanitized.ifBlank { fallback }
}
