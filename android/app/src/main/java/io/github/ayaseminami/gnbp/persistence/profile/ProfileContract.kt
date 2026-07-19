package io.github.ayaseminami.gnbp.persistence.profile

import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import kotlinx.coroutines.flow.Flow

enum class ProviderKind {
    Gemini,
    OpenAiCompatible,
}

class ProviderProfile(
    val id: ProfileId,
    val name: String,
    val providerKind: ProviderKind,
    val binding: TransportBinding,
    val apiKey: ApiKey,
    val model: String,
    val sortOrder: Int,
) {
    init {
        require(name.isNotBlank()) { "Profile name must not be blank" }
        require(model.isNotBlank()) { "Profile model must not be blank" }
        require(binding.profileId == id) { "Profile and transport binding IDs must match" }
        require(sortOrder >= 0) { "Profile sort order must not be negative" }
    }

    override fun toString(): String =
        "ProviderProfile(id=[REDACTED], name=[REDACTED], providerKind=$providerKind, " +
            "binding=$binding, apiKey=$apiKey, model=[REDACTED], sortOrder=$sortOrder)"
}

data class ProfileSummary(
    val id: ProfileId,
    val name: String,
    val providerKind: ProviderKind,
    val model: String,
    val isUnsafe: Boolean,
    val sortOrder: Int,
) {
    override fun toString(): String =
        "ProfileSummary(id=[REDACTED], name=[REDACTED], providerKind=$providerKind, " +
            "model=[REDACTED], isUnsafe=$isUnsafe, sortOrder=$sortOrder)"
}

sealed interface ProfileLoadResult {
    data class Found(val profile: ProviderProfile) : ProfileLoadResult

    data object NotFound : ProfileLoadResult

    data object SecretUnavailable : ProfileLoadResult

    data object CorruptData : ProfileLoadResult
}

sealed interface ProfileSaveResult {
    data object Saved : ProfileSaveResult

    data object AuthorityChangeRequiresSecurityReset : ProfileSaveResult

    data object SecretUnavailable : ProfileSaveResult
}

interface ProfileRepository {
    fun observeProfiles(): Flow<List<ProfileSummary>>

    suspend fun loadProfile(id: ProfileId): ProfileLoadResult

    suspend fun saveProfile(profile: ProviderProfile): ProfileSaveResult

    suspend fun deleteProfile(id: ProfileId): Boolean
}

internal fun TransportSecurityMode.isUnsafe(): Boolean =
    this is TransportSecurityMode.UnsafeTrustAllTls || this is TransportSecurityMode.CleartextHttp
