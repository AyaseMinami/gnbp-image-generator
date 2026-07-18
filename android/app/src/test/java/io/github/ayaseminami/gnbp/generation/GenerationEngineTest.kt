package io.github.ayaseminami.gnbp.generation

import android.net.Uri
import io.github.ayaseminami.gnbp.media.AssetReadResult
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.AssetSaveResult
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.GeneratedAssetStore
import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import io.github.ayaseminami.gnbp.provider.GenerationCancellation
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.ImageGenerationProvider
import io.github.ayaseminami.gnbp.provider.ImageGenerationRequest
import io.github.ayaseminami.gnbp.provider.ImageGenerationResult
import io.github.ayaseminami.gnbp.provider.ProviderError
import io.github.ayaseminami.gnbp.provider.ReferenceImage
import io.github.ayaseminami.gnbp.provider.transport.RequestOutcomeUnknownReason
import io.github.ayaseminami.gnbp.provider.transport.DeliveryCertainty
import io.github.ayaseminami.gnbp.provider.transport.TransportFailure
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class GenerationEngineTest {
    @Test
    fun `batch enqueue owns an immutable reference snapshot for every task`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = ControlledProvider()
        val assetStore = RecordingAssetStore()
        val sourceBytes = byteArrayOf(1, 2, 3)
        val engine = DefaultGenerationEngine(
            taskRepository = repository,
            providerFactory = GenerationProviderFactory { provider },
            generatedAssetStore = assetStore,
            referencePreparer = ReferencePreparer { asset ->
                ImagePreparationResult.Prepared(
                    reference = ReferenceImage(sourceBytes, "image/jpeg", asset.displayName),
                    width = 10,
                    height = 20,
                )
            },
            profileLoader = { geminiProfile() },
            maxConcurrency = 2,
            externalScope = backgroundScope,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            nowEpochMillis = { 1_000L },
            idGenerator = sequenceOf("task-one", "task-two").iterator()::next,
        )
        try {
            val result = engine.enqueue(
                GenerationBatchRequest(
                    profileId = geminiProfile().id,
                    prompt = "draw a lighthouse",
                    parameters = GenerationParameters.Gemini("3:4", "2K", 0.7),
                    references = listOf(referenceAsset("reference-one")),
                    count = 2,
                ),
            )

            assertTrue(result is EnqueueResult.Accepted)
            result as EnqueueResult.Accepted
            assertEquals(listOf(TaskId("task-one"), TaskId("task-two")), result.taskIds)
            val firstRequest = provider.started.receive()
            val secondRequest = provider.started.receive()
            sourceBytes[0] = 99

            assertArrayEquals(byteArrayOf(1, 2, 3), firstRequest.referenceImages.single().bytes)
            assertArrayEquals(byteArrayOf(1, 2, 3), secondRequest.referenceImages.single().bytes)
            assertNotSame(
                firstRequest.referenceImages.single().bytes,
                secondRequest.referenceImages.single().bytes,
            )
            assertNotEquals(result.taskIds[0], result.taskIds[1])

            provider.release.complete(Unit)
            val completed = engine.observeTasks().first { tasks ->
                tasks.size == 2 && tasks.all { it.status is TaskStatus.Succeeded }
            }
            assertEquals(2, completed.size)
            assertEquals(2, assetStore.savedMetadata.size)
            assertTrue(assetStore.savedMetadata.all { it.referenceDisplayNames == listOf("reference.jpg") })
        } finally {
            engine.close()
        }
    }

    @Test
    fun `workers never exceed the configured concurrency`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = ConcurrencyProvider()
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 2,
            ids = listOf("task-one", "task-two", "task-three"),
        )
        try {
            val result = engine.enqueue(batchRequest(count = 3))
            assertTrue(result is EnqueueResult.Accepted)
            provider.started.receive()
            provider.started.receive()
            assertTrue(provider.started.tryReceive().isFailure)
            assertEquals(2, provider.maximumActive.get())

            provider.releases.send(Unit)
            provider.started.receive()
            assertEquals(2, provider.maximumActive.get())
            provider.releases.send(Unit)
            provider.releases.send(Unit)

            val completed = engine.observeTasks().first { tasks ->
                tasks.size == 3 && tasks.all { it.status is TaskStatus.Succeeded }
            }
            assertEquals(3, completed.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `queued cancellation prevents a provider request`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = ControlledProvider()
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("task-one", "task-two"),
        )
        try {
            val result = engine.enqueue(batchRequest(count = 2)) as EnqueueResult.Accepted
            provider.started.receive()

            assertEquals(CancelResult.Cancelled, engine.cancel(result.taskIds[1]))
            provider.release.complete(Unit)
            val completed = engine.observeTasks().first { tasks ->
                tasks.size == 2 && tasks.none { it.status is TaskStatus.Queued || it.status is TaskStatus.Running }
            }

            assertTrue(completed.single { it.id == result.taskIds[0] }.status is TaskStatus.Succeeded)
            assertEquals(
                TaskStatus.Cancelled(TaskCancellationReason.UserRequested),
                completed.single { it.id == result.taskIds[1] }.status,
            )
            assertTrue(provider.started.tryReceive().isFailure)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `uncertain provider outcome stays terminal until explicit retry`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = SequenceProvider(
            listOf(
                ImageGenerationResult.Failure(
                    ProviderError.Transport(
                        TransportFailure.RequestOutcomeUnknown(
                            RequestOutcomeUnknownReason.ConnectionLost,
                        ),
                    ),
                ),
                ImageGenerationResult.Success(GeneratedImage(byteArrayOf(1), "image/png")),
            ),
        )
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("first-attempt", "explicit-retry"),
        )
        try {
            val originalId = (engine.enqueue(batchRequest()) as EnqueueResult.Accepted).taskIds.single()
            val unknown = engine.observeTasks().first { tasks ->
                tasks.singleOrNull()?.status is TaskStatus.OutcomeUnknown
            }.single()

            assertEquals(originalId, unknown.id)
            assertEquals(1, provider.callCount.get())
            val retry = engine.retry(originalId)
            assertEquals(RetryResult.Enqueued(TaskId("explicit-retry")), retry)

            val tasks = engine.observeTasks().first { current ->
                current.size == 2 && current.any { it.status is TaskStatus.Succeeded }
            }
            assertEquals(2, provider.callCount.get())
            assertEquals(originalId, tasks.single { it.id == TaskId("explicit-retry") }.sourceTaskId)
            assertTrue(tasks.single { it.id == originalId }.status is TaskStatus.OutcomeUnknown)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `startup reconciles running tasks and resumes queued tasks`() = runTest {
        val request = taskRequestSnapshot()
        val repository = InMemoryTaskRepository(
            listOf(
                GenerationTask(
                    id = TaskId("was-running"),
                    request = request,
                    status = TaskStatus.Running,
                    createdAtEpochMillis = 100L,
                    startedAtEpochMillis = 200L,
                ),
                GenerationTask(
                    id = TaskId("was-queued"),
                    request = request,
                    status = TaskStatus.Queued,
                    createdAtEpochMillis = 300L,
                ),
            ),
        )
        val provider = SequenceProvider(
            listOf(ImageGenerationResult.Success(GeneratedImage(byteArrayOf(1), "image/png"))),
        )
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = emptyList(),
            now = { 500L },
        )
        try {
            val reconciled = engine.observeTasks().first { tasks ->
                tasks.singleOrNull { it.id == TaskId("was-running") }?.status is TaskStatus.OutcomeUnknown &&
                    tasks.singleOrNull { it.id == TaskId("was-queued") }?.status is TaskStatus.Succeeded
            }
            assertEquals(
                TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProcessInterrupted),
                reconciled.single { it.id == TaskId("was-running") }.status,
            )
            assertTrue(reconciled.single { it.id == TaskId("was-queued") }.status is TaskStatus.Succeeded)
            assertEquals(1, provider.callCount.get())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `explicit retry rebuilds a persisted task after restart`() = runTest {
        val sourceId = TaskId("failed-before-restart")
        val request = taskRequestSnapshot().copy(
            model = "persisted-model",
            references = listOf(
                ReferenceAssetSnapshot(
                    id = "durable-reference",
                    displayName = "reference.jpg",
                    mimeType = "image/jpeg",
                ),
            ),
        )
        val repository = InMemoryTaskRepository(
            listOf(
                GenerationTask(
                    id = sourceId,
                    request = request,
                    status = TaskStatus.Failed(TaskFailureReason.Transport),
                    createdAtEpochMillis = 100L,
                    finishedAtEpochMillis = 200L,
                ),
            ),
        )
        val provider = RecordingSuccessProvider()
        val preparedIds = mutableListOf<String>()
        val engine = DefaultGenerationEngine(
            taskRepository = repository,
            providerFactory = GenerationProviderFactory { provider },
            generatedAssetStore = RecordingAssetStore(),
            referencePreparer = ReferencePreparer { reference ->
                preparedIds += reference.id
                ImagePreparationResult.Prepared(
                    reference = ReferenceImage(
                        bytes = byteArrayOf(4, 5, 6),
                        mimeType = reference.mimeType,
                        displayName = reference.displayName,
                    ),
                    width = 12,
                    height = 12,
                )
            },
            profileLoader = { geminiProfile(model = "current-profile-model") },
            maxConcurrency = 1,
            externalScope = backgroundScope,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { "retry-after-restart" },
        )
        try {
            assertEquals(
                RetryResult.Enqueued(TaskId("retry-after-restart")),
                engine.retry(sourceId),
            )
            val completed = engine.observeTasks().first { tasks ->
                tasks.any { task ->
                    task.id == TaskId("retry-after-restart") &&
                        task.status is TaskStatus.Succeeded
                }
            }
            val retried = completed.single { it.id == TaskId("retry-after-restart") }
            assertEquals(sourceId, retried.sourceTaskId)
            assertEquals(listOf("durable-reference"), preparedIds)
            val replayedRequest = provider.requests.receive()
            assertEquals("persisted-model", replayedRequest.model)
            assertArrayEquals(
                byteArrayOf(4, 5, 6),
                replayedRequest.referenceImages.single().bytes,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `asset storage failure after a provider response is a known failure`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = SequenceProvider(
            listOf(ImageGenerationResult.Success(GeneratedImage(byteArrayOf(1), "image/png"))),
        )
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("save-failure"),
            assetStore = ThrowingAssetStore(),
        )
        try {
            engine.enqueue(batchRequest())
            val task = engine.observeTasks().first { tasks ->
                tasks.singleOrNull()?.status is TaskStatus.Failed
            }.single()

            assertEquals(TaskStatus.Failed(TaskFailureReason.AssetSaveFailed), task.status)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `running cancellation reaches the provider and remains cancelled when not sent`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = CancellationAwareProvider()
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("cancel-running"),
        )
        try {
            val taskId = (engine.enqueue(batchRequest()) as EnqueueResult.Accepted).taskIds.single()
            provider.started.await()

            assertEquals(CancelResult.CancellationRequested, engine.cancel(taskId))
            val task = engine.observeTasks().first { tasks ->
                tasks.singleOrNull()?.status is TaskStatus.Cancelled
            }.single()

            assertEquals(
                TaskStatus.Cancelled(TaskCancellationReason.UserRequested),
                task.status,
            )
        } finally {
            engine.close()
        }
    }

    private fun TestScope.engine(
        repository: InMemoryTaskRepository,
        provider: ImageGenerationProvider,
        maxConcurrency: Int,
        ids: List<String>,
        now: () -> Long = { 1_000L },
        assetStore: GeneratedAssetStore = RecordingAssetStore(),
    ) = DefaultGenerationEngine(
        taskRepository = repository,
        providerFactory = GenerationProviderFactory { provider },
        generatedAssetStore = assetStore,
        referencePreparer = ReferencePreparer { error("No references expected") },
        profileLoader = { geminiProfile() },
        maxConcurrency = maxConcurrency,
        externalScope = backgroundScope,
        workerDispatcher = UnconfinedTestDispatcher(testScheduler),
        nowEpochMillis = now,
        idGenerator = ids.iterator()::next,
    )
}

private class ControlledProvider : ImageGenerationProvider {
    val started = Channel<ImageGenerationRequest>(Channel.UNLIMITED)
    val release = CompletableDeferred<Unit>()

    override suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation,
    ): ImageGenerationResult {
        started.send(request)
        release.await()
        return ImageGenerationResult.Success(GeneratedImage(byteArrayOf(7, 8, 9), "image/png"))
    }
}

private class ConcurrencyProvider : ImageGenerationProvider {
    val started = Channel<Unit>(Channel.UNLIMITED)
    val releases = Channel<Unit>(Channel.UNLIMITED)
    val maximumActive = AtomicInteger()
    private val active = AtomicInteger()

    override suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation,
    ): ImageGenerationResult {
        val current = active.incrementAndGet()
        maximumActive.accumulateAndGet(current, ::maxOf)
        started.send(Unit)
        releases.receive()
        active.decrementAndGet()
        return ImageGenerationResult.Success(GeneratedImage(byteArrayOf(1), "image/png"))
    }
}

