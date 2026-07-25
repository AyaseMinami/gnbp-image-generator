package io.github.ayaseminami.gnbp.ui.generation

import android.app.Application
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ayaseminami.gnbp.background.GenerationForegroundService
import io.github.ayaseminami.gnbp.gnbpGraph
import io.github.ayaseminami.gnbp.generation.CancelResult
import io.github.ayaseminami.gnbp.generation.EnqueueFailureReason
import io.github.ayaseminami.gnbp.generation.EnqueueResult
import io.github.ayaseminami.gnbp.generation.GenerationBatchRequest
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.ReferenceAssetInput
import io.github.ayaseminami.gnbp.generation.RetryResult
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProfileLoadResult
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSummary
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.settings.AppSettings
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.persistence.prompt.PromptPreset
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResult
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import io.github.ayaseminami.gnbp.ui.settings.ProfileTextField
import io.github.ayaseminami.gnbp.ui.settings.ProfileTransportChoice
import io.github.ayaseminami.gnbp.ui.settings.SettingsCoordinator
import io.github.ayaseminami.gnbp.ui.settings.SettingsUiState
import io.github.ayaseminami.gnbp.ui.notification.TaskCompletionEvent
import io.github.ayaseminami.gnbp.ui.notification.TaskCompletionTracker
import io.github.ayaseminami.gnbp.ui.gallery.GalleryManagementCoordinator
import io.github.ayaseminami.gnbp.ui.gallery.GalleryManagementState

data class GenerationUiState(
    val profiles: List<ProfileSummary> = emptyList(),
    val selectedProfileId: ProfileId? = null,
    val prompts: List<PromptPreset> = emptyList(),
    val selectedPromptId: PromptId? = null,
    val prompt: String = "",
    val batchCount: Int = AppSettings.DEFAULT_BATCH_COUNT,
    val geminiAspectRatio: String = "3:4",
    val geminiImageSize: String = "2K",
    val geminiTemperature: Double = 0.9,
    val openAiSize: String = "auto",
    val openAiQuality: String = "auto",
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val feedback: GenerationFeedback? = null,
)

sealed interface GenerationFeedback {
    data class Queued(val count: Int) : GenerationFeedback

    data object NoProfile : GenerationFeedback

    data object ProfileUnavailable : GenerationFeedback

    data object BlankPrompt : GenerationFeedback

    data object InvalidRequest : GenerationFeedback

    data object ReferencePreparationFailed : GenerationFeedback

    data object TaskNotAvailable : GenerationFeedback

    data object PermissionDenied : GenerationFeedback

    data object ResultUnavailable : GenerationFeedback

    data object PromptCopied : GenerationFeedback
}

@SuppressLint("InlinedApi")
enum class GenerationPermission(val manifestPermission: String) {
    LegacyMediaWrite(Manifest.permission.WRITE_EXTERNAL_STORAGE),
    LocalNetwork(Manifest.permission.ACCESS_LOCAL_NETWORK),
}

class GenerationViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val applicationGraph = application.gnbpGraph
    private val persistence = applicationGraph.persistence
    private val generationRuntime = applicationGraph.generationRuntime
    private var currentSettings = AppSettings()
    private var promptEdited = false
    private val mutableUiState = MutableStateFlow(GenerationUiState())
    private val mutableTasks = MutableStateFlow<List<GenerationTask>>(emptyList())
    private val mutableGeneratedResults = MutableStateFlow<List<GeneratedResult>>(emptyList())
    private val mutablePreviewEvents = MutableSharedFlow<io.github.ayaseminami.gnbp.generation.GeneratedAssetReference>(
        extraBufferCapacity = 1,
    )
    private val mutableGalleryShareEvents = MutableSharedFlow<List<io.github.ayaseminami.gnbp.generation.GeneratedAssetReference>>(
        extraBufferCapacity = 1,
    )
    private val completionTracker = TaskCompletionTracker()
    private val settingsCoordinator = SettingsCoordinator(
        profiles = persistence.profiles,
        prompts = persistence.prompts,
        settings = persistence.settings,
        scope = viewModelScope,
    )
    private val taskManagementCoordinator = TaskManagementCoordinator(
        scope = viewModelScope,
        deleteTasks = { taskIds ->
            generationRuntime.runCommand(
                startForegroundWork = { GenerationForegroundService.start(getApplication()) },
            ) { engine -> engine.deleteTasks(taskIds) }
        },
        cleanupReleasedReferences = applicationGraph::cleanupReleasedReferences,
    )
    private val galleryManagementCoordinator = GalleryManagementCoordinator(
        scope = viewModelScope,
        findResult = persistence.generatedResults::findResult,
        removeResults = persistence.generatedResults::removeResults,
        checkAsset = applicationGraph.generatedAssetStore::checkReadable,
        deleteAsset = applicationGraph.generatedAssetStore::delete,
        shareReady = mutableGalleryShareEvents::emit,
    )

    val uiState: StateFlow<GenerationUiState> = mutableUiState.asStateFlow()
    val tasks: StateFlow<List<GenerationTask>> = mutableTasks.asStateFlow()
    val generatedResults: StateFlow<List<GeneratedResult>> = mutableGeneratedResults.asStateFlow()
    val settingsState: StateFlow<SettingsUiState> = settingsCoordinator.state
    val taskManagementState: StateFlow<TaskManagementState> = taskManagementCoordinator.state
    val galleryManagementState: StateFlow<GalleryManagementState> = galleryManagementCoordinator.state
    val previewEvents: SharedFlow<io.github.ayaseminami.gnbp.generation.GeneratedAssetReference> =
        mutablePreviewEvents.asSharedFlow()
    val galleryShareEvents: SharedFlow<List<io.github.ayaseminami.gnbp.generation.GeneratedAssetReference>> =
        mutableGalleryShareEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            persistence.profiles.observeProfiles().collect { profiles ->
                mutableUiState.update { current ->
                    val selected = current.selectedProfileId
                        ?.takeIf { id -> profiles.any { profile -> profile.id == id } }
                        ?: currentSettings.selectedProfileId
                            ?.takeIf { id -> profiles.any { profile -> profile.id == id } }
                        ?: profiles.firstOrNull()?.id
                    current.copy(
                        profiles = profiles,
                        selectedProfileId = selected,
                        isLoading = false,
                    )
                }
            }
        }
        viewModelScope.launch { initializeState(application) }
        viewModelScope.launch {
            persistence.tasks.observeTasks().collect { taskList ->
                mutableTasks.value = taskList
                completionTracker.accept(taskList).forEach { event ->
                    if (event is TaskCompletionEvent.Succeeded && currentSettings.showPreview) {
                        mutablePreviewEvents.tryEmit(event.asset)
                    }
                }
            }
        }
        viewModelScope.launch {
            persistence.prompts.observePrompts().collect { prompts ->
                mutableUiState.update { current ->
                    current.copy(prompts = prompts).restorePersistedPromptIfNeeded()
                }
            }
        }
        viewModelScope.launch {
            persistence.generatedResults.observeResults().collect { results ->
                mutableGeneratedResults.value = results
            }
        }
        viewModelScope.launch {
            persistence.settings.observeSettings().collect { settings ->
                currentSettings = settings
                mutableUiState.update { current ->
                    current.copy(
                        selectedPromptId = settings.selectedPromptId
                            ?.takeIf { id -> current.prompts.any { it.id == id } },
                    ).restorePersistedPromptIfNeeded()
                }
            }
        }
    }

    fun selectProfile(id: ProfileId) {
        mutableUiState.update { it.copy(selectedProfileId = id, feedback = null) }
    }

    fun updatePrompt(value: String) = updatePrompt(value, preserveCopiedFeedback = false)

    fun reusePrompt(value: String) = updatePrompt(value, preserveCopiedFeedback = true)

    private fun updatePrompt(value: String, preserveCopiedFeedback: Boolean) {
        val shouldClearPersistedPreset = mutableUiState.value.selectedPromptId != null ||
            currentSettings.selectedPromptId != null
        val feedback = mutableUiState.value.feedback
            ?.takeIf { preserveCopiedFeedback && it == GenerationFeedback.PromptCopied }
        promptEdited = true
        mutableUiState.update { it.copy(prompt = value, selectedPromptId = null, feedback = feedback) }
        if (shouldClearPersistedPreset) {
            currentSettings = currentSettings.copy(selectedPromptId = null)
            persistSettingsBestEffort(currentSettings)
        }
    }

    fun selectPrompt(id: PromptId?) {
        promptEdited = true
        val preset = id?.let { selected -> mutableUiState.value.prompts.firstOrNull { it.id == selected } }
        mutableUiState.update {
            it.copy(
                selectedPromptId = preset?.id,
                prompt = preset?.content ?: it.prompt,
                feedback = null,
            )
        }
        currentSettings = currentSettings.copy(selectedPromptId = preset?.id)
        persistSettingsBestEffort(currentSettings)
    }

    fun updateBatchCount(value: Int) {
        mutableUiState.update {
            it.copy(batchCount = value.coerceIn(1, AppSettings.MAX_BATCH_COUNT), feedback = null)
        }
    }

    fun updateGeminiAspectRatio(value: String) {
        mutableUiState.update { it.copy(geminiAspectRatio = value, feedback = null) }
    }

    fun updateGeminiImageSize(value: String) {
        mutableUiState.update { it.copy(geminiImageSize = value, feedback = null) }
    }

    fun updateGeminiTemperature(value: Double) {
        mutableUiState.update { it.copy(geminiTemperature = value.coerceIn(0.0, 2.0), feedback = null) }
    }

    fun updateOpenAiSize(value: String) {
        mutableUiState.update { it.copy(openAiSize = value, feedback = null) }
    }

    fun updateOpenAiQuality(value: String) {
        mutableUiState.update { it.copy(openAiQuality = value, feedback = null) }
    }

    fun submit(
        claimTaskOwnedReferences: () -> List<DurableReferenceAsset>,
        onPermissionRequired: (GenerationPermission) -> Unit,
    ) {
        if (mutableUiState.value.isSubmitting) return
        viewModelScope.launch {
            val form = mutableUiState.value
            val profileId = form.selectedProfileId
            if (profileId == null) {
                mutableUiState.update { it.copy(feedback = GenerationFeedback.NoProfile) }
                return@launch
            }
            mutableUiState.update { it.copy(isSubmitting = true, feedback = null) }
            val profile = when (val loaded = persistence.profiles.loadProfile(profileId)) {
                is ProfileLoadResult.Found -> loaded.profile
                else -> {
                    mutableUiState.update {
                        it.copy(isSubmitting = false, feedback = GenerationFeedback.ProfileUnavailable)
                    }
                    return@launch
                }
            }
            val parameters = when (profile.providerKind) {
                ProviderKind.Gemini -> GenerationParameters.Gemini(
                    aspectRatio = form.geminiAspectRatio,
                    imageSize = form.geminiImageSize,
                    temperature = form.geminiTemperature,
                )
                ProviderKind.OpenAiCompatible -> GenerationParameters.OpenAi(
                    size = form.openAiSize,
                    quality = form.openAiQuality,
                )
            }
            requiredPermission(application = getApplication(), profile = profile)?.let { permission ->
                mutableUiState.update { it.copy(isSubmitting = false) }
                onPermissionRequired(permission)
                return@launch
            }
            val result = try {
                generationRuntime.runCommand(
                    startForegroundWork = {
                        GenerationForegroundService.start(getApplication<Application>())
                    },
                ) { readyEngine ->
                    val taskOwnedReferences = claimTaskOwnedReferences()
                    readyEngine.enqueue(
                        GenerationBatchRequest(
                            profileId = profile.id,
                            prompt = form.prompt,
                            parameters = parameters,
                            references = taskOwnedReferences.map { asset ->
                                ReferenceAssetInput(
                                    id = asset.id.value,
                                    displayName = asset.displayName,
                                    mimeType = asset.mimeType,
                                )
                            },
                            count = form.batchCount,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(isSubmitting = false, feedback = GenerationFeedback.InvalidRequest)
                }
                return@launch
            }
            when (result) {
                is EnqueueResult.Accepted -> {
                    currentSettings = currentSettings.copy(
                        selectedProfileId = profileId,
                        batchCount = form.batchCount,
                    )
                    mutableUiState.update {
                        it.copy(
                            isSubmitting = false,
                            feedback = GenerationFeedback.Queued(result.taskIds.size),
                        )
                    }
                    try {
                        persistence.settings.saveSettings(currentSettings)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Unit
                    }
                }
                is EnqueueResult.Rejected -> mutableUiState.update {
                    it.copy(
                        isSubmitting = false,
                        feedback = result.reason.toFeedback(),
                    )
                }
            }
        }
    }

    fun cancel(taskId: TaskId) {
        viewModelScope.launch {
            val result = try {
                generationRuntime.runCommand(
                    startForegroundWork = { GenerationForegroundService.start(getApplication()) },
                ) { engine -> engine.cancel(taskId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(feedback = GenerationFeedback.TaskNotAvailable)
                }
                return@launch
            }
            when (result) {
                CancelResult.NotFound,
                CancelResult.AlreadyFinished,
                -> mutableUiState.update { it.copy(feedback = GenerationFeedback.TaskNotAvailable) }
                CancelResult.Cancelled,
                CancelResult.CancellationRequested,
                -> Unit
            }
        }
    }

    fun cancelTasks(taskIds: Set<TaskId>) {
        if (taskIds.isEmpty()) return
        viewModelScope.launch {
            try {
                generationRuntime.runCommand(
                    startForegroundWork = { GenerationForegroundService.start(getApplication()) },
                ) { engine -> taskIds.forEach { taskId -> engine.cancel(taskId) } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(feedback = GenerationFeedback.TaskNotAvailable)
                }
            }
        }
    }

    fun deleteTasks(
        taskIds: Set<TaskId>,
        retainedDraftAssetIds: Set<MediaAssetId>,
    ) = taskManagementCoordinator.deleteTasks(taskIds, retainedDraftAssetIds)

    fun clearTaskManagementFeedback() = taskManagementCoordinator.clearFeedback()

    fun removeGeneratedResultsFromLibrary(ids: Set<GeneratedResultId>) =
        galleryManagementCoordinator.removeFromLibrary(ids)

    fun shareGeneratedResults(ids: Set<GeneratedResultId>) =
        galleryManagementCoordinator.shareResults(ids)

    fun deleteGeneratedResultsFromDevice(ids: Set<GeneratedResultId>) =
        galleryManagementCoordinator.deleteFromDevice(ids)

    fun clearGalleryManagementFeedback() = galleryManagementCoordinator.clearFeedback()

    fun retry(taskId: TaskId) {
        viewModelScope.launch {
            val result = try {
                generationRuntime.runCommand(
                    startForegroundWork = { GenerationForegroundService.start(getApplication()) },
                ) { engine -> engine.retry(taskId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(feedback = GenerationFeedback.TaskNotAvailable)
                }
                return@launch
            }
            when (result) {
                is RetryResult.Enqueued -> Unit
                RetryResult.NotFound,
                RetryResult.NotRetryable,
                RetryResult.SnapshotUnavailable,
                -> mutableUiState.update { it.copy(feedback = GenerationFeedback.TaskNotAvailable) }
            }
        }
    }

    fun clearFeedback() {
        mutableUiState.update { it.copy(feedback = null) }
    }

    fun newProfile() = settingsCoordinator.newProfile()
    fun editProfile(id: ProfileId) = settingsCoordinator.editProfile(id)
    fun deleteProfile(id: ProfileId) = settingsCoordinator.deleteProfile(id)
    fun cancelProfileEditor() = settingsCoordinator.cancelProfileEditor()
    fun updateProfileText(field: ProfileTextField, value: String) =
        settingsCoordinator.updateProfileText(field, value)
    fun updateProviderKind(kind: ProviderKind) = settingsCoordinator.updateProviderKind(kind)
    fun updateTransportChoice(choice: ProfileTransportChoice) =
        settingsCoordinator.updateTransportChoice(choice)
    fun updateAllowHostnameMismatch(value: Boolean) =
        settingsCoordinator.updateAllowHostnameMismatch(value)
    fun updateAllowLan(value: Boolean) = settingsCoordinator.updateAllowLan(value)
    fun updateUnsafeAcknowledgement(value: Boolean) =
        settingsCoordinator.updateUnsafeAcknowledgement(value)
    fun importCertificate(bytes: ByteArray) = settingsCoordinator.importCertificate(bytes)
    fun certificateImportFailed() = settingsCoordinator.certificateImportFailed()
    fun clearCertificates() = settingsCoordinator.clearCertificates()
    fun saveProfile() = settingsCoordinator.saveProfile()
    fun newPromptPreset() = settingsCoordinator.newPrompt()
    fun editPromptPreset(id: PromptId) = settingsCoordinator.editPrompt(id)
    fun deletePromptPreset(id: PromptId) = settingsCoordinator.deletePrompt(id)
    fun updatePromptPresetName(value: String) = settingsCoordinator.updatePromptName(value)
    fun updatePromptPresetContent(value: String) = settingsCoordinator.updatePromptContent(value)
    fun cancelPromptEditor() = settingsCoordinator.cancelPromptEditor()
    fun savePromptPreset() = settingsCoordinator.savePrompt()
    fun updateMaxConcurrency(value: Int) = settingsCoordinator.updateMaxConcurrency(value)
    fun updateShowPreview(value: Boolean) = settingsCoordinator.updateShowPreview(value)
    fun updateCompletionNotifications(value: Boolean) =
        settingsCoordinator.updateCompletionNotifications(value)
    fun updateSoundNotification(value: Boolean) = settingsCoordinator.updateSoundNotification(value)
    fun updateGalleryLayoutMode(value: io.github.ayaseminami.gnbp.persistence.settings.GalleryLayoutMode) =
        settingsCoordinator.updateGalleryLayoutMode(value)
    fun notificationPermissionDenied() = settingsCoordinator.notificationPermissionDenied()
    fun clearSettingsFeedback() = settingsCoordinator.clearFeedback()

    fun permissionDenied() {
        mutableUiState.update {
            it.copy(isSubmitting = false, feedback = GenerationFeedback.PermissionDenied)
        }
    }

    fun resultUnavailable() {
        mutableUiState.update { it.copy(feedback = GenerationFeedback.ResultUnavailable) }
    }

    fun promptCopied() {
        mutableUiState.update { it.copy(feedback = GenerationFeedback.PromptCopied) }
    }

    fun setGeneratedResultFavorite(id: GeneratedResultId, favorite: Boolean) {
        viewModelScope.launch {
            val updated = try {
                persistence.generatedResults.setFavorite(id, favorite)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (!updated) resultUnavailable()
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    private suspend fun initializeState(application: Application) {
        try {
            currentSettings = persistence.settings.observeSettings().first()
            mutableUiState.update { current ->
                current.copy(
                    selectedProfileId = current.selectedProfileId ?: currentSettings.selectedProfileId,
                    selectedPromptId = currentSettings.selectedPromptId,
                    batchCount = currentSettings.batchCount,
                )
            }
            val persistedTasks = persistence.tasks.loadTasks()
            if (GenerationForegroundService.hasActiveTasks(persistedTasks)) {
                GenerationForegroundService.start(application)
            }
            applicationGraph.cleanupReferences(emptySet())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableUiState.update {
                it.copy(isLoading = false, feedback = GenerationFeedback.TaskNotAvailable)
            }
        }
    }

    private fun GenerationUiState.restorePersistedPromptIfNeeded(): GenerationUiState {
        if (promptEdited) return this
        val preset = currentSettings.selectedPromptId
            ?.let { selected -> prompts.firstOrNull { it.id == selected } }
            ?: return copy(selectedPromptId = null)
        return copy(selectedPromptId = preset.id, prompt = preset.content)
    }

    private fun persistSettingsBestEffort(snapshot: AppSettings) {
        viewModelScope.launch {
            try {
                persistence.settings.saveSettings(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
        }
    }
}

private fun requiredPermission(
    application: Application,
    profile: io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile,
): GenerationPermission? = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED -> GenerationPermission.LegacyMediaWrite
    Build.VERSION.SDK_INT >= 37 &&
        profile.binding.localNetworkMode == LocalNetworkMode.AllowLan &&
        ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) != PackageManager.PERMISSION_GRANTED -> GenerationPermission.LocalNetwork
    else -> null
}

private fun EnqueueFailureReason.toFeedback(): GenerationFeedback = when (this) {
    EnqueueFailureReason.ProfileUnavailable -> GenerationFeedback.ProfileUnavailable
    EnqueueFailureReason.BlankPrompt -> GenerationFeedback.BlankPrompt
    EnqueueFailureReason.InvalidBatchCount,
    EnqueueFailureReason.ParameterMismatch,
    -> GenerationFeedback.InvalidRequest
    is EnqueueFailureReason.ReferencePreparationFailed ->
        GenerationFeedback.ReferencePreparationFailed
}
