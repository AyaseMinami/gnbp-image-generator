package io.github.ayaseminami.gnbp.persistence.profile

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import io.github.ayaseminami.gnbp.persistence.secret.AesGcmSecretCipher
import io.github.ayaseminami.gnbp.persistence.secret.SecretKeyProvider
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkMode
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportAcknowledgement
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportMode
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomProfileRepositoryTest {
    private lateinit var database: GnbpDatabase
    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomProfileRepository(database.profileDao(), testCipher())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `profile CRUD preserves the encrypted provider contract`() = runTest {
        val profileId = ProfileId("profile-1")
        val original = ProviderProfile(
            id = profileId,
            name = "Primary relay",
            providerKind = ProviderKind.Gemini,
            binding = TransportBinding(
                profileId = profileId,
                endpoint = ProviderEndpoint.parse("https://relay.example/base/"),
            ),
            apiKey = ApiKey("sentinel-api-key"),
            model = "gemini-model",
            sortOrder = 4,
        )

        assertEquals(ProfileSaveResult.Saved, repository.saveProfile(original))
        val loaded = repository.loadProfile(profileId)

        assertTrue(loaded is ProfileLoadResult.Found)
        loaded as ProfileLoadResult.Found
        assertEquals("Primary relay", loaded.profile.name)
        assertEquals(ProviderKind.Gemini, loaded.profile.providerKind)
        assertEquals("gemini-model", loaded.profile.model)
        assertEquals("sentinel-api-key", loaded.profile.apiKey.reveal())
        assertEquals(original.binding.endpoint.authority, loaded.profile.binding.endpoint.authority)
        assertEquals("/base/", loaded.profile.binding.endpoint.basePath)
        assertEquals(4, loaded.profile.sortOrder)

        val updated = ProviderProfile(
            id = profileId,
            name = "Updated relay",
            providerKind = ProviderKind.OpenAiCompatible,
            binding = TransportBinding(
                profileId = profileId,
                endpoint = ProviderEndpoint.parse("https://relay.example/openai/"),
            ),
            apiKey = ApiKey("updated-api-key"),
            model = "gpt-image-model",
            sortOrder = 1,
        )
        assertEquals(ProfileSaveResult.Saved, repository.saveProfile(updated))
        val reloaded = repository.loadProfile(profileId) as ProfileLoadResult.Found
        assertEquals("Updated relay", reloaded.profile.name)
        assertEquals("updated-api-key", reloaded.profile.apiKey.reveal())
        assertEquals(ProviderKind.OpenAiCompatible, reloaded.profile.providerKind)

        assertTrue(repository.deleteProfile(profileId))
        assertEquals(ProfileLoadResult.NotFound, repository.loadProfile(profileId))
    }

    @Test
    fun `all approved transport security modes survive persistence`() = runTest {
        val customId = ProfileId("custom-ca")
        val customEndpoint = ProviderEndpoint.parse("https://custom.example/base/")
        val pinnedId = ProfileId("pinned")
        val pinnedEndpoint = ProviderEndpoint.parse("https://pinned.example/base/")
        val trustAllId = ProfileId("trust-all")
        val trustAllEndpoint = ProviderEndpoint.parse("https://unsafe.example/base/")
        val cleartextId = ProfileId("cleartext")
        val cleartextEndpoint = ProviderEndpoint.parse("http://lan.example/base/")
        val profiles = listOf(
            testProfile(
                customId,
                customEndpoint,
                TransportSecurityMode.CustomCaTls(
                    certificates = listOf("ca-one".encodeToByteArray(), "ca-two".encodeToByteArray()),
                    spkiPins = setOf("sha256/pin-one=", "sha256/pin-two="),
                ),
                sortOrder = 1,
            ),
            testProfile(
                pinnedId,
                pinnedEndpoint,
                TransportSecurityMode.PinnedServerCertificateTls(
                    certificate = "server-certificate".encodeToByteArray(),
                    allowHostnameMismatch = true,
                ),
                sortOrder = 2,
            ),
            testProfile(
                trustAllId,
                trustAllEndpoint,
                TransportSecurityMode.UnsafeTrustAllTls(
                    UnsafeTransportAcknowledgement(
                        profileId = trustAllId,
                        authority = trustAllEndpoint.authority,
                        mode = UnsafeTransportMode.TrustAllTls,
                        policyRevision = TransportBinding.CURRENT_POLICY_REVISION,
                        acceptedAtEpochMillis = 100L,
                    ),
                ),
                sortOrder = 3,
            ),
            testProfile(
                cleartextId,
                cleartextEndpoint,
                TransportSecurityMode.CleartextHttp(
                    UnsafeTransportAcknowledgement(
                        profileId = cleartextId,
                        authority = cleartextEndpoint.authority,
                        mode = UnsafeTransportMode.CleartextHttp,
                        policyRevision = TransportBinding.CURRENT_POLICY_REVISION,
                        acceptedAtEpochMillis = 200L,
                    ),
                ),
                localNetworkMode = LocalNetworkMode.AllowLan,
                sortOrder = 4,
            ),
        )
        profiles.forEach { profile ->
            assertEquals(ProfileSaveResult.Saved, repository.saveProfile(profile))
        }

        val custom = (repository.loadProfile(customId) as ProfileLoadResult.Found)
            .profile.binding.securityMode as TransportSecurityMode.CustomCaTls
        assertEquals(
            listOf("ca-one", "ca-two"),
            custom.certificates.map(ByteArray::decodeToString),
        )
        assertEquals(setOf("sha256/pin-one=", "sha256/pin-two="), custom.spkiPins)

        val pinned = (repository.loadProfile(pinnedId) as ProfileLoadResult.Found)
            .profile.binding.securityMode as TransportSecurityMode.PinnedServerCertificateTls
        assertEquals("server-certificate", pinned.certificate.decodeToString())
        assertTrue(pinned.allowHostnameMismatch)

        val trustAll = (repository.loadProfile(trustAllId) as ProfileLoadResult.Found)
            .profile.binding.securityMode as TransportSecurityMode.UnsafeTrustAllTls
        assertEquals(100L, trustAll.acknowledgement?.acceptedAtEpochMillis)

        val cleartextProfile = (repository.loadProfile(cleartextId) as ProfileLoadResult.Found).profile
        val cleartext = cleartextProfile.binding.securityMode as TransportSecurityMode.CleartextHttp
        assertEquals(UnsafeTransportMode.CleartextHttp, cleartext.acknowledgement?.mode)
        assertEquals(LocalNetworkMode.AllowLan, cleartextProfile.binding.localNetworkMode)

        assertEquals(
            listOf(false, false, true, true),
            repository.observeProfiles().first().map(ProfileSummary::isUnsafe),
        )
    }

    @Test
    fun `authority changes require trust reset while base path changes preserve the binding`() = runTest {
        val profileId = ProfileId("authority-bound-profile")
        val originalEndpoint = ProviderEndpoint.parse("https://relay.example/base/")
        val customTrust = TransportSecurityMode.CustomCaTls(
            certificates = listOf("authority-bound-ca".encodeToByteArray()),
            spkiPins = setOf("sha256/authority-bound-pin="),
        )
        val original = testProfile(
            profileId,
            originalEndpoint,
            customTrust,
            sortOrder = 0,
        )
        assertEquals(ProfileSaveResult.Saved, repository.saveProfile(original))

        listOf(
            "https://other.example/base/",
            "https://relay.example:8443/base/",
            "http://relay.example/base/",
        ).forEach { changedAuthority ->
            val carriedTrust = testProfile(
                profileId,
                ProviderEndpoint.parse(changedAuthority),
                customTrust,
                sortOrder = 0,
            )
            assertEquals(
                ProfileSaveResult.AuthorityChangeRequiresSecurityReset,
                repository.saveProfile(carriedTrust),
            )
        }
        val unchanged = repository.loadProfile(profileId) as ProfileLoadResult.Found
        assertEquals(originalEndpoint.authority, unchanged.profile.binding.endpoint.authority)

        val newPath = ProviderEndpoint.parse("https://relay.example/other/")
        assertEquals(
            ProfileSaveResult.Saved,
            repository.saveProfile(testProfile(profileId, newPath, customTrust, sortOrder = 0)),
        )
        val pathUpdated = repository.loadProfile(profileId) as ProfileLoadResult.Found
        assertEquals("/other/", pathUpdated.profile.binding.endpoint.basePath)
        assertTrue(pathUpdated.profile.binding.securityMode is TransportSecurityMode.CustomCaTls)

        val newAuthority = ProviderEndpoint.parse("https://other.example/base/")
        assertEquals(
            ProfileSaveResult.Saved,
            repository.saveProfile(
                testProfile(
                    profileId,
                    newAuthority,
                    TransportSecurityMode.VerifiedTls,
                    sortOrder = 0,
                ),
            ),
        )
        assertEquals(
            ProfileSaveResult.Saved,
            repository.saveProfile(testProfile(profileId, newAuthority, customTrust, sortOrder = 0)),
        )
        val rebound = repository.loadProfile(profileId) as ProfileLoadResult.Found
        assertEquals(newAuthority.authority, rebound.profile.binding.endpoint.authority)
        assertTrue(rebound.profile.binding.securityMode is TransportSecurityMode.CustomCaTls)
    }

    private fun testProfile(
        id: ProfileId,
        endpoint: ProviderEndpoint,
        securityMode: TransportSecurityMode,
        localNetworkMode: LocalNetworkMode = LocalNetworkMode.InternetOrLoopbackOnly,
        sortOrder: Int,
    ) = ProviderProfile(
        id = id,
        name = "Profile ${id.value}",
        providerKind = ProviderKind.Gemini,
        binding = TransportBinding(
            profileId = id,
            endpoint = endpoint,
            securityMode = securityMode,
            localNetworkMode = localNetworkMode,
        ),
        apiKey = ApiKey("key-${id.value}"),
        model = "model-${id.value}",
        sortOrder = sortOrder,
    )
}

private fun testCipher() = AesGcmSecretCipher(
    SecretKeyProvider {
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    },
)