private class SequenceProvider(
    results: List<ImageGenerationResult>,
) : ImageGenerationProvider {
    private val remaining = ArrayDeque(results)
    val callCount = AtomicInteger()

    override suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation,
    ): ImageGenerationResult {
        callCount.incrementAndGet()
        return remaining.removeFirst()
    }
}

private class RecordingSuccessProvider : ImageGenerationProvider {
    val requests = Channel<ImageGenerationRequest>(Channel.UNLIMITED)

    override suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation,
    ): ImageGenerationResult {
        requests.send(request)
        return ImageGenerationResult.Success(GeneratedImage(byteArrayOf(1), "image/png"))
    }
}

private class CancellationAwareProvider : ImageGenerationProvider {
    val started = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()

    override suspend fun generate(
        request: ImageGenerationRequest,
        cancellation: GenerationCancellation,
    ): ImageGenerationResult {
        cancellation.transportCancellation.attach { cancelled.complete(Unit) }
        started.complete(Unit)
        cancelled.await()
        return ImageGenerationResult.Failure(
            ProviderError.Transport(TransportFailure.Cancelled(DeliveryCertainty.NotSent)),
        )
    }
}

private class RecordingAssetStore : GeneratedAssetStore {
    val savedMetadata = mutableListOf<GeneratedAssetMetadata>()

