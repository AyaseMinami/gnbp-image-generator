package io.github.ayaseminami.gnbp.generation

import io.github.ayaseminami.gnbp.media.AssetSaveResult
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.GeneratedAssetStore
import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.ImagePreparationFailure
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.GenerationCancellation
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import io.github.ayaseminami.gnbp.provider.ImageGenerationRequest
import io.github.ayaseminami.gnbp.provider.ImageGenerationResult
import io.github.ayaseminami.gnbp.provider.ProviderError
import io.github.ayaseminami.gnbp.provider.ReferenceImage
import io.github.ayaseminami.gnbp.provider.transport.DeliveryCertainty
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

internal class DefaultGenerationEngine(
    private val taskRepository: GenerationTaskRepository,
    private val providerFactory: GenerationProviderFactory,
    private val generatedAssetStore: GeneratedAssetStore,
    private val referencePreparer: ReferencePreparer,
    private val profileLoader: suspend (ProfileId) -> ProviderProfile?,
    private val maxConcurrency: Int,
    externalScope: CoroutineScope,
    private val resultJournal: GenerationResultJournal,
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val referenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ManagedGenerationEngine {
    private val engineJob = SupervisorJob(externalScope.coroutineContext[Job])
    private val scope = CoroutineScope(externalScope.coroutineContext + engineJob + workerDispatcher)
    private val queue = Channel<TaskId>(Channel.UNLIMITED)
    private val stateMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val snapshots = mutableMapOf<TaskId, ExecutionSnapshot>()
    private val retryAttempts = mutableMapOf<TaskId, Deferred<RetryResult>>()
    private val activeCancellations = ConcurrentHashMap<TaskId, GenerationCancellation>()
    private val requestedCancellations = ConcurrentHashMap.newKeySet<TaskId>()

    init {
        require(maxConcurrency > 0) { "Maximum concurrency must be positive" }
        scope.launch { initialize() }
    }

    override fun observeTasks(): Flow<List<GenerationTask>> = taskRepository.observeTasks()

    override suspend fun enqueue(request: GenerationBatchRequest): EnqueueResult {
        ready.await()
        validate(request)?.let { return EnqueueResult.Rejected(it) }
        val profile = loadProfile(request.profileId)
            ?: return EnqueueResult.Rejected(EnqueueFailureReason.ProfileUnavailable)
        validate(request, profile.providerKind.toGenerationKind())?.let {
            return EnqueueResult.Rejected(it)
        }
        val execution = when (val built = buildExecutionSnapshot(request, profile)) {
            is ExecutionBuildResult.Ready -> built.snapshot
            ExecutionBuildResult.ProfileUnavailable ->
                return EnqueueResult.Rejected(EnqueueFailureReason.ProfileUnavailable)
            is ExecutionBuildResult.ReferenceUnavailable -> return EnqueueResult.Rejected(
                EnqueueFailureReason.ReferencePreparationFailed(built.assetId),
            )
        }
        val requestSummary = TaskRequestSnapshot(
            profileId = profile.id,
            profileName = profile.name,
            providerKind = profile.providerKind.toGenerationKind(),
            model = profile.model,
            prompt = request.prompt,
            parameters = request.parameters,
            references = request.references.map { asset ->
                ReferenceAssetSnapshot(asset.id, asset.displayName, asset.mimeType)
            },
        )
        val createdAt = nowEpochMillis()
        val newTasks = mutableListOf<GenerationTask>()
        stateMutex.withLock {
            repeat(request.count) {
                val taskId = nextUniqueTaskId(newTasks.mapTo(mutableSetOf(), GenerationTask::id))
                val task = GenerationTask(
                    id = taskId,
                    request = requestSummary,
                    status = TaskStatus.Queued,
                    createdAtEpochMillis = createdAt,
                )
                newTasks += task
                snapshots[taskId] = execution.copyOwned()
            }
            taskRepository.insertTasks(newTasks)
        }
        newTasks.forEach { task -> queue.send(task.id) }
        return EnqueueResult.Accepted(newTasks.map(GenerationTask::id))
    }

    override suspend fun cancel(id: TaskId): CancelResult {
        ready.await()
        return stateMutex.withLock {
            val task = taskRepository.findTask(id) ?: return@withLock CancelResult.NotFound
            when (task.status) {
                TaskStatus.Queued -> {
                    taskRepository.updateTask(
                        task.copy(
                            status = TaskStatus.Cancelled(TaskCancellationReason.UserRequested),
                            finishedAtEpochMillis = nowEpochMillis(),
                        ),
                    )
                    snapshots -= id
                    CancelResult.Cancelled
                }
                TaskStatus.Running -> {
                    requestedCancellations += id
                    activeCancellations[id]?.cancel()
                    CancelResult.CancellationRequested
                }
                else -> CancelResult.AlreadyFinished
            }
        }
    }

    override suspend fun retry(id: TaskId): RetryResult {
        ready.await()
        val admission = stateMutex.withLock {
            val sourceTask = taskRepository.findTask(id)
                ?: return@withLock RetryAdmission.Complete(RetryResult.NotFound)
            taskRepository.findDirectReplacement(id)?.let { existing ->
                return@withLock RetryAdmission.Complete(RetryResult.Enqueued(existing.id))
            }
            if (!sourceTask.status.isRetryable()) {
                return@withLock RetryAdmission.Complete(RetryResult.NotRetryable)
            }
            retryAttempts[id]?.let { attempt ->
                return@withLock RetryAdmission.Await(attempt)
            }
            val attempt = scope.async(start = CoroutineStart.LAZY) {
                try {
                    executeRetryAttempt(id, sourceTask)
                } finally {
                    stateMutex.withLock { retryAttempts.remove(id) }
                }
            }
            retryAttempts[id] = attempt
            attempt.start()
            RetryAdmission.Await(attempt)
        }
        return when (admission) {
            is RetryAdmission.Complete -> admission.result
            is RetryAdmission.Await -> admission.result.await()
        }
    }

    private suspend fun executeRetryAttempt(
        id: TaskId,
        sourceTask: GenerationTask,
    ): RetryResult {
        val execution = when (val built = buildExecutionSnapshot(sourceTask.request)) {
            is ExecutionBuildResult.Ready -> built.snapshot
            ExecutionBuildResult.ProfileUnavailable,
            is ExecutionBuildResult.ReferenceUnavailable,
            -> return stateMutex.withLock {
                taskRepository.findDirectReplacement(id)?.let { existing ->
                    RetryResult.Enqueued(existing.id)
                } ?: RetryResult.SnapshotUnavailable
            }
        }
        val committed = stateMutex.withLock {
            val currentSource = taskRepository.findTask(id)
                ?: return@withLock RetryCommit.NotFound
            taskRepository.findDirectReplacement(id)?.let { existing ->
                return@withLock RetryCommit.Existing(existing.id)
            }
            if (!currentSource.status.isRetryable()) {
                return@withLock RetryCommit.NotRetryable
            }
            val taskId = nextUniqueTaskId(emptySet())
            val task = GenerationTask(
                id = taskId,
                request = currentSource.request,
                status = TaskStatus.Queued,
                createdAtEpochMillis = nowEpochMillis(),
                sourceTaskId = id,
            )
            when (val commit = taskRepository.commitDirectReplacement(task)) {
                is DirectReplacementCommit.Inserted -> {
                    snapshots[commit.taskId] = execution.copyOwned()
                    RetryCommit.Created(commit.taskId)
                }
                is DirectReplacementCommit.Existing -> RetryCommit.Existing(commit.taskId)
            }
        }
        return when (committed) {
            is RetryCommit.Created -> {
                queue.send(committed.taskId)
                RetryResult.Enqueued(committed.taskId)
            }
            is RetryCommit.Existing -> RetryResult.Enqueued(committed.taskId)
            RetryCommit.NotFound -> RetryResult.NotFound
            RetryCommit.NotRetryable -> RetryResult.NotRetryable
        }
    }

    override fun close() {
        activeCancellations.values.forEach(GenerationCancellation::cancel)
        queue.close()
        scope.cancel()
    }

    override suspend fun shutdownForInterruption() {
        val interruptedTaskIds = activeCancellations.keys.toMutableSet()
        activeCancellations.values.forEach(GenerationCancellation::cancel)
        engineJob.cancelAndJoin()
        queue.close()
        stateMutex.withLock {
            taskRepository.loadTasks()
                .filter { task -> task.status == TaskStatus.Running }
                .mapTo(interruptedTaskIds, GenerationTask::id)
            interruptedTaskIds.forEach { taskId ->
                val task = taskRepository.findTask(taskId) ?: return@forEach
                if (
                    task.status is TaskStatus.Succeeded ||
                    task.status is TaskStatus.Cancelled
                ) {
                    return@forEach
                }
                val recovered = recoverJournaledResult(taskId)
                taskRepository.updateTask(
                    task.copy(
                        status = recovered ?: TaskStatus.OutcomeUnknown(
                            TaskOutcomeUnknownReason.ProcessInterrupted,
                        ),
                        finishedAtEpochMillis = nowEpochMillis(),
                    ),
                )
                if (recovered is TaskStatus.Succeeded) resultJournal.delete(taskId)
            }
            activeCancellations.clear()
            requestedCancellations.clear()
            snapshots.clear()
        }
    }

    private suspend fun initialize() {
        try {
            val queuedTasks = stateMutex.withLock {
                val queued = mutableListOf<GenerationTask>()
                taskRepository.loadTasks().forEach { task ->
                    val reconciled = when (task.status) {
                        TaskStatus.Running -> task.copy(
                            status = recoverJournaledResult(task.id)
                                ?: TaskStatus.OutcomeUnknown(
                                    TaskOutcomeUnknownReason.ProcessInterrupted,
                                ),
                            finishedAtEpochMillis = nowEpochMillis(),
                        )
                        TaskStatus.Queued -> {
                            queued += task
                            null
                        }
                        is TaskStatus.Failed -> if (
                            task.status.reason == TaskFailureReason.AssetSaveFailed
                        ) {
                            recoverJournaledResult(task.id)?.let { recovered ->
                                task.copy(
                                    status = recovered,
                                    finishedAtEpochMillis = nowEpochMillis(),
                                )
                            }
                        } else {
                            resultJournal.delete(task.id)
                            null
                        }
                        is TaskStatus.Succeeded,
                        is TaskStatus.Cancelled,
                        is TaskStatus.OutcomeUnknown,
                        -> {
                            resultJournal.delete(task.id)
                            null
                        }
                    }
                    reconciled?.let { updated ->
                        taskRepository.updateTask(updated)
                        if (updated.status is TaskStatus.Succeeded) {
                            resultJournal.delete(updated.id)
                        }
                    }
                }
                queued
            }
            repeat(maxConcurrency) {
                scope.launch { consumeQueue() }
            }
            queuedTasks.forEach { queue.send(it.id) }
            ready.complete(Unit)
        } catch (error: Exception) {
            ready.completeExceptionally(error)
        }
    }

    private suspend fun consumeQueue() {
        for (taskId in queue) execute(taskId)
    }

    private suspend fun execute(taskId: TaskId) {
        val cancellation = GenerationCancellation()
        val queuedTask = stateMutex.withLock {
            val task = taskRepository.findTask(taskId) ?: return@withLock null
            if (task.status != TaskStatus.Queued) return@withLock null
            task to snapshots[taskId]?.copyOwned()
        } ?: return

        val snapshot = when (val built = queuedTask.second?.let { ExecutionBuildResult.Ready(it) }
            ?: buildExecutionSnapshot(queuedTask.first.request)) {
            is ExecutionBuildResult.Ready -> built.snapshot
            ExecutionBuildResult.ProfileUnavailable -> {
                failBeforeStart(taskId, TaskStatus.Failed(TaskFailureReason.ProviderUnavailable))
                return
            }
            is ExecutionBuildResult.ReferenceUnavailable -> {
                failBeforeStart(taskId, TaskStatus.Failed(TaskFailureReason.ReferenceUnavailable))
                return
            }
        }
        val started = stateMutex.withLock {
            val task = taskRepository.findTask(taskId) ?: return@withLock false
            if (task.status != TaskStatus.Queued) return@withLock false
            snapshots[taskId] = snapshot.copyOwned()
            activeCancellations[taskId] = cancellation
            taskRepository.updateTask(
                task.copy(
                    status = TaskStatus.Running,
                    startedAtEpochMillis = nowEpochMillis(),
                ),
            )
            true
        }
        if (!started) return

        val provider = try {
            providerFactory.create(snapshot.profile)
        } catch (_: Exception) {
            null
        } ?: return finish(taskId, TaskStatus.Failed(TaskFailureReason.ProviderUnavailable))
        val generationResult = try {
            provider.generate(snapshot.request, cancellation)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return finish(
                taskId,
                TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProviderResponseUnknown),
            )
        }
        val finalStatus = when (generationResult) {
            is ImageGenerationResult.Success -> saveJournaledResult(
                taskId = taskId,
                image = generationResult.image,
                metadata = snapshot.metadata,
            )
            is ImageGenerationResult.Failure -> generationResult.error.toTaskStatus(
                cancellationRequested = taskId in requestedCancellations,
            )
        }
        finish(taskId, finalStatus)
    }

    private suspend fun finish(taskId: TaskId, status: TaskStatus) {
        stateMutex.withLock {
            val task = taskRepository.findTask(taskId) ?: return@withLock
            if (task.status == TaskStatus.Running) {
                taskRepository.updateTask(
                    task.copy(
                        status = status,
                        finishedAtEpochMillis = nowEpochMillis(),
                    ),
                )
                if (status is TaskStatus.Succeeded) resultJournal.delete(taskId)
            }
            activeCancellations -= taskId
            requestedCancellations -= taskId
            snapshots -= taskId
        }
    }

    private suspend fun saveJournaledResult(
        taskId: TaskId,
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): TaskStatus = try {
        if (!resultJournal.stage(taskId, image, metadata)) {
            TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
        } else {
            when (val saved = generatedAssetStore.save(image, metadata)) {
                is AssetSaveResult.Saved -> if (resultJournal.recordSaved(taskId, saved.asset)) {
                    TaskStatus.Succeeded(saved.asset.toGenerationReference())
                } else {
                    TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
                }
                is AssetSaveResult.Failed -> TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
    }

    private suspend fun recoverJournaledResult(taskId: TaskId): TaskStatus? = try {
        when (val recovery = resultJournal.load(taskId)) {
            ResultJournalRecovery.None -> null
            is ResultJournalRecovery.Saved ->
                TaskStatus.Succeeded(recovery.asset.toGenerationReference())
            is ResultJournalRecovery.Staged -> when (
                val saved = generatedAssetStore.save(recovery.image, recovery.metadata)
            ) {
                is AssetSaveResult.Saved -> if (resultJournal.recordSaved(taskId, saved.asset)) {
                    TaskStatus.Succeeded(saved.asset.toGenerationReference())
                } else {
                    TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
                }
                is AssetSaveResult.Failed -> TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun failBeforeStart(taskId: TaskId, status: TaskStatus) {
        stateMutex.withLock {
            val task = taskRepository.findTask(taskId) ?: return@withLock
            if (task.status == TaskStatus.Queued) {
                taskRepository.updateTask(
                    task.copy(status = status, finishedAtEpochMillis = nowEpochMillis()),
                )
                snapshots -= taskId
            }
        }
    }

    private suspend fun nextUniqueTaskId(reserved: Set<TaskId>): TaskId {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = TaskId(idGenerator())
            if (candidate !in reserved && taskRepository.findTask(candidate) == null) return candidate
        }
        error("Unable to allocate a unique task ID")
    }

    private fun validate(request: GenerationBatchRequest): EnqueueFailureReason? = when {
        request.prompt.isBlank() -> EnqueueFailureReason.BlankPrompt
        request.count !in 1..MAX_BATCH_COUNT -> EnqueueFailureReason.InvalidBatchCount
        else -> null
    }

    private fun validate(
        request: GenerationBatchRequest,
        providerKind: GenerationProviderKind,
    ): EnqueueFailureReason? = when {
        providerKind == GenerationProviderKind.Gemini &&
            request.parameters !is GenerationParameters.Gemini -> EnqueueFailureReason.ParameterMismatch
        providerKind == GenerationProviderKind.OpenAiCompatible &&
            request.parameters !is GenerationParameters.OpenAi -> EnqueueFailureReason.ParameterMismatch
        else -> null
    }

    private suspend fun loadProfile(profileId: ProfileId): ProviderProfile? = try {
        profileLoader(profileId)?.copyOwned()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun buildExecutionSnapshot(
        request: GenerationBatchRequest,
    ): ExecutionBuildResult {
        val profile = loadProfile(request.profileId)
            ?: return ExecutionBuildResult.ProfileUnavailable
        return buildExecutionSnapshot(request, profile)
    }

    private suspend fun buildExecutionSnapshot(
        request: GenerationBatchRequest,
        profile: ProviderProfile,
        model: String = profile.model,
        providerName: String = profile.providerKind.name,
    ): ExecutionBuildResult {
        val referencePreparation = withContext(referenceDispatcher) {
            val prepared = mutableListOf<ReferenceImage>()
            request.references.forEach { asset ->
                val result = try {
                    referencePreparer.prepare(asset)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
                }
                when (result) {
                    is ImagePreparationResult.Prepared -> prepared += result.reference.copyOwned()
                    is ImagePreparationResult.Failed -> return@withContext ReferencePreparationBatchResult.Failed(
                        asset.id,
                    )
                }
            }
            ReferencePreparationBatchResult.Prepared(prepared)
        }
        val preparedReferences = when (referencePreparation) {
            is ReferencePreparationBatchResult.Prepared -> referencePreparation.references
            is ReferencePreparationBatchResult.Failed ->
                return ExecutionBuildResult.ReferenceUnavailable(referencePreparation.assetId)
        }
        return ExecutionBuildResult.Ready(
            ExecutionSnapshot(
                profile = profile.copyOwned(),
                request = ImageGenerationRequest(
                    model = model,
                    prompt = request.prompt,
                    parameters = request.parameters,
                    referenceImages = preparedReferences.map(ReferenceImage::copyOwned),
                ),
                metadata = request.toMetadata(model, providerName),
            ),
        )
    }

    private suspend fun buildExecutionSnapshot(
        request: TaskRequestSnapshot,
    ): ExecutionBuildResult {
        val profile = loadProfile(request.profileId)
            ?: return ExecutionBuildResult.ProfileUnavailable
        if (profile.providerKind.toGenerationKind() != request.providerKind) {
            return ExecutionBuildResult.ProfileUnavailable
        }
        return buildExecutionSnapshot(
            request = GenerationBatchRequest(
                profileId = request.profileId,
                prompt = request.prompt,
                parameters = request.parameters,
                references = request.references.map { reference ->
                    ReferenceAssetInput(
                        id = reference.id,
                        displayName = reference.displayName,
                        mimeType = reference.mimeType,
                    )
                },
            ),
            profile = profile,
            model = request.model,
            providerName = request.providerKind.name,
        )
    }

    private companion object {
        const val MAX_BATCH_COUNT = 16
        const val MAX_ID_ATTEMPTS = 100
    }
}

private data class ExecutionSnapshot(
    val profile: ProviderProfile,
    val request: ImageGenerationRequest,
    val metadata: GeneratedAssetMetadata,
) {
    fun copyOwned(): ExecutionSnapshot = ExecutionSnapshot(
        profile = profile.copyOwned(),
        request = request.copy(referenceImages = request.referenceImages.map(ReferenceImage::copyOwned)),
        metadata = metadata.copy(
            parameters = metadata.parameters.toMap(),
            referenceDisplayNames = metadata.referenceDisplayNames.toList(),
        ),
    )
}

private sealed interface ReferencePreparationBatchResult {
    data class Prepared(val references: List<ReferenceImage>) : ReferencePreparationBatchResult

    data class Failed(
        val assetId: String,
    ) : ReferencePreparationBatchResult
}

private sealed interface ExecutionBuildResult {
    data class Ready(val snapshot: ExecutionSnapshot) : ExecutionBuildResult

    data object ProfileUnavailable : ExecutionBuildResult

    data class ReferenceUnavailable(val assetId: String) : ExecutionBuildResult
}

private sealed interface RetryAdmission {
    data class Complete(val result: RetryResult) : RetryAdmission

    data class Await(val result: Deferred<RetryResult>) : RetryAdmission
}

private sealed interface RetryCommit {
    data class Created(val taskId: TaskId) : RetryCommit

    data class Existing(val taskId: TaskId) : RetryCommit

    data object NotFound : RetryCommit

    data object NotRetryable : RetryCommit
}

private fun TaskStatus.isRetryable(): Boolean =
    this is TaskStatus.Failed || this is TaskStatus.Cancelled || this is TaskStatus.OutcomeUnknown

private fun ReferenceImage.copyOwned(): ReferenceImage =
    ReferenceImage(bytes.copyOf(), mimeType, displayName)

private fun ProviderProfile.copyOwned(): ProviderProfile = ProviderProfile(
    id = id,
    name = name,
    providerKind = providerKind,
    binding = TransportBinding(
        profileId = binding.profileId,
        endpoint = binding.endpoint,
        securityMode = binding.securityMode.copyOwned(),
        localNetworkMode = binding.localNetworkMode,
        policyRevision = binding.policyRevision,
    ),
    apiKey = ApiKey(apiKey.reveal()),
    model = model,
    sortOrder = sortOrder,
)

private fun TransportSecurityMode.copyOwned(): TransportSecurityMode = when (this) {
    TransportSecurityMode.VerifiedTls -> this
    is TransportSecurityMode.CustomCaTls -> TransportSecurityMode.CustomCaTls(certificates, spkiPins)
    is TransportSecurityMode.PinnedServerCertificateTls ->
        TransportSecurityMode.PinnedServerCertificateTls(certificate, allowHostnameMismatch)
    is TransportSecurityMode.UnsafeTrustAllTls -> copy()
    is TransportSecurityMode.CleartextHttp -> copy()
}

private fun GenerationBatchRequest.toMetadata(
    model: String,
    providerName: String,
): GeneratedAssetMetadata =
    GeneratedAssetMetadata(
        provider = providerName,
        model = model,
        parameters = when (val value = parameters) {
            is GenerationParameters.Gemini -> mapOf(
                "aspect_ratio" to value.aspectRatio,
                "image_size" to value.imageSize,
                "temperature" to value.temperature.toString(),
            )
            is GenerationParameters.OpenAi -> mapOf(
                "size" to value.size,
                "quality" to value.quality,
            )
        },
        referenceDisplayNames = references.map { it.displayName },
    )

private fun ProviderKind.toGenerationKind(): GenerationProviderKind = when (this) {
    ProviderKind.Gemini -> GenerationProviderKind.Gemini
    ProviderKind.OpenAiCompatible -> GenerationProviderKind.OpenAiCompatible
}

private fun AssetRef.toGenerationReference(): GeneratedAssetReference = GeneratedAssetReference(
    id = id.value,
    location = uri.toString(),
    displayName = displayName,
    mimeType = mimeType,
    byteSize = byteSize,
)

private fun ProviderError.toTaskStatus(cancellationRequested: Boolean): TaskStatus {
    if (certainty == DeliveryCertainty.PossiblySent) {
        return TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProviderResponseUnknown)
    }
    if (cancellationRequested && this is ProviderError.Transport) {
        return TaskStatus.Cancelled(TaskCancellationReason.UserRequested)
    }
    val failure = when (this) {
        is ProviderError.InvalidRequest -> TaskFailureReason.InvalidRequest
        is ProviderError.Blocked -> TaskFailureReason.Blocked
        is ProviderError.HttpStatus -> TaskFailureReason.HttpStatus
        is ProviderError.Transport -> TaskFailureReason.Transport
        is ProviderError.MalformedResponse -> TaskFailureReason.MalformedResponse
        ProviderError.NoImageData -> TaskFailureReason.NoImageData
    }
    return TaskStatus.Failed(failure)
}
