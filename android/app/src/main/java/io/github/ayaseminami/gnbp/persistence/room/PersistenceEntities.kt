package io.github.ayaseminami.gnbp.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
internal data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "provider_kind") val providerKind: String,
    @ColumnInfo(name = "endpoint_url") val endpointUrl: String,
    val model: String,
    @ColumnInfo(name = "secret_version") val secretVersion: Int,
    @ColumnInfo(name = "api_key_iv", typeAffinity = ColumnInfo.BLOB) val apiKeyIv: ByteArray,
    @ColumnInfo(name = "api_key_ciphertext", typeAffinity = ColumnInfo.BLOB) val apiKeyCiphertext: ByteArray,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "security_mode", defaultValue = "'VERIFIED_TLS'")
    val securityMode: String = "VERIFIED_TLS",
    @ColumnInfo(name = "local_network_mode", defaultValue = "'INTERNET_OR_LOOPBACK_ONLY'")
    val localNetworkMode: String = "INTERNET_OR_LOOPBACK_ONLY",
    @ColumnInfo(name = "transport_policy_revision", defaultValue = "1")
    val transportPolicyRevision: Int = 1,
    @ColumnInfo(name = "custom_ca_certificates_json", defaultValue = "'[]'")
    val customCaCertificatesJson: String = "[]",
    @ColumnInfo(name = "spki_pins_json", defaultValue = "'[]'")
    val spkiPinsJson: String = "[]",
    @ColumnInfo(name = "pinned_certificate", typeAffinity = ColumnInfo.BLOB)
    val pinnedCertificate: ByteArray? = null,
    @ColumnInfo(name = "allow_hostname_mismatch", defaultValue = "0")
    val allowHostnameMismatch: Boolean = false,
    @ColumnInfo(name = "ack_profile_id") val acknowledgementProfileId: String? = null,
    @ColumnInfo(name = "ack_scheme") val acknowledgementScheme: String? = null,
    @ColumnInfo(name = "ack_host") val acknowledgementHost: String? = null,
    @ColumnInfo(name = "ack_port") val acknowledgementPort: Int? = null,
    @ColumnInfo(name = "ack_mode") val acknowledgementMode: String? = null,
    @ColumnInfo(name = "ack_policy_revision") val acknowledgementPolicyRevision: Int? = null,
    @ColumnInfo(name = "ack_accepted_at") val acknowledgementAcceptedAt: Long? = null,
) {
    override fun toString(): String =
        "ProfileEntity(id=[REDACTED], name=[REDACTED], providerKind=$providerKind, " +
            "endpoint=[REDACTED], model=[REDACTED], apiKey=[REDACTED], securityMode=$securityMode)"
}

@Entity(tableName = "prompts")
internal data class PromptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
) {
    override fun toString(): String =
        "PromptEntity(id=[REDACTED], name=[REDACTED], content=[REDACTED], sortOrder=$sortOrder)"
}

@Entity(
    tableName = "generation_tasks",
    indices = [Index(value = ["source_task_id"], unique = true)],
)
internal data class GenerationTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "request_json") val requestJson: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "source_task_id") val sourceTaskId: String?,
    @ColumnInfo(name = "terminal_reason") val terminalReason: String?,
    @ColumnInfo(name = "result_asset_id") val resultAssetId: String?,
    @ColumnInfo(name = "result_uri") val resultUri: String?,
    @ColumnInfo(name = "result_display_name") val resultDisplayName: String?,
    @ColumnInfo(name = "result_mime_type") val resultMimeType: String?,
    @ColumnInfo(name = "result_byte_size") val resultByteSize: Long?,
    @ColumnInfo(name = "failure_http_status") val failureHttpStatus: Int? = null,
    @ColumnInfo(name = "failure_provider_message") val failureProviderMessage: String? = null,
) {
    override fun toString(): String =
        "GenerationTaskEntity(id=[REDACTED], request=[REDACTED], status=$status, " +
            "timestamps=[REDACTED], sourceTaskId=[REDACTED], terminalReason=$terminalReason, " +
            "result=[REDACTED], failureDiagnostic=[REDACTED])"
}

@Entity(
    tableName = "generated_results",
    indices = [Index(value = ["source_task_id"], unique = true)],
)
internal data class GeneratedResultEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_task_id") val sourceTaskId: String,
    @ColumnInfo(name = "request_json") val requestJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "asset_uri") val assetUri: String,
    @ColumnInfo(name = "asset_display_name") val assetDisplayName: String,
    @ColumnInfo(name = "asset_mime_type") val assetMimeType: String,
    @ColumnInfo(name = "asset_byte_size") val assetByteSize: Long,
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
) {
    override fun toString(): String =
        "GeneratedResultEntity(id=[REDACTED], sourceTaskId=[REDACTED], " +
            "request=[REDACTED], asset=[REDACTED], createdAt=[REDACTED], " +
            "isFavorite=$isFavorite)"
}
