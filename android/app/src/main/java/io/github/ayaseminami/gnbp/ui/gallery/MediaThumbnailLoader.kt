package io.github.ayaseminami.gnbp.ui.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.core.graphics.scale
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaThumbnailLoader(
    private val contentResolver: ContentResolver,
    private val maxDimension: Int = DEFAULT_MAX_DIMENSION,
) {
    init {
        require(maxDimension > 0) { "Maximum thumbnail dimension must be positive" }
    }

    suspend fun load(location: String): Bitmap? = withContext(Dispatchers.IO) {
        val uri = runCatching { location.toUri() }.getOrNull() ?: return@withContext null
        decodeBoundedThumbnail(
            open = { contentResolver.openInputStream(uri) },
            maxDimension = maxDimension,
        )
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 512
    }
}

internal fun decodeBoundedThumbnail(
    open: () -> InputStream?,
    maxDimension: Int,
): Bitmap? {
    require(maxDimension > 0) { "Maximum thumbnail dimension must be positive" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsDecoded = runCatching {
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        bounds.outWidth > 0 && bounds.outHeight > 0
    }.getOrDefault(false)
    if (!boundsDecoded) return null

    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= maxDimension ||
        bounds.outHeight / (sampleSize * 2) >= maxDimension
    ) {
        sampleSize *= 2
    }
    val decoded = runCatching {
        open()?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
    }.getOrNull() ?: return null
    if (decoded.width <= maxDimension && decoded.height <= maxDimension) return decoded

    val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = runCatching {
        decoded.scale(
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
        )
    }.getOrNull()
    if (scaled !== decoded) decoded.recycle()
    return scaled
}
