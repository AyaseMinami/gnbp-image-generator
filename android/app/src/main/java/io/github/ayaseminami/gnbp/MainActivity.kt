package io.github.ayaseminami.gnbp

import android.Manifest
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.ReferenceDraftViewModel
import io.github.ayaseminami.gnbp.media.ReferenceImagePicker
import io.github.ayaseminami.gnbp.media.previewIntent
import io.github.ayaseminami.gnbp.media.shareIntent
import io.github.ayaseminami.gnbp.media.toAssetRef
import android.content.Intent
import io.github.ayaseminami.gnbp.ui.generation.GenerationApp
import io.github.ayaseminami.gnbp.ui.generation.GenerationPermission
import io.github.ayaseminami.gnbp.ui.generation.GenerationViewModel
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.ui.settings.CertificateDocumentPicker
import io.github.ayaseminami.gnbp.ui.settings.SettingsActions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val referenceDraft by viewModels<ReferenceDraftViewModel>()
    private val generation by viewModels<GenerationViewModel>()
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                referenceDraft.consumePendingPermissionSubmission()?.let(::submit)
            } else {
                referenceDraft.discardPendingPermissionSubmission()
                generation.permissionDenied()
            }
        }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) generation.notificationPermissionDenied()
        }
        val referencePicker = ReferenceImagePicker(
            activity = this,
            onPicked = referenceDraft::importPickedUris,
        )
        val certificatePicker = CertificateDocumentPicker(
            activity = this,
            onImported = generation::importCertificate,
            onFailed = generation::certificateImportFailed,
        )
        setContent {
            val draftState by referenceDraft.state.collectAsState()
            val generationState by generation.uiState.collectAsState()
            val tasks by generation.tasks.collectAsState()
            val settingsState by generation.settingsState.collectAsState()
            val settingsActions = remember {
                SettingsActions(
                    onNewProfile = generation::newProfile,
                    onEditProfile = generation::editProfile,
                    onDeleteProfile = generation::deleteProfile,
                    onCancelProfileEditor = generation::cancelProfileEditor,
                    onUpdateProfileText = generation::updateProfileText,
                    onUpdateProviderKind = generation::updateProviderKind,
                    onUpdateTransportChoice = generation::updateTransportChoice,
                    onUpdateAllowHostnameMismatch = generation::updateAllowHostnameMismatch,
                    onUpdateAllowLan = generation::updateAllowLan,
                    onUpdateUnsafeAcknowledgement = generation::updateUnsafeAcknowledgement,
                    onImportCertificate = certificatePicker::launch,
                    onClearCertificates = generation::clearCertificates,
                    onSaveProfile = generation::saveProfile,
                    onNewPrompt = generation::newPromptPreset,
                    onEditPrompt = { generation.editPromptPreset(PromptId(it)) },
                    onDeletePrompt = { generation.deletePromptPreset(PromptId(it)) },
                    onUpdatePromptName = generation::updatePromptPresetName,
                    onUpdatePromptContent = generation::updatePromptPresetContent,
                    onCancelPromptEditor = generation::cancelPromptEditor,
                    onSavePrompt = generation::savePromptPreset,
                    onUpdateMaxConcurrency = generation::updateMaxConcurrency,
                    onUpdateShowPreview = generation::updateShowPreview,
                    onUpdateCompletionNotifications = ::updateCompletionNotifications,
                    onUpdateSoundNotification = generation::updateSoundNotification,
                )
            }
            GenerationApp(
                state = generationState,
                settingsState = settingsState,
                settingsActions = settingsActions,
                tasks = tasks,
                references = draftState.assets,
                failedReferenceCount = draftState.failedImportCount,
                onSelectProfile = generation::selectProfile,
                onSelectPrompt = generation::selectPrompt,
                onPromptChange = generation::updatePrompt,
                onBatchCountChange = generation::updateBatchCount,
                onGeminiAspectRatioChange = generation::updateGeminiAspectRatio,
                onGeminiImageSizeChange = generation::updateGeminiImageSize,
                onGeminiTemperatureChange = generation::updateGeminiTemperature,
                onOpenAiSizeChange = generation::updateOpenAiSize,
                onOpenAiQualityChange = generation::updateOpenAiQuality,
                onPickReferences = referencePicker::launch,
                onRemoveReference = referenceDraft::remove,
                onSubmit = { submit(draftState.assets) },
                onCancelTask = generation::cancel,
                onRetryTask = generation::retry,
                onOpenResult = ::openResult,
                onShareResult = ::shareResult,
                onReuseResult = ::reuseResult,
                onFeedbackShown = generation::clearFeedback,
                onSettingsFeedbackShown = generation::clearSettingsFeedback,
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                generation.previewEvents.collect(::openResult)
            }
        }
    }

    private fun submit(references: List<DurableReferenceAsset>) {
        generation.submit(
            claimTaskOwnedReferences = { referenceDraft.claimForTask(references) },
            onPermissionRequired = { permission -> requestPermission(permission, references) },
        )
    }

    private fun requestPermission(
        permission: GenerationPermission,
        references: List<DurableReferenceAsset>,
    ) {
        referenceDraft.retainPendingPermissionSubmission(references)
        permissionLauncher.launch(permission.manifestPermission)
    }

    private fun openResult(asset: GeneratedAssetReference) {
        runCatching { startActivity(asset.toAssetRef().previewIntent()) }
            .onFailure { generation.resultUnavailable() }
    }

    private fun shareResult(asset: GeneratedAssetReference) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    asset.toAssetRef().shareIntent(),
                    getString(R.string.share_chooser_title),
                ),
            )
        }.onFailure { generation.resultUnavailable() }
    }

    private fun reuseResult(asset: GeneratedAssetReference) {
        referenceDraft.importPickedUris(listOf(asset.location.toUri()))
    }

    private fun updateCompletionNotifications(enabled: Boolean) {
        generation.updateCompletionNotifications(enabled)
        if (
            enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
