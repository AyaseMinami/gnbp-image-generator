package io.github.ayaseminami.gnbp.persistence.profile

import io.github.ayaseminami.gnbp.persistence.room.ProfileDao
import io.github.ayaseminami.gnbp.persistence.room.ProfileEntity
import io.github.ayaseminami.gnbp.persistence.secret.EncryptedSecret
import io.github.ayaseminami.gnbp.persistence.secret.SecretCipher
import io.github.ayaseminami.gnbp.persistence.secret.SecretDecryptionResult
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.transport.EndpointAuthority
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkMode
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportAcknowledgement
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportMode
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

class RoomProfileRepository internal constructor(
    private val profileDao: ProfileDao,
    private val secretCipher: SecretCipher,
) : ProfileRepository {
    private val json = Json

    override fun observeProfiles(): Flow<List<ProfileSummary>> = profileDao.observeAll().map { profiles ->
        profiles.mapNotNull { profile -> profile.toSummaryOrNull() }
    }

    override suspend fun loadProfile(id: ProfileId): ProfileLoadResult {
        val entity = profileDao.findById(id.value) ?: return ProfileLoadResult.NotFound
        val decrypted = secretCipher.decrypt(
            EncryptedSecret(entity.secretVersion, entity.apiKeyIv, entity.apiKeyCiphertext),
        )
        if (decrypted !is SecretDecryptionResult.Success) {
            return ProfileLoadResult.SecretUnavailable
        }
        return try {
            ProfileLoadResult.Found(entity.toProfile(ApiKey(decrypted.value)))
        } catch (_: IllegalArgumentException) {
            ProfileLoadResult.CorruptData
        } catch (_: SerializationException) {
            ProfileLoadResult.CorruptData
        }
    }

    override suspend fun saveProfile(profile: ProviderProfile): ProfileSaveResult {
        val existing = profileDao.findById(profile.id.value)
        val authorityChanged = existing != null &&
            existing.persistedAuthorityOrNull() != profile.binding.endpoint.authority
        if (authorityChanged && profile.binding.securityMode != TransportSecurityMode.VerifiedTls) {
            return ProfileSaveResult.AuthorityChangeRequiresSecurityReset
        }
        val encrypted = try {
            secretCipher.encrypt(profile.apiKey.reveal())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return ProfileSaveResult.SecretUnavailable
        }
        profileDao.upsert(profile.toEntity(encrypted))
        return ProfileSaveResult.Saved
    }

    override suspend fun deleteProfile(id: ProfileId): Boolean = profileDao.deleteById(id.value) > 0

    private fun ProviderProfile.toEntity(encrypted: EncryptedSecret): ProfileEntity {
        val security = binding.securityMode
        val acknowledgement = when (security) {
            is TransportSecurityMode.UnsafeTrustAllTls -> security.acknowledgement
            is TransportSecurityMode.CleartextHttp -> security.acknowledgement
            else -> null
        }
        return ProfileEntity(
            id = id.value,
            name = name,
            providerKind = providerKind.name,
            endpointUrl = binding.endpoint.toPersistedUrl(),
            model = model,
            secretVersion = encrypted.version,
            apiKeyIv = encrypted.iv,
            apiKeyCiphertext = encrypted.ciphertext,
            sortOrder = sortOrder,
            securityMode = security.persistedName(),
            localNetworkMode = binding.localNetworkMode.persistedName(),
            transportPolicyRevision = binding.policyRevision,
            customCaCertificatesJson = json.encodeToString(
                (security as? TransportSecurityMode.CustomCaTls)
                    ?.certificates
                    .orEmpty()
                    .map { Base64.getEncoder().encodeToString(it) },
            ),
            spkiPinsJson = json.encodeToString(
                (security as? TransportSecurityMode.CustomCaTls)?.spkiPins.orEmpty().sorted(),
            ),
            pinnedCertificate = (security as? TransportSecurityMode.PinnedServerCertificateTls)
                ?.certificate,
            allowHostnameMismatch = (security as? TransportSecurityMode.PinnedServerCertificateTls)
                ?.allowHostnameMismatch == true,
            acknowledgementProfileId = acknowledgement?.profileId?.value,
            acknowledgementScheme = acknowledgement?.authority?.scheme,
            acknowledgementHost = acknowledgement?.authority?.asciiHost,
            acknowledgementPort = acknowledgement?.authority?.effectivePort,
            acknowledgementMode = acknowledgement?.mode?.name,
            acknowledgementPolicyRevision = acknowledgement?.policyRevision,
            acknowledgementAcceptedAt = acknowledgement?.acceptedAtEpochMillis,
        )
    }

    private fun ProfileEntity.toProfile(apiKey: ApiKey): ProviderProfile {
        val profileId = ProfileId(id)
        val endpoint = ProviderEndpoint.parse(endpointUrl)
        val acknowledgement = acknowledgementOrNull()
        val security = when (securityMode) {
            "VERIFIED_TLS" -> TransportSecurityMode.VerifiedTls
            "CUSTOM_CA_TLS" -> TransportSecurityMode.CustomCaTls(
                certificates = decodeCertificates(customCaCertificatesJson),
                spkiPins = json.decodeFromString<List<String>>(spkiPinsJson).toSet(),
            )
            "PINNED_SERVER_CERTIFICATE_TLS" -> TransportSecurityMode.PinnedServerCertificateTls(
                certificate = requireNotNull(pinnedCertificate),
                allowHostnameMismatch = allowHostnameMismatch,
            )
            "UNSAFE_TRUST_ALL_TLS" -> TransportSecurityMode.UnsafeTrustAllTls(acknowledgement)
            "CLEARTEXT_HTTP" -> TransportSecurityMode.CleartextHttp(acknowledgement)
            else -> throw IllegalArgumentException("Unknown persisted transport security mode")
        }
        return ProviderProfile(
            id = profileId,
            name = name,
            providerKind = ProviderKind.valueOf(providerKind),
            binding = TransportBinding(
                profileId = profileId,
                endpoint = endpoint,
                securityMode = security,
                localNetworkMode = when (localNetworkMode) {
                    "INTERNET_OR_LOOPBACK_ONLY" -> LocalNetworkMode.InternetOrLoopbackOnly
                    "ALLOW_LAN" -> LocalNetworkMode.AllowLan
                    else -> throw IllegalArgumentException("Unknown persisted local-network mode")
                },
                policyRevision = transportPolicyRevision,
            ),
            apiKey = apiKey,
            model = model,
            sortOrder = sortOrder,
        )
    }

    private fun ProfileEntity.toSummaryOrNull(): ProfileSummary? = runCatching {
        ProfileSummary(
            id = ProfileId(id),
            name = name,
            providerKind = ProviderKind.valueOf(providerKind),
            model = model,
            isUnsafe = securityMode == "UNSAFE_TRUST_ALL_TLS" || securityMode == "CLEARTEXT_HTTP",
            sortOrder = sortOrder,
        )
    }.getOrNull()

    private fun ProfileEntity.acknowledgementOrNull(): UnsafeTransportAcknowledgement? {
        val values = listOf(
            acknowledgementProfileId,
            acknowledgementScheme,
            acknowledgementHost,
            acknowledgementPort,
            acknowledgementMode,
            acknowledgementPolicyRevision,
            acknowledgementAcceptedAt,
        )
        if (values.all { it == null }) return null
        require(values.none { it == null }) { "Persisted acknowledgement is incomplete" }
        return UnsafeTransportAcknowledgement(
            profileId = ProfileId(requireNotNull(acknowledgementProfileId)),
            authority = EndpointAuthority(
                scheme = requireNotNull(acknowledgementScheme),
                asciiHost = requireNotNull(acknowledgementHost),
                effectivePort = requireNotNull(acknowledgementPort),
            ),
            mode = UnsafeTransportMode.valueOf(requireNotNull(acknowledgementMode)),
            policyRevision = requireNotNull(acknowledgementPolicyRevision),
            acceptedAtEpochMillis = requireNotNull(acknowledgementAcceptedAt),
        )
    }

    private fun decodeCertificates(value: String): List<ByteArray> =
        json.decodeFromString<List<String>>(value).map(Base64.getDecoder()::decode)

    private fun ProfileEntity.persistedAuthorityOrNull(): EndpointAuthority? =
        runCatching { ProviderEndpoint.parse(endpointUrl).authority }.getOrNull()
}

private fun ProviderEndpoint.toPersistedUrl(): String = HttpUrl.Builder()
    .scheme(authority.scheme)
    .host(authority.asciiHost)
    .port(authority.effectivePort)
    .encodedPath(basePath)
    .build()
    .toString()

private fun TransportSecurityMode.persistedName(): String = when (this) {
    TransportSecurityMode.VerifiedTls -> "VERIFIED_TLS"
    is TransportSecurityMode.CustomCaTls -> "CUSTOM_CA_TLS"
    is TransportSecurityMode.PinnedServerCertificateTls -> "PINNED_SERVER_CERTIFICATE_TLS"
    is TransportSecurityMode.UnsafeTrustAllTls -> "UNSAFE_TRUST_ALL_TLS"
    is TransportSecurityMode.CleartextHttp -> "CLEARTEXT_HTTP"
}

private fun LocalNetworkMode.persistedName(): String = when (this) {
    LocalNetworkMode.InternetOrLoopbackOnly -> "INTERNET_OR_LOOPBACK_ONLY"
    LocalNetworkMode.AllowLan -> "ALLOW_LAN"
}
