package io.github.ayaseminami.gnbp.media

import android.Manifest
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface GeneratedAssetStore {
    suspend fun save(
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): AssetSaveResult

    suspend fun read(asset: AssetRef): AssetReadResult

    suspend fun checkReadable(asset: AssetRef): AssetAccessResult

    suspend fun delete(asset: AssetRef): AssetDeleteResult
}

sealed interface AssetAccessResult {
    data object Available : AssetAccessResult

    data object Missing : AssetAccessResult

    data object PermissionDenied : AssetAccessResult

    data object Failed : AssetAccessResult
}

sealed interface AssetDeleteResult {
    data object Deleted : AssetDeleteResult

    data object Missing : AssetDeleteResult

    data object PermissionDenied : AssetDeleteResult

    data object Failed : AssetDeleteResult
}

interface MediaStoreGateway {
    fun insert(values: ContentValues): Uri?

    fun openOutput(uri: Uri): OutputStream?

    fun publish(uri: Uri): Boolean

    fun openInput(uri: Uri): InputStream?

    fun delete(uri: Uri): Boolean
}

class ContentResolverMediaStoreGateway(
    private val contentResolver: ContentResolver,
) : MediaStoreGateway {
    override fun insert(values: ContentValues): Uri? =
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

    override fun openOutput(uri: Uri): OutputStream? = contentResolver.openOutputStream(uri)

    override fun publish(uri: Uri): Boolean {
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        return contentResolver.update(uri, values, null, null) > 0
    }

    override fun openInput(uri: Uri): InputStream? = contentResolver.openInputStream(uri)

    override fun delete(uri: Uri): Boolean = contentResolver.delete(uri, null, null) > 0
}

class MediaStoreGeneratedAssetStore(
    private val gateway: MediaStoreGateway,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val hasLegacyWritePermission: () -> Boolean,
    private val legacyPicturesDirectory: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : GeneratedAssetStore {
    override suspend fun save(
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): AssetSaveResult = withContext(Dispatchers.IO) {
        image.validateForStorage()?.let { failure ->
            return@withContext AssetSaveResult.Failed(failure)
        }
        if (sdkInt < Build.VERSION_CODES.Q && !hasLegacyWritePermission()) {
            return@withContext AssetSaveResult.Failed(AssetSaveFailure.PermissionRequired)
        }

        val id = MediaAssetId(idGenerator())
        val extension = if (image.mimeType == "image/png") "png" else "jpg"
        val displayName = "gnbp_${nowEpochMillis()}_${id.value}.$extension"
        val values = if (sdkInt >= Build.VERSION_CODES.Q) {
            scopedValues(displayName, image.mimeType, metadata)
        } else {
            legacyValues(displayName, image.mimeType, metadata)
                ?: return@withContext AssetSaveResult.Failed(
                    AssetSaveFailure.StorageUnavailable,
                )
        }

        val uri = try {
            gateway.insert(values)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return@withContext AssetSaveResult.Failed(AssetSaveFailure.StorageUnavailable)

        val written = try {
            gateway.openOutput(uri)?.use { output ->
                output.write(image.bytes)
                output.flush()
            } != null
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        }
        val published = written && (
            sdkInt < Build.VERSION_CODES.Q || runCatching { gateway.publish(uri) }.getOrDefault(false)
        )
        if (!published) {
            runCatching { gateway.delete(uri) }
            return@withContext AssetSaveResult.Failed(AssetSaveFailure.WriteFailed)
        }

        AssetSaveResult.Saved(
            AssetRef(
                id = id,
                uri = uri,
                displayName = displayName,
                mimeType = image.mimeType,
                byteSize = image.bytes.size.toLong(),
            ),
        )
    }

    override suspend fun read(asset: AssetRef): AssetReadResult = withContext(Dispatchers.IO) {
        val input = try {
            gateway.openInput(asset.uri)
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } ?: return@withContext AssetReadResult.ExternalAssetMissing
        try {
            input.use { stream ->
                AssetReadResult.Opened(stream.readBounded(MAX_GENERATED_BYTES))
            }
        } catch (_: ImageReadLimitException) {
            AssetReadResult.ReadFailed
        } catch (_: IOException) {
            AssetReadResult.ReadFailed
        }
    }

    override suspend fun checkReadable(asset: AssetRef): AssetAccessResult = withContext(Dispatchers.IO) {
        val input = try {
            gateway.openInput(asset.uri)
        } catch (_: SecurityException) {
            return@withContext AssetAccessResult.PermissionDenied
        } catch (_: Exception) {
            return@withContext AssetAccessResult.Failed
        } ?: return@withContext AssetAccessResult.Missing
        try {
            input.close()
            AssetAccessResult.Available
        } catch (_: IOException) {
            AssetAccessResult.Failed
        }
    }

    override suspend fun delete(asset: AssetRef): AssetDeleteResult = withContext(Dispatchers.IO) {
        val deleted = try {
            gateway.delete(asset.uri)
        } catch (_: SecurityException) {
            return@withContext AssetDeleteResult.PermissionDenied
        } catch (_: Exception) {
            return@withContext AssetDeleteResult.Failed
        }
        if (deleted) AssetDeleteResult.Deleted else AssetDeleteResult.Missing
    }

    private fun scopedValues(
        displayName: String,
        mimeType: String,
        metadata: GeneratedAssetMetadata,
    ): ContentValues = baseValues(displayName, mimeType, metadata).apply {
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$OUTPUT_DIRECTORY")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    private fun legacyValues(
        displayName: String,
        mimeType: String,
        metadata: GeneratedAssetMetadata,
    ): ContentValues? {
        val outputDirectory = File(legacyPicturesDirectory, OUTPUT_DIRECTORY)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) return null
        return baseValues(displayName, mimeType, metadata).apply {
            @Suppress("DEPRECATION")
            put(MediaStore.Images.Media.DATA, File(outputDirectory, displayName).absolutePath)
        }
    }

    private fun baseValues(
        displayName: String,
        mimeType: String,
        metadata: GeneratedAssetMetadata,
    ): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.DATE_ADDED, nowEpochMillis() / 1000L)
        put(MediaStore.Images.Media.DESCRIPTION, metadata.toDescription())
    }

    companion object {
        private const val OUTPUT_DIRECTORY = "GNBP"

        fun create(context: Context): MediaStoreGeneratedAssetStore {
            val applicationContext = context.applicationContext
            @Suppress("DEPRECATION")
            val picturesDirectory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES,
            )
            return MediaStoreGeneratedAssetStore(
                gateway = ContentResolverMediaStoreGateway(applicationContext.contentResolver),
                hasLegacyWritePermission = {
                    ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                },
                legacyPicturesDirectory = picturesDirectory,
            )
        }
    }
}

