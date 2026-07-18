package io.github.ayaseminami.gnbp.ui.generation

import android.app.Application
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ayaseminami.gnbp.generation.AndroidGenerationProviderFactory
import io.github.ayaseminami.gnbp.generation.CancelResult
import io.github.ayaseminami.gnbp.generation.DefaultGenerationEngine
import io.github.ayaseminami.gnbp.generation.EnqueueFailureReason
import io.github.ayaseminami.gnbp.generation.EnqueueResult
import io.github.ayaseminami.gnbp.generation.GenerationBatchRequest
import io.github.ayaseminami.gnbp.generation.GenerationEngine
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.ReferenceAssetInput
import io.github.ayaseminami.gnbp.generation.ReferencePreparer
import io.github.ayaseminami.gnbp.generation.RetryResult
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.media.BoundedImagePreparer
import io.github.ayaseminami.gnbp.media.ContentUriReferenceStore
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.ImagePreparationFailure
import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.media.MediaStoreGeneratedAssetStore
import io.github.ayaseminami.gnbp.persistence.GnbpPersistence
import io.github.ayaseminami.gnbp.persistence.profile.ProfileLoadResult
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSummary
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.settings.AppSettings
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.LocalNetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GenerationUiState(
    val profiles: List<ProfileSummary> = emptyList(),
    val selectedProfileId: ProfileId? = null,
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
}

@SuppressLint("InlinedApi")
enum class GenerationPermission(val manifestPermission: String) {
    LegacyMediaWrite(Manifest.permission.WRITE_EXTERNAL_STORAGE),
    LocalNetwork(Manifest.permission.ACCESS_LOCAL_NETWORK),
}

class GenerationViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val persistence = GnbpPersistence.create(application)
    private val referenceStore = ContentUriReferenceStore.create(application)
    private val engineReady = CompletableDeferred<GenerationEngine>()
    private var engine: GenerationEngine? = null
    private var currentSettings = AppSettings()
    private val mutableUiState = MutableStateFlow(GenerationUiState())
    private val mutableTasks = MutableStateFlow<List<GenerationTask>>(emptyList())

    val uiState: StateFlow<GenerationUiState> = mutableUiState.asStateFlow()
    val tasks: StateFlow<List<GenerationTask>> = mutableTasks.asStateFlow()

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
        viewModelScope.launch { initializeEngine(application) }
    }

    fun selectProfile(id: ProfileId) {
        mutableUiState.update { it.copy(selectedProfileId = id, feedback = null) }
    }

    fun updatePrompt(value: String) {
        mutableUiState.update { it.copy(prompt = value, feedback = null) }
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
        references: List<DurableReferenceAsset>,
        onAccepted: (List<DurableReferenceAsset>) -> Unit,
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
                engineReady.await().enqueue(
                    GenerationBatchRequest(
                        profileId = profile.id,
                        prompt = form.prompt,
                        parameters = parameters,
                        references = references.map { asset ->
                            ReferenceAssetInput(
                                id = asset.id.value,
                                displayName = asset.displayName,
                                mimeType = asset.mimeType,
                            )
                        },
                        count = form.batchCount,
                    ),
                )
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
                    onAccepted(references)
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
            when (engineReady.await().cancel(taskId)) {
                CancelResult.NotFound,
                CancelResult.AlreadyFinished,
                -> mutableUiState.update { it.copy(feedback = GenerationFeedback.TaskNotAvailable) }
                CancelResult.Cancelled,
                CancelResult.CancellationRequested,
                -> Unit
            }
        }
    }

    fun retry(taskId: TaskId) {
        viewModelScope.launch {
            when (engineReady.await().retry(taskId)) {
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

    fun permissionDenied() {
        mutableUiState.update {
            it.copy(isSubmitting = false, feedback = GenerationFeedback.PermissionDenied)
        }
    }

    override fun onCleared() {
        engine?.close()
        persistence.close()
        super.onCleared()
    }

    private suspend fun initializeEngine(application: Application) {
        try {
            currentSettings = persistence.settings.observeSettings().first()
            mutableUiState.update { current ->
                current.copy(
                    selectedProfileId = current.selectedProfileId ?: currentSettings.selectedProfileId,
                    batchCount = currentSettings.batchCount,
                )
            }
            val retainedAssetIds = persistence.tasks.loadTasks()
                .flatMap { task -> task.request.references }
                .map { reference -> MediaAssetId(reference.id) }
                .toSet()
            withContext(Dispatchers.IO) {
                referenceStore.cleanupOrphanedCopies(retainedAssetIds, System.currentTimeMillis())
            }
            val createdEngine = DefaultGenerationEngine(
                taskRepository = persistence.tasks,
                providerFactory = AndroidGenerationProviderFactory(application),
                generatedAssetStore = MediaStoreGeneratedAssetStore.create(application),
                referencePreparer = ReferencePreparer { asset ->
                    val durable = referenceStore.resolve(asset)
                        ?: return@ReferencePreparer ImagePreparationResult.Failed(
                            ImagePreparationFailure.SourceMissing,
                        )
                    durable.asReferenceImage(BoundedImagePreparer())
                },
                profileLoader = { profileId ->
                    when (val loaded = persistence.profiles.loadProfile(profileId)) {
                        is ProfileLoadResult.Found -> loaded.profile
                        else -> null
                    }
                },
                maxConcurrency = currentSettings.maxConcurrency,
                externalScope = viewModelScope,
            )
            engine = createdEngine
            engineReady.complete(createdEngine)
            createdEngine.observeTasks().collect { taskList -> mutableTasks.value = taskList }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            engineReady.completeExceptionally(error)
            mutableUiState.update {
                it.copy(isLoading = false, feedback = GenerationFeedback.TaskNotAvailable)
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
