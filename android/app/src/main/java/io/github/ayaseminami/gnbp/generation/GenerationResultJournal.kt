package io.github.ayaseminami.gnbp.generation

import android.content.Context
import androidx.core.net.toUri
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.MAX_GENERATED_BYTES
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.media.validateForStorage
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface ResultJournalRecovery {
    data object None : ResultJournalRecovery

    data class Staged(
        val image: GeneratedImage,
        val metadata: GeneratedAssetMetadata,
    ) : ResultJournalRecovery {
        override fun toString(): String = "Staged(image=[REDACTED], metadata=[REDACTED])"
    }

    data class Saved(val asset: AssetRef) : ResultJournalRecovery {
        override fun toString(): String = "Saved(asset=[REDACTED])"
    }
}

internal interface GenerationResultJournal {
    suspend fun stage(
        taskId: TaskId,
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): Boolean

    suspend fun recordSaved(taskId: TaskId, asset: AssetRef): Boolean

    suspend fun load(taskId: TaskId): ResultJournalRecovery

    suspend fun delete(taskId: TaskId)

    suspend fun deleteForTaskRemoval(taskId: TaskId): Boolean {
        delete(taskId)
        return true
    }
}

internal object NoOpGenerationResultJournal : GenerationResultJournal {
    override suspend fun stage(
        taskId: TaskId,
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): Boolean = true

    override suspend fun recordSaved(taskId: TaskId, asset: AssetRef): Boolean = true

    override suspend fun load(taskId: TaskId): ResultJournalRecovery = ResultJournalRecovery.None

    override suspend fun delete(taskId: TaskId) = Unit
}

