package io.github.ayaseminami.gnbp.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.github.ayaseminami.gnbp.generation.ReferenceAssetInput
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ContentUriReader {
    fun mimeType(uri: Uri): String?

    fun displayName(uri: Uri): String?

    fun open(uri: Uri): InputStream?
}

class AndroidContentUriReader(
    private val contentResolver: ContentResolver,
) : ContentUriReader {
    override fun mimeType(uri: Uri): String? = runCatching {
        contentResolver.getType(uri)
    }.getOrNull()

    override fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use(Cursor::firstDisplayName)
    }.getOrNull()

    override fun open(uri: Uri): InputStream? = contentResolver.openInputStream(uri)

}

class ContentUriReferenceStore(
    private val reader: ContentUriReader,
    private val rootDirectory: File,
    private val maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        require(maxInputBytes > 0) { "Maximum input size must be positive" }
    }

    suspend fun import(uri: Uri): ReferenceImportResult = withContext(Dispatchers.IO) {
        val rawMimeType = runCatching { reader.mimeType(uri) }.getOrNull()
        val mimeType = rawMimeType?.substringBefore(';')?.trim()?.lowercase()
        val stream = try {
            reader.open(uri)
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } ?: return@withContext ReferenceImportResult.Failed(ReferenceImportFailure.AccessRevoked)
        if (mimeType == null || !mimeType.startsWith("image/")) {
            runCatching(stream::close)
            return@withContext ReferenceImportResult.Failed(
                ReferenceImportFailure.UnsupportedMimeType,
            )
        }

        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            runCatching(stream::close)
            return@withContext ReferenceImportResult.Failed(
                ReferenceImportFailure.StorageUnavailable,
            )
        }

        val id = MediaAssetId(idGenerator())
        val temporary = File(rootDirectory, ".${id.value}.tmp")
        val destination = File(rootDirectory, "${id.value}.input")
        val copyResult = try {
            stream.use { input ->
                FileOutputStream(temporary).use { output ->
                    copyWithLimit(input, output, maxInputBytes)
                }
            }
        } catch (_: SecurityException) {
            CopyResult.AccessRevoked
        } catch (_: IOException) {
            CopyResult.StorageFailure
        }
        when (copyResult) {
            CopyResult.TooLarge -> {
                temporary.delete()
                return@withContext ReferenceImportResult.Failed(ReferenceImportFailure.TooLarge)
            }
            CopyResult.AccessRevoked -> {
                temporary.delete()
                return@withContext ReferenceImportResult.Failed(ReferenceImportFailure.AccessRevoked)
            }
            CopyResult.StorageFailure -> {
                temporary.delete()
                return@withContext ReferenceImportResult.Failed(
                    ReferenceImportFailure.StorageUnavailable,
                )
            }
            CopyResult.Copied -> Unit
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            return@withContext ReferenceImportResult.Failed(
                ReferenceImportFailure.StorageUnavailable,
            )
        }

        ReferenceImportResult.Imported(
            DurableReferenceAsset(
                id = id,
                file = destination,
                displayName = sanitizeDisplayName(
                    runCatching { reader.displayName(uri) }.getOrNull() ?: uri.lastPathSegment,
                    "reference-image",
                ),
                mimeType = mimeType,
            ),
        )
    }

    fun delete(asset: DurableReferenceAsset): Boolean =
        if (asset.file.isInside(rootDirectory)) asset.file.delete() else false

    fun resolve(input: ReferenceAssetInput): DurableReferenceAsset? {
        val file = File(rootDirectory, "${input.id}.input")
        return if (file.isFile && file.isInside(rootDirectory)) {
            DurableReferenceAsset(
                id = MediaAssetId(input.id),
                file = file,
                displayName = input.displayName,
                mimeType = input.mimeType,
            )
        } else {
            null
        }
    }

    fun cleanupOrphanedCopies(
        retainedAssetIds: Set<MediaAssetId>,
        nowEpochMillis: Long,
        policy: ReferenceCleanupPolicy = ReferenceCleanupPolicy(),
    ): Int = rootDirectory.listFiles().orEmpty()
        .filter { file ->
            if (!file.isFile) {
                false
            } else if (file.name.endsWith(".tmp")) {
                file.lastModified() < nowEpochMillis - policy.incompleteCopyRetentionMillis
            } else {
                file.assetIdOrNull() !in retainedAssetIds &&
                    file.lastModified() < nowEpochMillis - policy.orphanRetentionMillis
            }
        }
        .count(File::delete)

    suspend fun cleanupReleasedCopies(
        releasedAssetIds: Set<MediaAssetId>,
        retainedAssetIds: Set<MediaAssetId>,
    ): ReferenceReleaseCleanupReport = withContext(Dispatchers.IO) {
        val deleted = mutableSetOf<MediaAssetId>()
        val failed = mutableSetOf<MediaAssetId>()
        (releasedAssetIds - retainedAssetIds).forEach { assetId ->
            val file = File(rootDirectory, "${assetId.value}.input")
            if (file.isFile && file.isInside(rootDirectory)) {
                if (file.delete()) deleted += assetId else failed += assetId
            }
        }
        ReferenceReleaseCleanupReport(deleted, failed)
    }

    companion object {
        private const val DEFAULT_MAX_INPUT_BYTES = 64L * 1024L * 1024L

        fun create(context: Context): ContentUriReferenceStore {
            val applicationContext = context.applicationContext
            return ContentUriReferenceStore(
                reader = AndroidContentUriReader(applicationContext.contentResolver),
                rootDirectory = File(applicationContext.filesDir, "reference-images"),
            )
        }
    }
}

data class ReferenceReleaseCleanupReport(
    val deletedAssetIds: Set<MediaAssetId>,
    val failedAssetIds: Set<MediaAssetId>,
) {
    override fun toString(): String =
        "ReferenceReleaseCleanupReport(deletedAssetIds=[REDACTED], failedAssetIds=[REDACTED])"
}

data class ReferenceCleanupPolicy(
    val orphanRetentionMillis: Long = DEFAULT_ORPHAN_RETENTION_MILLIS,
    val incompleteCopyRetentionMillis: Long = DEFAULT_INCOMPLETE_COPY_RETENTION_MILLIS,
) {
    init {
        require(orphanRetentionMillis >= 0) { "Orphan retention must not be negative" }
        require(incompleteCopyRetentionMillis >= 0) {
            "Incomplete-copy retention must not be negative"
        }
    }

    companion object {
        const val DEFAULT_ORPHAN_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val DEFAULT_INCOMPLETE_COPY_RETENTION_MILLIS = 60L * 60L * 1_000L
    }
}

private enum class CopyResult {
    Copied,
    TooLarge,
    AccessRevoked,
    StorageFailure,
}

private fun copyWithLimit(
    input: InputStream,
    output: FileOutputStream,
    limit: Long,
): CopyResult {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return CopyResult.Copied
        total += count
        if (total > limit) return CopyResult.TooLarge
        output.write(buffer, 0, count)
    }
}

private fun Cursor.firstDisplayName(): String? =
    if (moveToFirst()) {
        val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && !isNull(index)) getString(index) else null
    } else {
        null
    }

private fun File.isInside(root: File): Boolean = runCatching {
    canonicalPath.startsWith(root.canonicalPath + File.separator)
}.getOrDefault(false)

private fun File.assetIdOrNull(): MediaAssetId? = runCatching {
    MediaAssetId(name.removePrefix(".").substringBefore('.'))
}.getOrNull()
