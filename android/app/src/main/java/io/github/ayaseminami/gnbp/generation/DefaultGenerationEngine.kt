package io.github.ayaseminami.gnbp.generation

import io.github.ayaseminami.gnbp.media.AssetSaveResult
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.GeneratedAssetStore
import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.GenerationCancellation
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.ImageGenerationRequest
import io.github.ayaseminami.gnbp.provider.ImageGenerationResult
import io.github.ayaseminami.gnbp.provider.ProviderError
import io.github.ayaseminami.gnbp.provider.ReferenceImage
import io.github.ayaseminami.gnbp.provider.transport.DeliveryCertainty
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.provider.transport.TransportSecurityMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class DefaultGenerationEngine(
    private val taskRepository: GenerationTaskRepository,
    private val providerFactory: GenerationProviderFactory,
    private val generatedAssetStore: GeneratedAssetStore,
    private val referencePreparer: ReferencePreparer,
    private val maxConcurrency: Int,
    externalScope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val referenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : GenerationEngine {
    private val engineJob = SupervisorJob(externalScope.coroutineContext[Job])
    private val scope = CoroutineScope(externalScope.coroutineContext + engineJob + workerDispatcher)
    private val queue = Channel<TaskId>(Channel.UNLIMITED)
    private val stateMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val snapshots = mutableMapOf<TaskId, ExecutionSnapshot>()
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
        val referencePreparation = withContext(referenceDispatcher) {
            val prepared = mutableListOf<ReferenceImage>()
            request.references.forEach { asset ->
                when (val result = referencePreparer.prepare(asset)) {
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
            is ReferencePreparationBatchResult.Failed -> return EnqueueResult.Rejected(
                EnqueueFailureReason.ReferencePreparationFailed(referencePreparation.assetId),
            )
        }

        val profile = request.profile.copyOwned()
        val requestSummary = TaskRequestSnapshot(
            profileId = profile.id,
            profileName = profile.name,
            providerKind = profile.providerKind,
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
                snapshots[taskId] = ExecutionSnapshot(
                    profile = profile.copyOwned(),
                    request = ImageGenerationRequest(
                        model = profile.model,
                        prompt = request.prompt,
                        parameters = request.parameters,
                        referenceImages = preparedReferences.map(ReferenceImage::copyOwned),
                    ),
                    metadata = request.toMetadata(profile),
                )
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
        val taskToQueue = stateMutex.withLock {
            val source = taskRepository.findTask(id) ?: return@withLock null to RetryResult.NotFound
            if (
                source.status !is TaskStatus.Failed &&
                source.status !is TaskStatus.Cancelled &&
                source.status !is TaskStatus.OutcomeUnknown
            ) {
                return@withLock null to RetryResult.NotRetryable
            }
            val sourceSnapshot = snapshots[id]
                ?: return@withLock null to RetryResult.SnapshotUnavailable
            val newId = nextUniqueTaskId(emptySet())
            val task = GenerationTask(
                id = newId,
                request = source.request,
                status = TaskStatus.Queued,
                createdAtEpochMillis = nowEpochMillis(),
                sourceTaskId = id,
            )
            snapshots[newId] = sourceSnapshot.copyOwned()
            taskRepository.insertTasks(listOf(task))
            task to RetryResult.Enqueued(newId)
        }
        taskToQueue.first?.let { task -> queue.send(task.id) }
        return taskToQueue.second
    }

    override fun close() {
        activeCancellations.values.forEach(GenerationCancellation::cancel)
        queue.close()
        scope.cancel()
    }

    private suspend fun initialize() {
        try {
            stateMutex.withLock {
                taskRepository.loadTasks().forEach { task ->
                    val reconciled = when (task.status) {
                        TaskStatus.Running -> task.copy(
                            status = TaskStatus.OutcomeUnknown(
                                TaskOutcomeUnknownReason.ProcessInterrupted,
                            ),
                            finishedAtEpochMillis = nowEpochMillis(),
                        )
                        TaskStatus.Queued -> task.copy(
                            status = TaskStatus.Cancelled(
                                TaskCancellationReason.ProcessInterruptedBeforeStart,
                            ),
                            finishedAtEpochMillis = nowEpochMillis(),
                        )
                        else -> null
                    }
                    reconciled?.let { taskRepository.updateTask(it) }
                }
            }
            repeat(maxConcurrency) {
                scope.launch { consumeQueue() }
            }
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
        val snapshot = stateMutex.withLock {
            val task = taskRepository.findTask(taskId) ?: return@withLock null
            val execution = snapshots[taskId]
            if (task.status != TaskStatus.Queued || execution == null) return@withLock null
            activeCancellations[taskId] = cancellation
            taskRepository.updateTask(
                task.copy(
                    status = TaskStatus.Running,
                    startedAtEpochMillis = nowEpochMillis(),
                ),
            )
            execution
        } ?: return

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
            is ImageGenerationResult.Success -> try {
                when (val saved = generatedAssetStore.save(generationResult.image, snapshot.metadata)) {
                    is AssetSaveResult.Saved -> TaskStatus.Succeeded(saved.asset)
                    is AssetSaveResult.Failed -> TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                TaskStatus.Failed(TaskFailureReason.AssetSaveFailed)
            }
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
            }
            activeCancellations -= taskId
            requestedCancellations -= taskId
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
        request.profile.providerKind == ProviderKind.Gemini &&
            request.parameters !is GenerationParameters.Gemini -> EnqueueFailureReason.ParameterMismatch
        request.profile.providerKind == ProviderKind.OpenAiCompatible &&
            request.parameters !is GenerationParameters.OpenAi -> EnqueueFailureReason.ParameterMismatch
        else -> null
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
        val assetId: MediaAssetId,
    ) : ReferencePreparationBatchResult
}

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

private fun GenerationBatchRequest.toMetadata(profile: ProviderProfile): GeneratedAssetMetadata =
    GeneratedAssetMetadata(
        provider = profile.providerKind.name,
        model = profile.model,
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