internal class FileGenerationResultJournal(
    private val root: File,
) : GenerationResultJournal {
    @Volatile
    private var temporaryFilesCleaned = false

    override suspend fun stage(
        taskId: TaskId,
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): Boolean = withContext(Dispatchers.IO) {
        cleanTemporaryFilesOnce()
        if (image.validateForStorage() != null) return@withContext false
        val header = Json.encodeToString(
            StagedHeader(
                mimeType = image.mimeType,
                provider = metadata.provider,
                model = metadata.model,
                parameters = metadata.parameters,
                referenceDisplayNames = metadata.referenceDisplayNames,
            ),
        ).encodeToByteArray()
        if (header.size !in 1..MAX_HEADER_BYTES) return@withContext false
        writeAtomically(stageFile(taskId)) { output ->
            val data = DataOutputStream(output)
            data.writeInt(STAGE_MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(header.size)
            data.writeLong(image.bytes.size.toLong())
            data.write(header)
            data.write(image.bytes)
            data.flush()
        }
    }

    override suspend fun recordSaved(taskId: TaskId, asset: AssetRef): Boolean =
        withContext(Dispatchers.IO) {
            cleanTemporaryFilesOnce()
            val bytes = Json.encodeToString(
                SavedReceipt(
                    id = asset.id.value,
                    uri = asset.uri.toString(),
                    displayName = asset.displayName,
                    mimeType = asset.mimeType,
                    byteSize = asset.byteSize,
                ),
            ).encodeToByteArray()
            if (bytes.size !in 1..MAX_RECEIPT_BYTES) return@withContext false
            writeAtomically(receiptFile(taskId)) { output -> output.write(bytes) }
        }

    override suspend fun load(taskId: TaskId): ResultJournalRecovery = withContext(Dispatchers.IO) {
        cleanTemporaryFilesOnce()
        readReceipt(receiptFile(taskId))?.let { return@withContext ResultJournalRecovery.Saved(it) }
        readStage(stageFile(taskId)) ?: ResultJournalRecovery.None
    }

    override suspend fun delete(taskId: TaskId) = withContext(Dispatchers.IO) {
        cleanTemporaryFilesOnce()
        runCatching { receiptFile(taskId).delete() }
        runCatching { stageFile(taskId).delete() }
        Unit
    }

    override suspend fun deleteForTaskRemoval(taskId: TaskId): Boolean =
        withContext(Dispatchers.IO) {
            cleanTemporaryFilesOnce()
            listOf(receiptFile(taskId), stageFile(taskId)).all { file ->
                !file.exists() || runCatching(file::delete).getOrDefault(false)
            }
        }

    private fun readReceipt(file: File): AssetRef? {
        if (!file.isFile) return null
        if (file.length() !in 1..MAX_RECEIPT_BYTES.toLong()) {
            runCatching { file.delete() }
            return null
        }
        return try {
            val receipt = Json.decodeFromString<SavedReceipt>(file.readText(Charsets.UTF_8))
            AssetRef(
                id = MediaAssetId(receipt.id),
                uri = receipt.uri.toUri(),
                displayName = receipt.displayName,
                mimeType = receipt.mimeType,
                byteSize = receipt.byteSize,
            )
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    private fun readStage(file: File): ResultJournalRecovery.Staged? {
        if (!file.isFile) return null
        if (file.length() > MAX_STAGE_FILE_BYTES) {
            runCatching { file.delete() }
            return null
        }
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                require(input.readInt() == STAGE_MAGIC)
                require(input.readInt() == FORMAT_VERSION)
                val headerLength = input.readInt()
                val imageLength = input.readLong()
                require(headerLength in 1..MAX_HEADER_BYTES)
                require(imageLength in 1..MAX_GENERATED_BYTES)
                val expectedLength = STAGE_FIXED_BYTES + headerLength + imageLength
                require(file.length() == expectedLength)
                val headerBytes = ByteArray(headerLength).also { bytes -> input.readFully(bytes) }
                val header = Json.decodeFromString<StagedHeader>(headerBytes.decodeToString())
                val imageBytes = ByteArray(imageLength.toInt()).also { bytes -> input.readFully(bytes) }
                val image = GeneratedImage(
                    bytes = imageBytes,
                    mimeType = header.mimeType,
                )
                require(image.validateForStorage() == null)
                ResultJournalRecovery.Staged(
                    image = image,
                    metadata = GeneratedAssetMetadata(
                        provider = header.provider,
                        model = header.model,
                        parameters = header.parameters,
                        referenceDisplayNames = header.referenceDisplayNames,
                    ),
                )
            }
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    private fun writeAtomically(
        target: File,
        writer: (FileOutputStream) -> Unit,
    ): Boolean {
        var temporary: File? = null
        return try {
            if (!root.exists() && !root.mkdirs()) return false
            temporary = File(root, ".${target.name}.${UUID.randomUUID()}.tmp")
            FileOutputStream(temporary).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) return false
            temporary.renameTo(target)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            temporary?.takeIf(File::exists)?.delete()
        }
    }

    private fun cleanTemporaryFilesOnce() {
        if (temporaryFilesCleaned) return
        synchronized(this) {
            if (temporaryFilesCleaned) return
            root.listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        file.name.startsWith(".") &&
                        file.name.endsWith(".tmp") &&
                        (file.name.contains(".stage.") || file.name.contains(".receipt."))
                }
                ?.forEach { file -> runCatching { file.delete() } }
            temporaryFilesCleaned = true
        }
    }

    private fun stageFile(taskId: TaskId) = File(root, "${taskId.value}.stage")

    private fun receiptFile(taskId: TaskId) = File(root, "${taskId.value}.receipt")

    companion object {
        fun create(context: Context): FileGenerationResultJournal = FileGenerationResultJournal(
            File(context.applicationContext.filesDir, DIRECTORY_NAME),
        )

        private const val DIRECTORY_NAME = "generation-results"
        private const val STAGE_MAGIC = 0x474E4250
        private const val FORMAT_VERSION = 1
        private const val STAGE_FIXED_BYTES = 20L
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_RECEIPT_BYTES = 16 * 1024
        private const val MAX_STAGE_FILE_BYTES = STAGE_FIXED_BYTES + MAX_HEADER_BYTES + MAX_GENERATED_BYTES
    }
}

@Serializable
private data class StagedHeader(
    val mimeType: String,
    val provider: String,
    val model: String,
    val parameters: Map<String, String>,
    val referenceDisplayNames: List<String>,
)

@Serializable
private data class SavedReceipt(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
)
