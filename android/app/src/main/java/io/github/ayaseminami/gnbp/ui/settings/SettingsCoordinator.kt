package io.github.ayaseminami.gnbp.ui.settings

import io.github.ayaseminami.gnbp.persistence.profile.ProfileLoadResult
import io.github.ayaseminami.gnbp.persistence.profile.ProfileRepository
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSaveResult
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.persistence.prompt.PromptPreset
import io.github.ayaseminami.gnbp.persistence.prompt.PromptRepository
import io.github.ayaseminami.gnbp.persistence.settings.AppSettings
import io.github.ayaseminami.gnbp.persistence.settings.SettingsRepository
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkMode
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportAcknowledgement
import io.github.ayaseminami.gnbp.provider.transport.UnsafeTransportMode
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SettingsCoordinator(
    private val profiles: ProfileRepository,
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(SettingsUiState())
    private val settingsMutex = Mutex()
    private var retainedApiKey: Pair<ProfileId, ApiKey>? = null
    private var certificateMaterial: List<ByteArray> = emptyList()

    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            profiles.observeProfiles().collect { values ->
                mutableState.update { it.copy(profiles = values) }
            }
        }
        scope.launch {
            prompts.observePrompts().collect { values ->
                mutableState.update { it.copy(prompts = values) }
            }
        }
        scope.launch {
            settings.observeSettings().collect { value ->
                mutableState.update { it.copy(appSettings = value) }
            }
        }
    }

    fun newProfile() {
        retainedApiKey = null
        certificateMaterial = emptyList()
        val sortOrder = mutableState.value.profiles.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        mutableState.update {
            it.copy(
                profileEditor = ProfileEditorState(
                    id = ProfileId(idGenerator()),
                    isNew = true,
                    sortOrder = sortOrder,
                ),
                feedback = null,
            )
        }
    }

    fun editProfile(id: ProfileId) {
        scope.launch {
            setBusy(true)
            when (val loaded = profiles.loadProfile(id)) {
                is ProfileLoadResult.Found -> {
                    val profile = loaded.profile
                    retainedApiKey = id to profile.apiKey
                    certificateMaterial = when (val mode = profile.binding.securityMode) {
                        is TransportSecurityMode.CustomCaTls -> mode.certificates.map(ByteArray::copyOf)
                        is TransportSecurityMode.PinnedServerCertificateTls -> listOf(mode.certificate.copyOf())
                        else -> emptyList()
                    }
                    mutableState.update {
                        it.copy(
                            profileEditor = profile.toEditor(certificateMaterial.size),
                            feedback = null,
                            isBusy = false,
                        )
                    }
                }
                ProfileLoadResult.SecretUnavailable -> fail(SettingsFailure.SecretUnavailable)
                ProfileLoadResult.NotFound,
                ProfileLoadResult.CorruptData,
                -> fail(SettingsFailure.ProfileUnavailable)
            }
        }
    }

    fun cancelProfileEditor() {
        retainedApiKey = null
        certificateMaterial = emptyList()
        mutableState.update { it.copy(profileEditor = null, feedback = null, isBusy = false) }
    }

    fun updateProfileText(field: ProfileTextField, value: String) = updateProfileEditor { editor ->
        when (field) {
            ProfileTextField.Name -> editor.copy(name = value)
            ProfileTextField.Endpoint -> editor.copy(endpoint = value, unsafeAcknowledged = false)
            ProfileTextField.Model -> editor.copy(model = value)
            ProfileTextField.ApiKey -> editor.copy(apiKeyReplacement = value)
            ProfileTextField.SpkiPins -> editor.copy(spkiPins = value)
        }
    }

    fun updateProviderKind(kind: ProviderKind) = updateProfileEditor { editor ->
        val oldDefault = when (editor.providerKind) {
            ProviderKind.Gemini -> ProfileEditorState.DEFAULT_GEMINI_ENDPOINT
            ProviderKind.OpenAiCompatible -> ProfileEditorState.DEFAULT_OPENAI_ENDPOINT
        }
        val newDefault = when (kind) {
            ProviderKind.Gemini -> ProfileEditorState.DEFAULT_GEMINI_ENDPOINT
            ProviderKind.OpenAiCompatible -> ProfileEditorState.DEFAULT_OPENAI_ENDPOINT
        }
        editor.copy(
            providerKind = kind,
            endpoint = if (editor.endpoint == oldDefault) newDefault else editor.endpoint,
            unsafeAcknowledged = false,
        )
    }

    fun updateTransportChoice(choice: ProfileTransportChoice) = updateProfileEditor { editor ->
        editor.copy(
            transportChoice = choice,
            allowHostnameMismatch = if (
                choice == ProfileTransportChoice.PinnedServerCertificateTls
            ) {
                editor.allowHostnameMismatch
            } else {
                false
            },
            unsafeAcknowledged = false,
        )
    }

    fun updateAllowHostnameMismatch(value: Boolean) = updateProfileEditor {
        it.copy(allowHostnameMismatch = value)
    }

    fun updateAllowLan(value: Boolean) = updateProfileEditor { it.copy(allowLan = value) }

    fun updateUnsafeAcknowledgement(value: Boolean) = updateProfileEditor {
        it.copy(unsafeAcknowledged = value)
    }

    fun importCertificate(bytes: ByteArray) {
        val editor = mutableState.value.profileEditor ?: return
        val parsed = parseCertificates(bytes) ?: return fail(SettingsFailure.InvalidCertificate)
        when (editor.transportChoice) {
            ProfileTransportChoice.CustomCaTls -> {
                if (parsed.any { it.basicConstraints < 0 }) {
                    return fail(SettingsFailure.InvalidCertificate)
                }
                certificateMaterial = certificateMaterial + bytes.copyOf()
            }
            ProfileTransportChoice.PinnedServerCertificateTls -> {
                if (parsed.size != 1) return fail(SettingsFailure.InvalidCertificate)
                certificateMaterial = listOf(bytes.copyOf())
            }
            else -> return fail(SettingsFailure.InvalidCertificate)
        }
        mutableState.update {
            it.copy(
                profileEditor = editor.copy(importedCertificateCount = certificateMaterial.size),
                feedback = SettingsFeedback.CertificateImported,
                isBusy = false,
            )
        }
    }

    fun certificateImportFailed() {
        fail(SettingsFailure.InvalidCertificate)
    }

    fun clearCertificates() {
        certificateMaterial = emptyList()
        updateProfileEditor { it.copy(importedCertificateCount = 0) }
    }

    fun saveProfile() {
        val editor = mutableState.value.profileEditor ?: return
        scope.launch {
            setBusy(true)
            val built = buildProfile(editor)
            if (built is ProfileBuildResult.Failed) return@launch fail(built.reason)
            val profile = (built as ProfileBuildResult.Ready).profile
            when (profiles.saveProfile(profile)) {
                ProfileSaveResult.Saved -> {
                    retainedApiKey = null
                    certificateMaterial = emptyList()
                    mutableState.update {
                        it.copy(
                            profileEditor = null,
                            feedback = SettingsFeedback.ProfileSaved,
                            isBusy = false,
                        )
                    }
                }
                ProfileSaveResult.AuthorityChangeRequiresSecurityReset ->
                    fail(SettingsFailure.AuthorityChangeRequiresSecurityReset)
                ProfileSaveResult.SecretUnavailable -> fail(SettingsFailure.SecretUnavailable)
            }
        }
    }

    fun deleteProfile(id: ProfileId) {
        scope.launch {
            setBusy(true)
            try {
                if (profiles.deleteProfile(id)) {
                    if (mutableState.value.appSettings.selectedProfileId == id) {
                        updateSettings { it.copy(selectedProfileId = null) }
                    }
                    mutableState.update {
                        it.copy(feedback = SettingsFeedback.ProfileDeleted, isBusy = false)
                    }
                } else {
                    fail(SettingsFailure.ProfileUnavailable)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(SettingsFailure.StorageUnavailable)
            }
        }
    }

    fun newPrompt() {
        val sortOrder = mutableState.value.prompts.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        mutableState.update {
            it.copy(
                promptEditor = PromptEditorState(
                    id = idGenerator(),
                    isNew = true,
                    sortOrder = sortOrder,
                ),
                feedback = null,
            )
        }
    }

    fun editPrompt(id: PromptId) {
        scope.launch {
            val prompt = prompts.loadPrompt(id)
            if (prompt == null) {
                fail(SettingsFailure.InvalidPrompt)
            } else {
                mutableState.update {
                    it.copy(
                        promptEditor = PromptEditorState(
                            id = prompt.id.value,
                            isNew = false,
                            name = prompt.name,
                            content = prompt.content,
                            sortOrder = prompt.sortOrder,
                        ),
                        feedback = null,
                    )
                }
            }
        }
    }

    fun updatePromptName(value: String) {
        mutableState.update { current ->
            current.copy(promptEditor = current.promptEditor?.copy(name = value), feedback = null)
        }
    }

    fun updatePromptContent(value: String) {
        mutableState.update { current ->
            current.copy(promptEditor = current.promptEditor?.copy(content = value), feedback = null)
        }
    }

    fun cancelPromptEditor() {
        mutableState.update { it.copy(promptEditor = null, feedback = null) }
    }

    fun savePrompt() {
        val editor = mutableState.value.promptEditor ?: return
        if (editor.name.isBlank() || editor.content.isBlank()) {
            return fail(SettingsFailure.InvalidPrompt)
        }
        scope.launch {
            try {
                prompts.savePrompt(
                    PromptPreset(
                        id = PromptId(editor.id),
                        name = editor.name.trim(),
                        content = editor.content.trim(),
                        sortOrder = editor.sortOrder,
                    ),
                )
                mutableState.update {
                    it.copy(promptEditor = null, feedback = SettingsFeedback.PromptSaved)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(SettingsFailure.StorageUnavailable)
            }
        }
    }

    fun deletePrompt(id: PromptId) {
        scope.launch {
            try {
                if (prompts.deletePrompt(id)) {
                    if (mutableState.value.appSettings.selectedPromptId == id) {
                        updateSettings { it.copy(selectedPromptId = null) }
                    }
                    mutableState.update { it.copy(feedback = SettingsFeedback.PromptDeleted) }
                } else {
                    fail(SettingsFailure.InvalidPrompt)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(SettingsFailure.StorageUnavailable)
            }
        }
    }

    fun updateMaxConcurrency(value: Int) = updateSettings {
        it.copy(maxConcurrency = value.coerceIn(1, AppSettings.MAX_CONCURRENCY))
    }

    fun updateShowPreview(value: Boolean) = updateSettings { it.copy(showPreview = value) }

    fun updateCompletionNotifications(value: Boolean) = updateSettings {
        it.copy(completionNotifications = value)
    }

    fun updateSoundNotification(value: Boolean) = updateSettings {
        it.copy(soundNotification = value)
    }

    fun notificationPermissionDenied() {
        val updated = mutableState.value.appSettings.copy(completionNotifications = false)
        mutableState.update {
            it.copy(
                appSettings = updated,
                feedback = SettingsFeedback.Failed(SettingsFailure.NotificationPermissionDenied),
            )
        }
        scope.launch {
            settingsMutex.withLock {
                try {
                    settings.saveSettings(updated)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    fail(SettingsFailure.StorageUnavailable)
                }
            }
        }
    }

    fun clearFeedback() {
        mutableState.update { it.copy(feedback = null) }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(mutableState.value.appSettings)
        mutableState.update { it.copy(appSettings = updated, feedback = null) }
        scope.launch {
            settingsMutex.withLock {
                try {
                    settings.saveSettings(mutableState.value.appSettings)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    fail(SettingsFailure.StorageUnavailable)
                }
            }
        }
    }

    private fun updateProfileEditor(transform: (ProfileEditorState) -> ProfileEditorState) {
        mutableState.update { current ->
            current.copy(
                profileEditor = current.profileEditor?.let(transform),
                feedback = null,
            )
        }
    }

    private fun buildProfile(editor: ProfileEditorState): ProfileBuildResult {
        if (editor.name.isBlank()) return ProfileBuildResult.Failed(SettingsFailure.InvalidName)
        if (editor.model.isBlank()) return ProfileBuildResult.Failed(SettingsFailure.InvalidModel)
        val endpoint = runCatching { ProviderEndpoint.parse(editor.endpoint.trim()) }.getOrNull()
            ?: return ProfileBuildResult.Failed(SettingsFailure.InvalidEndpoint)
        val key = runCatching {
            if (editor.apiKeyReplacement.isNotBlank()) {
                ApiKey(editor.apiKeyReplacement)
            } else {
                retainedApiKey?.takeIf { it.first == editor.id }?.second
                    ?: throw IllegalArgumentException("Missing API key")
            }
        }.getOrNull() ?: return ProfileBuildResult.Failed(SettingsFailure.InvalidApiKey)
        val pins = parsePins(editor.spkiPins)
            ?: return ProfileBuildResult.Failed(SettingsFailure.InvalidPin)
        val mode = when (editor.transportChoice) {
            ProfileTransportChoice.VerifiedTls -> {
                if (endpoint.authority.scheme != "https") {
                    return ProfileBuildResult.Failed(SettingsFailure.InvalidEndpoint)
                }
                TransportSecurityMode.VerifiedTls
            }
            ProfileTransportChoice.CustomCaTls -> {
                if (endpoint.authority.scheme != "https") {
                    return ProfileBuildResult.Failed(SettingsFailure.InvalidEndpoint)
                }
                if (certificateMaterial.isEmpty()) {
                    return ProfileBuildResult.Failed(SettingsFailure.MissingCertificate)
                }
                TransportSecurityMode.CustomCaTls(certificateMaterial, pins)
            }
            ProfileTransportChoice.PinnedServerCertificateTls -> {
                if (endpoint.authority.scheme != "https") {
                    return ProfileBuildResult.Failed(SettingsFailure.InvalidEndpoint)
                }
                val certificate = certificateMaterial.singleOrNull()
                    ?: return ProfileBuildResult.Failed(SettingsFailure.MissingCertificate)
                TransportSecurityMode.PinnedServerCertificateTls(
                    certificate = certificate,
                    allowHostnameMismatch = editor.allowHostnameMismatch,
                )
            }
            ProfileTransportChoice.UnsafeTrustAllTls -> {
                if (endpoint.authority.scheme != "https") {
                    return ProfileBuildResult.Failed(SettingsFailure.InvalidEndpoint)
                }
                if (!editor.unsafeAcknowledged) {
                    return ProfileBuildResult.Failed(SettingsFailure.UnsafeAcknowledgementRequired)
                }
                TransportSecurityMode.UnsafeTrustAllTls(
                    acknowledgement(editor.id, endpoint, UnsafeTransportMode.TrustAllTls),
                )
            }
            ProfileTransportChoice.CleartextHttp -> {
                if (endpoint.authority.scheme != "http") {
                    return ProfileBuildResult.Failed(SettingsFailure.InvalidEndpoint)
                }
                if (!editor.unsafeAcknowledged) {
                    return ProfileBuildResult.Failed(SettingsFailure.UnsafeAcknowledgementRequired)
                }
                TransportSecurityMode.CleartextHttp(
                    acknowledgement(editor.id, endpoint, UnsafeTransportMode.CleartextHttp),
                )
            }
        }
        return ProfileBuildResult.Ready(
            ProviderProfile(
                id = editor.id,
                name = editor.name.trim(),
                providerKind = editor.providerKind,
                binding = TransportBinding(
                    profileId = editor.id,
                    endpoint = endpoint,
                    securityMode = mode,
                    localNetworkMode = if (editor.allowLan) {
                        LocalNetworkMode.AllowLan
                    } else {
                        LocalNetworkMode.InternetOrLoopbackOnly
                    },
                ),
                apiKey = key,
                model = editor.model.trim(),
                sortOrder = editor.sortOrder,
            ),
        )
    }

    private fun acknowledgement(
        profileId: ProfileId,
        endpoint: ProviderEndpoint,
        mode: UnsafeTransportMode,
    ) = UnsafeTransportAcknowledgement(
        profileId = profileId,
        authority = endpoint.authority,
        mode = mode,
        policyRevision = TransportBinding.CURRENT_POLICY_REVISION,
        acceptedAtEpochMillis = nowEpochMillis().coerceAtLeast(1L),
    )

    private fun setBusy(value: Boolean) {
        mutableState.update { it.copy(isBusy = value, feedback = null) }
    }

    private fun fail(reason: SettingsFailure) {
        mutableState.update {
            it.copy(feedback = SettingsFeedback.Failed(reason), isBusy = false)
        }
    }
}

private sealed interface ProfileBuildResult {
    data class Ready(val profile: ProviderProfile) : ProfileBuildResult
    data class Failed(val reason: SettingsFailure) : ProfileBuildResult
}

private fun ProviderProfile.toEditor(certificateCount: Int): ProfileEditorState {
    val security = binding.securityMode
    return ProfileEditorState(
        id = id,
        isNew = false,
        name = name,
        providerKind = providerKind,
        endpoint = binding.endpoint.toEditableUrl(),
        model = model,
        transportChoice = when (security) {
            TransportSecurityMode.VerifiedTls -> ProfileTransportChoice.VerifiedTls
            is TransportSecurityMode.CustomCaTls -> ProfileTransportChoice.CustomCaTls
            is TransportSecurityMode.PinnedServerCertificateTls ->
                ProfileTransportChoice.PinnedServerCertificateTls
            is TransportSecurityMode.UnsafeTrustAllTls -> ProfileTransportChoice.UnsafeTrustAllTls
            is TransportSecurityMode.CleartextHttp -> ProfileTransportChoice.CleartextHttp
        },
        spkiPins = (security as? TransportSecurityMode.CustomCaTls)
            ?.spkiPins
            .orEmpty()
            .sorted()
            .joinToString("\n"),
        importedCertificateCount = certificateCount,
        allowHostnameMismatch =
            (security as? TransportSecurityMode.PinnedServerCertificateTls)
                ?.allowHostnameMismatch == true,
        allowLan = binding.localNetworkMode == LocalNetworkMode.AllowLan,
        unsafeAcknowledged = security is TransportSecurityMode.UnsafeTrustAllTls ||
            security is TransportSecurityMode.CleartextHttp,
        sortOrder = sortOrder,
    )
}

private fun ProviderEndpoint.toEditableUrl(): String = buildString {
    append(authority.scheme)
    append("://")
    if (':' in authority.asciiHost) append('[')
    append(authority.asciiHost)
    if (':' in authority.asciiHost) append(']')
    val defaultPort = if (authority.scheme == "https") 443 else 80
    if (authority.effectivePort != defaultPort) {
        append(':')
        append(authority.effectivePort)
    }
    append(basePath)
}

private fun parsePins(value: String): Set<String>? {
    val pins = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
    return pins.takeIf { values ->
        values.all { pin ->
            if (!pin.startsWith("sha256/")) return@all false
            runCatching { Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32 }
                .getOrDefault(false)
        }
    }
}

private fun parseCertificates(bytes: ByteArray): List<X509Certificate>? {
    if (bytes.decodeToString().contains("PRIVATE KEY", ignoreCase = true)) return null
    return runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
            .map { it as X509Certificate }
            .takeIf(List<X509Certificate>::isNotEmpty)
    }.getOrNull()
}