    override suspend fun save(
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): AssetSaveResult {
        savedMetadata += metadata
        val index = savedMetadata.size
        return AssetSaveResult.Saved(
            AssetRef(
                id = MediaAssetId("generated-$index"),
                uri = Uri.parse("content://gnbp/generated-$index"),
                displayName = "generated-$index.png",
                mimeType = image.mimeType,
                byteSize = image.bytes.size.toLong(),
            ),
        )
    }

    override suspend fun read(asset: AssetRef): AssetReadResult = error("Not used")

    override suspend fun delete(asset: AssetRef): Boolean = error("Not used")
}

private class ThrowingAssetStore : GeneratedAssetStore {
    override suspend fun save(
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): AssetSaveResult = error("simulated storage failure")

    override suspend fun read(asset: AssetRef): AssetReadResult = error("Not used")

    override suspend fun delete(asset: AssetRef): Boolean = error("Not used")
}

private class InMemoryTaskRepository(
    initialTasks: List<GenerationTask> = emptyList(),
) : GenerationTaskRepository {
    private val tasks = MutableStateFlow(initialTasks)

    override fun observeTasks(): Flow<List<GenerationTask>> = tasks

    override suspend fun loadTasks(): List<GenerationTask> = tasks.value

    override suspend fun findTask(id: TaskId): GenerationTask? =
        tasks.value.firstOrNull { it.id == id }

    override suspend fun insertTasks(newTasks: List<GenerationTask>) {
        tasks.value = (newTasks + tasks.value).sortedByDescending(GenerationTask::createdAtEpochMillis)
    }

    override suspend fun updateTask(task: GenerationTask) {
        tasks.value = tasks.value.map { current -> if (current.id == task.id) task else current }
    }
}

private fun geminiProfile(model: String = "gemini-test"): ProviderProfile {
    val id = ProfileId("profile-one")
    return ProviderProfile(
        id = id,
        name = "Gemini",
        providerKind = ProviderKind.Gemini,
        binding = TransportBinding(
            profileId = id,
            endpoint = ProviderEndpoint.parse("https://example.invalid/relay/"),
        ),
        apiKey = ApiKey("test-key"),
        model = model,
        sortOrder = 0,
    )
}

private fun referenceAsset(id: String) = ReferenceAssetInput(
    id = id,
    displayName = "reference.jpg",
    mimeType = "image/jpeg",
)

private fun batchRequest(count: Int = 1) = GenerationBatchRequest(
    profileId = geminiProfile().id,
    prompt = "draw a lighthouse",
    parameters = GenerationParameters.Gemini("3:4", "2K", 0.7),
    count = count,
)

private fun taskRequestSnapshot() = TaskRequestSnapshot(
    profileId = ProfileId("profile-one"),
    profileName = "Gemini",
    providerKind = GenerationProviderKind.Gemini,
    model = "gemini-test",
    prompt = "draw a lighthouse",
    parameters = GenerationParameters.Gemini("3:4", "2K", 0.7),
    references = emptyList(),
)
