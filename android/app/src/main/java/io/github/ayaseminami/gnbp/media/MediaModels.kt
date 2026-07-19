package io.github.ayaseminami.gnbp.media

import android.net.Uri
import androidx.core.net.toUri
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import io.github.ayaseminami.gnbp.provider.ReferenceImage
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import java.io.File

@JvmInline
value class MediaAssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "Media asset ID must not be blank" }
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Media asset ID contains unsupported characters"
        }
    }
}

data class AssetRef(
    val id: MediaAssetId,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
) {
    init {
        require(displayName.isNotBlank()) { "Asset display name must not be blank" }
        require(mimeType.startsWith("image/")) { "Asset MIME type must be an image" }
        require(byteSize > 0) { "Asset byte size must be positive" }
    }

    override fun toString(): String =
        "AssetRef(id=[REDACTED], uri=[REDACTED], displayName=[REDACTED], " +
            "mimeType=$mimeType, byteSize=$byteSize)"
}

fun GeneratedAssetReference.toAssetRef() = AssetRef(
    id = MediaAssetId(id),
    uri = location.toUri(),
    displayName = displayName,
    mimeType = mimeType,
    byteSize = byteSize,
)

data class DurableReferenceAsset(
    val id: MediaAssetId,
    val file: File,
    val displayName: String,
    val mimeType: String,
) {
    init {
        require(displayName.isNotBlank()) { "Reference display name must not be blank" }
        require(mimeType.startsWith("image/")) { "Reference MIME type must be an image" }
    }

    override fun toString(): String =
        "DurableReferenceAsset(id=[REDACTED], file=[REDACTED], displayName=[REDACTED], " +
            "mimeType=$mimeType)"

    fun asReferenceImage(preparer: BoundedImagePreparer): ImagePreparationResult =
        preparer.prepare(file, displayName, mimeType)
}

data class GeneratedAssetMetadata(
    val provider: String,
    val model: String,
    val parameters: Map<String, String> = emptyMap(),
    val referenceDisplayNames: List<String> = emptyList(),
) {
    init {
        require(provider.isNotBlank()) { "Metadata provider must not be blank" }
        require(model.isNotBlank()) { "Metadata model must not be blank" }
    }

    override fun toString(): String =
        "GeneratedAssetMetadata(provider=$provider, model=[REDACTED], " +
            "parameters=[REDACTED], referenceDisplayNames=[REDACTED])"
}

sealed interface ReferenceImportResult {
    data class Imported(val asset: DurableReferenceAsset) : ReferenceImportResult

    data class Failed(val reason: ReferenceImportFailure) : ReferenceImportResult
}

sealed interface ReferenceImportFailure {
    data object AccessRevoked : ReferenceImportFailure

    data object UnsupportedMimeType : ReferenceImportFailure

    data object TooLarge : ReferenceImportFailure

    data object StorageUnavailable : ReferenceImportFailure
}

sealed interface ImagePreparationResult {
    data class Prepared(
        val reference: ReferenceImage,
        val width: Int,
        val height: Int,
    ) : ImagePreparationResult

    data class Failed(val reason: ImagePreparationFailure) : ImagePreparationResult
}

sealed interface ImagePreparationFailure {
    data object SourceMissing : ImagePreparationFailure

    data object InvalidImage : ImagePreparationFailure

    data object TooLarge : ImagePreparationFailure

    data object EncodingFailed : ImagePreparationFailure
}

sealed interface AssetSaveResult {
    data class Saved(val asset: AssetRef) : AssetSaveResult

    data class Failed(val reason: AssetSaveFailure) : AssetSaveResult
}

sealed interface AssetSaveFailure {
    data object PermissionRequired : AssetSaveFailure

    data object StorageUnavailable : AssetSaveFailure

    data object UnsupportedImageType : AssetSaveFailure

    data object ImageTooLarge : AssetSaveFailure

    data object WriteFailed : AssetSaveFailure
}

sealed interface AssetReadResult {
    data class Opened(val bytes: ByteArray) : AssetReadResult {
        override fun toString(): String = "Opened(bytes=[REDACTED])"
    }

    data object ExternalAssetMissing : AssetReadResult

    data object ReadFailed : AssetReadResult
}

fun GeneratedImage.validateForStorage(): AssetSaveFailure? = when {
    bytes.isEmpty() -> AssetSaveFailure.WriteFailed
    mimeType !in SUPPORTED_GENERATED_MIME_TYPES -> AssetSaveFailure.UnsupportedImageType
    bytes.size.toLong() > MAX_GENERATED_BYTES -> AssetSaveFailure.ImageTooLarge
    else -> null
}

internal const val MAX_GENERATED_BYTES = 64L * 1024L * 1024L
internal val SUPPORTED_GENERATED_MIME_TYPES = setOf("image/png", "image/jpeg")