fun AssetRef.shareIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
    type = mimeType
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

fun List<AssetRef>.shareIntent(): Intent {
    require(isNotEmpty()) { "At least one generated asset is required for sharing" }
    val uris = mapTo(ArrayList(size), AssetRef::uri)
    val sharedMimeType = map(AssetRef::mimeType).distinct().singleOrNull() ?: "image/*"
    return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = sharedMimeType
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri("generated images", uris.first()).apply {
            uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun AssetRef.previewIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Serializable
private data class MetadataDescription(
    val provider: String,
    val model: String,
    val parameters: Map<String, String>,
    val referenceImages: List<String>,
)

private fun GeneratedAssetMetadata.toDescription(): String {
    val payload = MetadataDescription(
        provider = provider.safeMetadataValue(),
        model = model.safeMetadataValue(),
        parameters = parameters
            .filterKeys(SAFE_PARAMETER_NAMES::contains)
            .mapValues { (_, value) -> value.safeMetadataValue() },
        referenceImages = referenceDisplayNames.take(MAX_REFERENCE_NAMES).map { name ->
            sanitizeDisplayName(name, "reference-image").take(MAX_METADATA_VALUE_CHARS)
        },
    )
    val encoded = Json.encodeToString(payload)
    return if (encoded.length <= MAX_DESCRIPTION_CHARS) {
        encoded
    } else {
        Json.encodeToString(payload.copy(parameters = emptyMap(), referenceImages = emptyList()))
    }
}

private fun String.safeMetadataValue(): String =
    filterNot(Char::isISOControl).take(MAX_METADATA_VALUE_CHARS)

private fun InputStream.readBounded(limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        total += count
        if (total > limit) throw ImageReadLimitException()
        output.write(buffer, 0, count)
    }
}

private class ImageReadLimitException : RuntimeException()

private const val MAX_DESCRIPTION_CHARS = 4_000
private const val MAX_METADATA_VALUE_CHARS = 120
private const val MAX_REFERENCE_NAMES = 8
private val SAFE_PARAMETER_NAMES = setOf(
    "aspect_ratio",
    "image_size",
    "quality",
    "size",
    "temperature",
)
