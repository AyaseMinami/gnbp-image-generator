package io.github.ayaseminami.gnbp.ui.settings

import io.github.ayaseminami.gnbp.persistence.profile.ProfileLoadResult
import io.github.ayaseminami.gnbp.persistence.profile.ProfileRepository
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSaveResult
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSummary
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.persistence.prompt.PromptPreset
import io.github.ayaseminami.gnbp.persistence.prompt.PromptRepository
import io.github.ayaseminami.gnbp.persistence.settings.AppSettings
import io.github.ayaseminami.gnbp.persistence.settings.GalleryLayoutMode
import io.github.ayaseminami.gnbp.persistence.settings.SettingsRepository
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCoordinatorTest {
    @Test
    fun `unsafe profile requires explicit acknowledgement bound to exact authority`() = runTest {
        val profiles = FakeProfileRepository()
        val coordinator = coordinator(profiles = profiles)
        runCurrent()

        coordinator.newProfile()
        coordinator.updateProfileText(ProfileTextField.Name, "Private relay")
        coordinator.updateProfileText(ProfileTextField.Endpoint, "https://relay.example:8443/base/")
        coordinator.updateProfileText(ProfileTextField.Model, "image-model")
        coordinator.updateProfileText(ProfileTextField.ApiKey, "sentinel-secret-key")
        coordinator.updateTransportChoice(ProfileTransportChoice.UnsafeTrustAllTls)
        coordinator.saveProfile()
        runCurrent()

        assertEquals(
            SettingsFeedback.Failed(SettingsFailure.UnsafeAcknowledgementRequired),
            coordinator.state.value.feedback,
        )
        assertTrue(profiles.values.isEmpty())

        coordinator.updateUnsafeAcknowledgement(true)
        coordinator.saveProfile()
        runCurrent()

        val saved = profiles.values.single()
        val mode = saved.binding.securityMode as TransportSecurityMode.UnsafeTrustAllTls
        assertEquals(saved.id, mode.acknowledgement?.profileId)
        assertEquals(saved.binding.endpoint.authority, mode.acknowledgement?.authority)
        assertEquals(UnsafeTransportMode.TrustAllTls, mode.acknowledgement?.mode)
        assertFalse(coordinator.state.value.toString().contains("sentinel-secret-key"))
        assertFalse(coordinator.state.value.toString().contains("relay.example"))
    }

    @Test
    fun `editing a profile preserves its encrypted key when replacement stays blank`() = runTest {
        val id = ProfileId("existing-profile")
        val original = ProviderProfile(
            id = id,
            name = "Original",
            providerKind = ProviderKind.Gemini,
            binding = TransportBinding(
                profileId = id,
                endpoint = ProviderEndpoint.parse("https://example.invalid/"),
            ),
            apiKey = ApiKey("existing-secret-key"),
            model = "old-model",
            sortOrder = 0,
        )
        val profiles = FakeProfileRepository(listOf(original))
        val coordinator = coordinator(profiles = profiles)
        runCurrent()

        coordinator.editProfile(id)
        runCurrent()
        assertEquals("", coordinator.state.value.profileEditor?.apiKeyReplacement)
        coordinator.updateProfileText(ProfileTextField.Name, "Updated")
        coordinator.updateProfileText(ProfileTextField.Model, "new-model")
        coordinator.saveProfile()
        runCurrent()

        val saved = profiles.values.single()
        assertEquals("Updated", saved.name)
        assertEquals("new-model", saved.model)
        assertEquals("existing-secret-key", saved.apiKey.reveal())
    }

    @Test
    fun `prompt presets and application settings persist through their repositories`() = runTest {
        val prompts = FakePromptRepository()
        val settings = FakeSettingsRepository()
        val coordinator = coordinator(prompts = prompts, settings = settings)
        runCurrent()

        coordinator.newPrompt()
        coordinator.updatePromptName("Portrait")
        coordinator.updatePromptContent("Studio portrait with soft light")
        coordinator.savePrompt()
        coordinator.updateMaxConcurrency(2)
        coordinator.updateShowPreview(false)
        coordinator.updateCompletionNotifications(false)
        coordinator.updateSoundNotification(false)
        coordinator.updateGalleryLayoutMode(GalleryLayoutMode.List)
        runCurrent()

        assertEquals("Portrait", prompts.values.single().name)
        assertEquals("Studio portrait with soft light", prompts.values.single().content)
        assertEquals(2, settings.value.maxConcurrency)
        assertFalse(settings.value.showPreview)
        assertFalse(settings.value.completionNotifications)
        assertFalse(settings.value.soundNotification)
        assertEquals(GalleryLayoutMode.List, settings.value.galleryLayoutMode)
    }

    @Test
    fun `gallery layout applies immediately and restores for a recreated coordinator`() = runTest {
        val settings = FakeSettingsRepository()
        val first = coordinator(settings = settings)
        runCurrent()

        first.updateGalleryLayoutMode(GalleryLayoutMode.CompactGrid)
        runCurrent()

        assertEquals(GalleryLayoutMode.CompactGrid, first.state.value.appSettings.galleryLayoutMode)

        val recreated = coordinator(settings = settings)
        runCurrent()

        assertEquals(GalleryLayoutMode.CompactGrid, recreated.state.value.appSettings.galleryLayoutMode)
    }

    @Test
    fun `certificate modes reject save until valid material is imported`() = runTest {
        val profiles = FakeProfileRepository()
        val coordinator = coordinator(profiles = profiles)
        runCurrent()

        coordinator.newProfile()
        coordinator.updateProfileText(ProfileTextField.Name, "Custom CA")
        coordinator.updateProfileText(ProfileTextField.Model, "model")
        coordinator.updateProfileText(ProfileTextField.ApiKey, "key")
        coordinator.updateTransportChoice(ProfileTransportChoice.CustomCaTls)
        coordinator.saveProfile()
        runCurrent()

        assertEquals(
            SettingsFeedback.Failed(SettingsFailure.MissingCertificate),
            coordinator.state.value.feedback,
        )
        assertTrue(profiles.values.isEmpty())
    }

    @Test
    fun `missing browser becomes settings feedback and can be cleared`() = runTest {
        val coordinator = coordinator()
        runCurrent()

        coordinator.browserUnavailable()

        assertEquals(
            SettingsFeedback.Failed(SettingsFailure.BrowserUnavailable),
            coordinator.state.value.feedback,
        )

        coordinator.clearFeedback()

        assertEquals(null, coordinator.state.value.feedback)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        profiles: FakeProfileRepository = FakeProfileRepository(),
        prompts: FakePromptRepository = FakePromptRepository(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = SettingsCoordinator(
        profiles = profiles,
        prompts = prompts,
        settings = settings,
        scope = backgroundScope,
        nowEpochMillis = { 1234L },
        idGenerator = { "generated-id" },
    )
}

private class FakeProfileRepository(
    initial: List<ProviderProfile> = emptyList(),
) : ProfileRepository {
    val values = initial.toMutableList()
    private val summaries = MutableStateFlow(values.toSummaries())

    override fun observeProfiles(): Flow<List<ProfileSummary>> = summaries

    override suspend fun loadProfile(id: ProfileId): ProfileLoadResult =
        values.firstOrNull { it.id == id }?.let(ProfileLoadResult::Found)
            ?: ProfileLoadResult.NotFound

    override suspend fun saveProfile(profile: ProviderProfile): ProfileSaveResult {
        values.removeAll { it.id == profile.id }
        values += profile
        summaries.value = values.toSummaries()
        return ProfileSaveResult.Saved
    }

    override suspend fun deleteProfile(id: ProfileId): Boolean {
        val removed = values.removeAll { it.id == id }
        summaries.value = values.toSummaries()
        return removed
    }
}

private class FakePromptRepository : PromptRepository {
    val values = mutableListOf<PromptPreset>()
    private val flow = MutableStateFlow<List<PromptPreset>>(emptyList())

    override fun observePrompts(): Flow<List<PromptPreset>> = flow

    override suspend fun loadPrompt(id: PromptId): PromptPreset? =
        values.firstOrNull { it.id == id }

    override suspend fun savePrompt(prompt: PromptPreset) {
        values.removeAll { it.id == prompt.id }
        values += prompt
        flow.value = values.toList()
    }

    override suspend fun deletePrompt(id: PromptId): Boolean {
        val removed = values.removeAll { it.id == id }
        flow.value = values.toList()
        return removed
    }
}

private class FakeSettingsRepository : SettingsRepository {
    private val flow = MutableStateFlow(AppSettings())
    val value: AppSettings get() = flow.value

    override fun observeSettings(): Flow<AppSettings> = flow

    override suspend fun saveSettings(settings: AppSettings) {
        flow.value = settings
    }
}

private fun List<ProviderProfile>.toSummaries(): List<ProfileSummary> = map { profile ->
    ProfileSummary(
        id = profile.id,
        name = profile.name,
        providerKind = profile.providerKind,
        model = profile.model,
        isUnsafe = profile.binding.securityMode is TransportSecurityMode.UnsafeTrustAllTls ||
            profile.binding.securityMode is TransportSecurityMode.CleartextHttp,
        sortOrder = profile.sortOrder,
    )
}
