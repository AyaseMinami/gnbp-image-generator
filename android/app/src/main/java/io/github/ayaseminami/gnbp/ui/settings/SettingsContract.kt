package io.github.ayaseminami.gnbp.ui.settings

import io.github.ayaseminami.gnbp.persistence.profile.ProfileSummary
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.prompt.PromptPreset
import io.github.ayaseminami.gnbp.persistence.settings.AppSettings
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.ui.about.AboutDestination

enum class ProfileTransportChoice {
    VerifiedTls,
    CustomCaTls,
    PinnedServerCertificateTls,
    UnsafeTrustAllTls,
    CleartextHttp,
}

enum class ProfileTextField {
    Name,
    Endpoint,
    Model,
    ApiKey,
    SpkiPins,
}

data class ProfileEditorState(
    val id: ProfileId,
    val isNew: Boolean,
    val name: String = "",
    val providerKind: ProviderKind = ProviderKind.Gemini,
    val endpoint: String = DEFAULT_GEMINI_ENDPOINT,
    val model: String = "",
    val apiKeyReplacement: String = "",
    val transportChoice: ProfileTransportChoice = ProfileTransportChoice.VerifiedTls,
    val spkiPins: String = "",
    val importedCertificateCount: Int = 0,
    val allowHostnameMismatch: Boolean = false,
    val allowLan: Boolean = false,
    val unsafeAcknowledged: Boolean = false,
    val sortOrder: Int = 0,
) {
    override fun toString(): String =
        "ProfileEditorState(id=[REDACTED], isNew=$isNew, name=[REDACTED], " +
            "providerKind=$providerKind, endpoint=[REDACTED], model=[REDACTED], " +
            "apiKeyReplacement=[REDACTED], transportChoice=$transportChoice, " +
            "spkiPins=[REDACTED], importedCertificateCount=$importedCertificateCount, " +
            "allowHostnameMismatch=$allowHostnameMismatch, allowLan=$allowLan, " +
            "unsafeAcknowledged=$unsafeAcknowledged, sortOrder=$sortOrder)"

    companion object {
        const val DEFAULT_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/"
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/"
    }
}

data class PromptEditorState(
    val id: String,
    val isNew: Boolean,
    val name: String = "",
    val content: String = "",
    val sortOrder: Int = 0,
) {
    override fun toString(): String =
        "PromptEditorState(id=[REDACTED], isNew=$isNew, name=[REDACTED], " +
            "content=[REDACTED], sortOrder=$sortOrder)"
}

enum class SettingsFailure {
    InvalidName,
    InvalidEndpoint,
    InvalidModel,
    InvalidApiKey,
    MissingCertificate,
    InvalidCertificate,
    InvalidPin,
    UnsafeAcknowledgementRequired,
    AuthorityChangeRequiresSecurityReset,
    SecretUnavailable,
    ProfileUnavailable,
    InvalidPrompt,
    StorageUnavailable,
    NotificationPermissionDenied,
    BrowserUnavailable,
}

sealed interface SettingsFeedback {
    data object ProfileSaved : SettingsFeedback
    data object ProfileDeleted : SettingsFeedback
    data object PromptSaved : SettingsFeedback
    data object PromptDeleted : SettingsFeedback
    data object CertificateImported : SettingsFeedback
    data class Failed(val reason: SettingsFailure) : SettingsFeedback
}

data class SettingsUiState(
    val profiles: List<ProfileSummary> = emptyList(),
    val prompts: List<PromptPreset> = emptyList(),
    val appSettings: AppSettings = AppSettings(),
    val profileEditor: ProfileEditorState? = null,
    val promptEditor: PromptEditorState? = null,
    val feedback: SettingsFeedback? = null,
    val isBusy: Boolean = false,
) {
    override fun toString(): String =
        "SettingsUiState(profiles=[REDACTED], prompts=[REDACTED], appSettings=$appSettings, " +
            "profileEditor=$profileEditor, promptEditor=$promptEditor, feedback=$feedback, " +
            "isBusy=$isBusy)"
}

class SettingsActions(
    val onNewProfile: () -> Unit,
    val onEditProfile: (ProfileId) -> Unit,
    val onDeleteProfile: (ProfileId) -> Unit,
    val onCancelProfileEditor: () -> Unit,
    val onUpdateProfileText: (ProfileTextField, String) -> Unit,
    val onUpdateProviderKind: (ProviderKind) -> Unit,
    val onUpdateTransportChoice: (ProfileTransportChoice) -> Unit,
    val onUpdateAllowHostnameMismatch: (Boolean) -> Unit,
    val onUpdateAllowLan: (Boolean) -> Unit,
    val onUpdateUnsafeAcknowledgement: (Boolean) -> Unit,
    val onImportCertificate: () -> Unit,
    val onClearCertificates: () -> Unit,
    val onSaveProfile: () -> Unit,
    val onNewPrompt: () -> Unit,
    val onEditPrompt: (String) -> Unit,
    val onDeletePrompt: (String) -> Unit,
    val onUpdatePromptName: (String) -> Unit,
    val onUpdatePromptContent: (String) -> Unit,
    val onCancelPromptEditor: () -> Unit,
    val onSavePrompt: () -> Unit,
    val onUpdateMaxConcurrency: (Int) -> Unit,
    val onUpdateShowPreview: (Boolean) -> Unit,
    val onUpdateCompletionNotifications: (Boolean) -> Unit,
    val onUpdateSoundNotification: (Boolean) -> Unit,
    val onOpenAboutDestination: (AboutDestination) -> Unit,
)
