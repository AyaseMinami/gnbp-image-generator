package io.github.ayaseminami.gnbp.generation

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.media.AssetReadResult
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.AssetAccessResult
import io.github.ayaseminami.gnbp.media.AssetDeleteResult
import io.github.ayaseminami.gnbp.media.AssetSaveResult
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.GeneratedAssetStore
import io.github.ayaseminami.gnbp.media.ImagePreparationFailure
import io.github.ayaseminami.gnbp.media.ImagePreparationResult
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.persistence.room.GnbpDatabase
import io.github.ayaseminami.gnbp.persistence.result.RoomGeneratedResultRepository
import io.github.ayaseminami.gnbp.persistence.task.RoomGenerationTaskRepository
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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
            resultJournal = NoOpGenerationResultJournal,
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
    fun `successful generation commits task and generated result through one completion seam`() = runTest {
        val repository = InMemoryTaskRepository()
        val completions = RecordingCompletionRepository(repository)
        val provider = SequenceProvider(
            listOf(ImageGenerationResult.Success(GeneratedImage(byteArrayOf(1, 2, 3), "image/png"))),
        )
        val journal = RecordingResultJournal()
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("completed-task"),
            resultJournal = journal,
            completionRepository = completions,
        )
        try {
            val taskId = (engine.enqueue(batchRequest()) as EnqueueResult.Accepted).taskIds.single()
            engine.observeTasks().first { tasks -> tasks.singleOrNull()?.status is TaskStatus.Succeeded }

            assertEquals(listOf(taskId), completions.committedTaskIds)
            assertEquals(listOf(taskId), journal.deletedTaskIds)
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
    fun `responded HTTP failure preserves its safe diagnostic`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = SequenceProvider(
            listOf(
                ImageGenerationResult.Failure(
                    ProviderError.HttpStatus(524, "Upstream request timed out"),
                ),
            ),
        )
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("http-failure"),
        )
        try {
            val taskId = (engine.enqueue(batchRequest()) as EnqueueResult.Accepted).taskIds.single()
            val failed = engine.observeTasks().first { tasks ->
                tasks.singleOrNull()?.status is TaskStatus.Failed
            }.single()

            assertEquals(taskId, failed.id)
            assertEquals(
                TaskStatus.Failed(
                    reason = TaskFailureReason.HttpStatus,
                    diagnostic = TaskFailureDiagnostic(
                        httpStatusCode = 524,
                        providerMessage = "Upstream request timed out",
                    ),
                ),
                failed.status,
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `possibly sent HTTP failure remains outcome unknown without diagnostics`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = SequenceProvider(
            listOf(
                ImageGenerationResult.Failure(
                    ProviderError.HttpStatus(
                        statusCode = 524,
                        providerMessage = "Do not persist this response",
                        certainty = DeliveryCertainty.PossiblySent,
                    ),
                ),
            ),
        )
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("unknown-http-outcome"),
        )
        try {
            val taskId = (engine.enqueue(batchRequest()) as EnqueueResult.Accepted).taskIds.single()
            val unknown = engine.observeTasks().first { tasks ->
                tasks.singleOrNull()?.status is TaskStatus.OutcomeUnknown
            }.single()

            assertEquals(taskId, unknown.id)
            assertEquals(
                TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProviderResponseUnknown),
                unknown.status,
            )
            assertEquals(1, provider.callCount.get())
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
    fun `startup restores a published result receipt without another provider request`() = runTest {
        val taskId = TaskId("published-before-process-death")
        val repository = InMemoryTaskRepository(
            listOf(
                GenerationTask(
                    id = taskId,
                    request = taskRequestSnapshot(),
                    status = TaskStatus.Running,
                    createdAtEpochMillis = 100L,
                    startedAtEpochMillis = 200L,
                ),
            ),
        )
        val provider = SequenceProvider(emptyList())
        val asset = AssetRef(
            id = MediaAssetId("recovered-asset"),
            uri = Uri.parse("content://gnbp/recovered-asset"),
            displayName = "recovered.png",
            mimeType = "image/png",
            byteSize = 3,
        )
        val journal = RecordingResultJournal(ResultJournalRecovery.Saved(asset))
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = emptyList(),
            resultJournal = journal,
        )
        try {
            val recovered = engine.observeTasks().first { tasks ->
                tasks.singleOrNull()?.status is TaskStatus.Succeeded
            }.single()

            val recoveredAsset = (recovered.status as TaskStatus.Succeeded).asset
            assertEquals(asset.id.value, recoveredAsset.id)
            assertEquals(asset.uri.toString(), recoveredAsset.location)
            assertEquals(0, provider.callCount.get())
            assertEquals(listOf(taskId), journal.deletedTaskIds)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `startup publishes a staged response without another provider request`() = runTest {
        val taskId = TaskId("staged-before-process-death")
        val repository = InMemoryTaskRepository(
            listOf(
                GenerationTask(
                    id = taskId,
                    request = taskRequestSnapshot(),
                    status = TaskStatus.Running,
                    createdAtEpochMillis = 100L,
                    startedAtEpochMillis = 200L,
                ),
            ),
        )
        val provider = SequenceProvider(emptyList())
        val assetStore = RecordingAssetStore()
        val metadata = GeneratedAssetMetadata("Gemini", "persisted-model")
        val journal = RecordingResultJournal(
            ResultJournalRecovery.Staged(
                image = GeneratedImage(byteArrayOf(4, 5, 6), "image/png"),
                metadata = metadata,
            ),
        )
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = emptyList(),
            assetStore = assetStore,
            resultJournal = journal,
        )
        try {
            engine.observeTasks().first { tasks ->
                tasks.singleOrNull()?.status is TaskStatus.Succeeded
            }

            assertEquals(0, provider.callCount.get())
            assertEquals(listOf(metadata), assetStore.savedMetadata)
            assertEquals(listOf(taskId), journal.deletedTaskIds)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `startup recovery commits a generated result to Room without another provider request`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val taskId = TaskId("room-recovered-task")
        val taskRepository = RoomGenerationTaskRepository(database.taskDao())
        val resultRepository = RoomGeneratedResultRepository(database.generatedResultDao())
        taskRepository.insertTasks(
            listOf(
                GenerationTask(
                    id = taskId,
                    request = taskRequestSnapshot(),
                    status = TaskStatus.Running,
                    createdAtEpochMillis = 100L,
                    startedAtEpochMillis = 200L,
                ),
            ),
        )
        val asset = AssetRef(
            id = MediaAssetId("room-recovered-asset"),
            uri = Uri.parse("content://gnbp/room-recovered-asset"),
            displayName = "room-recovered.png",
            mimeType = "image/png",
            byteSize = 3,
        )
        val provider = SequenceProvider(emptyList())
        val engine = DefaultGenerationEngine(
            taskRepository = taskRepository,
            completionRepository = taskRepository,
            providerFactory = GenerationProviderFactory { provider },
            generatedAssetStore = RecordingAssetStore(),
            referencePreparer = ReferencePreparer { error("No references expected") },
            profileLoader = { geminiProfile() },
            maxConcurrency = 1,
            externalScope = backgroundScope,
            resultJournal = RecordingResultJournal(ResultJournalRecovery.Saved(asset)),
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        try {
            engine.observeTasks().first { tasks -> tasks.singleOrNull()?.status is TaskStatus.Succeeded }
            val result = resultRepository.loadResults().single()
            assertEquals(taskId, result.sourceTaskId)
            assertEquals(asset.uri.toString(), result.asset.location)
            assertEquals(0, provider.callCount.get())
        } finally {
            engine.close()
            database.close()
        }
    }

    @Test
    fun `foreground interruption cancels active work before marking it unknown`() = runTest {
        val repository = InMemoryTaskRepository()
        val provider = CancellationAwareProvider()
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("interrupted-running-task"),
            now = { 700L },
        )
        try {
            val taskId = (engine.enqueue(batchRequest()) as EnqueueResult.Accepted).taskIds.single()
            provider.started.await()

            engine.shutdownForInterruption()

            assertEquals(
                TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProcessInterrupted),
                repository.findTask(taskId)?.status,
            )
            assertEquals(700L, repository.findTask(taskId)?.finishedAtEpochMillis)
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
            resultJournal = NoOpGenerationResultJournal,
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
    fun `concurrent retries create one direct replacement`() = runTest {
        val sourceId = TaskId("failed-source")
        val repository = InMemoryTaskRepository(
            listOf(
                GenerationTask(
                    id = sourceId,
                    request = taskRequestSnapshot().copy(
                        references = listOf(
                            ReferenceAssetSnapshot(
                                id = "durable-reference",
                                displayName = "reference.jpg",
                                mimeType = "image/jpeg",
                            ),
                        ),
                    ),
                    status = TaskStatus.Failed(TaskFailureReason.Transport),
                    createdAtEpochMillis = 100L,
                    finishedAtEpochMillis = 200L,
                ),
            ),
        )
        val preparationsStarted = Channel<Unit>(Channel.UNLIMITED)
        val allowPreparation = CompletableDeferred<Unit>()
        val provider = ControlledProvider()
        val engine = DefaultGenerationEngine(
            taskRepository = repository,
            providerFactory = GenerationProviderFactory { provider },
            generatedAssetStore = RecordingAssetStore(),
            referencePreparer = ReferencePreparer { reference ->
                preparationsStarted.send(Unit)
                allowPreparation.await()
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
            profileLoader = { geminiProfile() },
            maxConcurrency = 2,
            externalScope = backgroundScope,
            resultJournal = NoOpGenerationResultJournal,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { "retry-one" },
        )
        try {
            val firstRetry = async { engine.retry(sourceId) }
            preparationsStarted.receive()
            val secondRetry = async { engine.retry(sourceId) }
            runCurrent()
            assertTrue(preparationsStarted.tryReceive().isFailure)
            allowPreparation.complete(Unit)

            val firstResult = firstRetry.await()
            val secondResult = secondRetry.await()
            assertEquals(firstResult, secondResult)
            assertTrue(firstResult is RetryResult.Enqueued)
            provider.started.receive()
            assertTrue(provider.started.tryReceive().isFailure)

            val thirdResult = engine.retry(sourceId)
            assertEquals(firstResult, thirdResult)
            assertTrue(preparationsStarted.tryReceive().isFailure)
            assertTrue(provider.started.tryReceive().isFailure)
            assertEquals(
                1,
                repository.loadTasks().count { task -> task.sourceTaskId == sourceId },
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `concurrent engine instances return the one persisted direct replacement`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GnbpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = RoomGenerationTaskRepository(database.taskDao())
        val sourceId = TaskId("failed-source")
        repository.insertTasks(
            listOf(
                GenerationTask(
                    id = sourceId,
                    request = taskRequestSnapshot().copy(
                        references = listOf(
                            ReferenceAssetSnapshot(
                                id = "durable-reference",
                                displayName = "reference.jpg",
                                mimeType = "image/jpeg",
                            ),
                        ),
                    ),
                    status = TaskStatus.Failed(TaskFailureReason.Transport),
                    createdAtEpochMillis = 100L,
                    finishedAtEpochMillis = 200L,
                ),
            ),
        )
        val preparationsStarted = Channel<Unit>(Channel.UNLIMITED)
        val allowPreparation = CompletableDeferred<Unit>()
        val preparer = ReferencePreparer { reference ->
            preparationsStarted.send(Unit)
            allowPreparation.await()
            ImagePreparationResult.Prepared(
                reference = ReferenceImage(
                    bytes = byteArrayOf(4, 5, 6),
                    mimeType = reference.mimeType,
                    displayName = reference.displayName,
                ),
                width = 12,
                height = 12,
            )
        }
        fun newEngine(id: String) = DefaultGenerationEngine(
            taskRepository = repository,
            providerFactory = GenerationProviderFactory { ControlledProvider() },
            generatedAssetStore = RecordingAssetStore(),
            referencePreparer = preparer,
            profileLoader = { geminiProfile() },
            maxConcurrency = 1,
            externalScope = backgroundScope,
            resultJournal = NoOpGenerationResultJournal,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { id },
        )
        val firstEngine = newEngine("retry-one")
        val secondEngine = newEngine("retry-two")
        try {
            val firstRetry = async { firstEngine.retry(sourceId) }
            val secondRetry = async { secondEngine.retry(sourceId) }
            preparationsStarted.receive()
            preparationsStarted.receive()
            allowPreparation.complete(Unit)

            assertEquals(firstRetry.await(), secondRetry.await())
            assertEquals(
                1,
                repository.loadTasks().count { task -> task.sourceTaskId == sourceId },
            )
        } finally {
            firstEngine.close()
            secondEngine.close()
            database.close()
        }
    }

    @Test
    fun `failed retry preparation returns a concurrently committed replacement`() = runTest {
        val sourceId = TaskId("failed-source")
        val repository = InMemoryTaskRepository(
            listOf(
                GenerationTask(
                    id = sourceId,
                    request = taskRequestSnapshot().copy(
                        references = listOf(
                            ReferenceAssetSnapshot(
                                id = "durable-reference",
                                displayName = "reference.jpg",
                                mimeType = "image/jpeg",
                            ),
                        ),
                    ),
                    status = TaskStatus.Failed(TaskFailureReason.Transport),
                    createdAtEpochMillis = 100L,
                    finishedAtEpochMillis = 200L,
                ),
            ),
        )
        val failingPreparationStarted = CompletableDeferred<Unit>()
        val allowFailingPreparationToFinish = CompletableDeferred<Unit>()
        fun newEngine(
            preparer: ReferencePreparer,
            id: String,
        ) = DefaultGenerationEngine(
            taskRepository = repository,
            providerFactory = GenerationProviderFactory { ControlledProvider() },
            generatedAssetStore = RecordingAssetStore(),
            referencePreparer = preparer,
            profileLoader = { geminiProfile() },
            maxConcurrency = 1,
            externalScope = backgroundScope,
            resultJournal = NoOpGenerationResultJournal,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { id },
        )
        val failingEngine = newEngine(
            preparer = ReferencePreparer {
                failingPreparationStarted.complete(Unit)
                allowFailingPreparationToFinish.await()
                ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
            },
            id = "unused-replacement",
        )
        val successfulEngine = newEngine(
            preparer = ReferencePreparer { reference ->
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
            id = "committed-replacement",
        )
        try {
            val failingRetry = async { failingEngine.retry(sourceId) }
            failingPreparationStarted.await()
            val successfulResult = successfulEngine.retry(sourceId)
            allowFailingPreparationToFinish.complete(Unit)

            assertEquals(successfulResult, failingRetry.await())
            assertEquals(
                RetryResult.Enqueued(TaskId("committed-replacement")),
                successfulResult,
            )
            assertEquals(
                1,
                repository.loadTasks().count { task -> task.sourceTaskId == sourceId },
            )
        } finally {
            failingEngine.close()
            successfulEngine.close()
        }
    }

    @Test
    fun `concurrent retries share one unavailable snapshot result`() = runTest {
        val sourceId = TaskId("failed-source")
        val repository = InMemoryTaskRepository(
            listOf(
                GenerationTask(
                    id = sourceId,
                    request = taskRequestSnapshot().copy(
                        references = listOf(
                            ReferenceAssetSnapshot(
                                id = "missing-reference",
                                displayName = "reference.jpg",
                                mimeType = "image/jpeg",
                            ),
                        ),
                    ),
                    status = TaskStatus.Failed(TaskFailureReason.Transport),
                    createdAtEpochMillis = 100L,
                    finishedAtEpochMillis = 200L,
                ),
            ),
        )
        val preparationStarted = CompletableDeferred<Unit>()
        val allowPreparationToFail = CompletableDeferred<Unit>()
        val preparationCount = AtomicInteger()
        val engine = DefaultGenerationEngine(
            taskRepository = repository,
            providerFactory = GenerationProviderFactory { ControlledProvider() },
            generatedAssetStore = RecordingAssetStore(),
            referencePreparer = ReferencePreparer {
                preparationCount.incrementAndGet()
                preparationStarted.complete(Unit)
                allowPreparationToFail.await()
                ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing)
            },
            profileLoader = { geminiProfile() },
            maxConcurrency = 1,
            externalScope = backgroundScope,
            resultJournal = NoOpGenerationResultJournal,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            idGenerator = { "unused-replacement" },
        )
        try {
            val firstRetry = async { engine.retry(sourceId) }
            preparationStarted.await()
            val secondRetry = async { engine.retry(sourceId) }
            runCurrent()
            allowPreparationToFail.complete(Unit)

            assertEquals(RetryResult.SnapshotUnavailable, firstRetry.await())
            assertEquals(RetryResult.SnapshotUnavailable, secondRetry.await())
            assertEquals(1, preparationCount.get())
            assertTrue(repository.loadTasks().none { task -> task.sourceTaskId == sourceId })
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

    @Test
    fun `bulk deletion removes safe terminal tasks and preserves active and unknown tasks`() = runTest {
        val repository = InMemoryTaskRepository()
        val journal = RecordingResultJournal()
        val engine = engine(
            repository = repository,
            provider = ControlledProvider(),
            maxConcurrency = 1,
            ids = listOf("unused"),
            resultJournal = journal,
        )
        try {
            assertEquals(CancelResult.NotFound, engine.cancel(TaskId("ready")))
            val tasks = listOf(
                persistedTask("succeeded", TaskStatus.Succeeded(generatedAsset("succeeded"))),
                persistedTask("failed", TaskStatus.Failed(TaskFailureReason.Transport)),
                persistedTask(
                    "cancelled",
                    TaskStatus.Cancelled(TaskCancellationReason.UserRequested),
                ),
                persistedTask("queued", TaskStatus.Queued),
                persistedTask("running", TaskStatus.Running),
                persistedTask(
                    "unknown",
                    TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProviderResponseUnknown),
                ),
            )
            repository.insertTasks(tasks)

            val report = engine.deleteTasks(tasks.mapTo(mutableSetOf(), GenerationTask::id))

            assertEquals(
                setOf(TaskId("succeeded"), TaskId("failed"), TaskId("cancelled")),
                report.deletedTaskIds,
            )
            assertEquals(TaskDeletionBlockReason.Active, report.blockedTaskIds[TaskId("queued")])
            assertEquals(TaskDeletionBlockReason.Active, report.blockedTaskIds[TaskId("running")])
            assertEquals(
                TaskDeletionBlockReason.OutcomeUnknown,
                report.blockedTaskIds[TaskId("unknown")],
            )
            assertEquals(
                setOf(TaskId("queued"), TaskId("running"), TaskId("unknown")),
                repository.loadTasks().mapTo(mutableSetOf(), GenerationTask::id),
            )
            assertEquals(report.deletedTaskIds, journal.deletedTaskIds.toSet())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `direct replacement cannot be deleted while its retryable source remains`() = runTest {
        val source = persistedTask(
            "failed-source",
            TaskStatus.Failed(TaskFailureReason.Transport),
        )
        val replacement = persistedTask(
            "replacement",
            TaskStatus.Cancelled(TaskCancellationReason.UserRequested),
        ).copy(sourceTaskId = source.id)
        val repository = InMemoryTaskRepository(listOf(source, replacement))
        val engine = engine(
            repository = repository,
            provider = ControlledProvider(),
            maxConcurrency = 1,
            ids = listOf("second-replacement"),
        )
        try {
            val report = engine.deleteTasks(setOf(replacement.id))

            assertTrue(report.deletedTaskIds.isEmpty())
            assertEquals(
                TaskDeletionBlockReason.RetryLineage,
                report.blockedTaskIds[replacement.id],
            )
            assertEquals(RetryResult.Enqueued(replacement.id), engine.retry(source.id))
            assertEquals(2, repository.loadTasks().size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `direct replacement can be deleted after its source succeeds`() = runTest {
        val source = persistedTask(
            "succeeded-source",
            TaskStatus.Succeeded(generatedAsset("succeeded-source")),
        )
        val replacement = persistedTask(
            "failed-replacement",
            TaskStatus.Failed(TaskFailureReason.Transport),
        ).copy(sourceTaskId = source.id)
        val repository = InMemoryTaskRepository(listOf(source, replacement))
        val engine = engine(
            repository = repository,
            provider = ControlledProvider(),
            maxConcurrency = 1,
            ids = listOf("unused"),
        )
        try {
            val report = engine.deleteTasks(setOf(replacement.id))

            assertEquals(setOf(replacement.id), report.deletedTaskIds)
            assertTrue(report.blockedTaskIds.isEmpty())
            assertEquals(listOf(source), repository.loadTasks())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `deleting an asset-save failure recovers paid bytes before removing task history`() = runTest {
        val failed = persistedTask(
            "recover-before-delete",
            TaskStatus.Failed(TaskFailureReason.AssetSaveFailed),
        )
        val repository = InMemoryTaskRepository()
        val journal = RecordingResultJournal(
            ResultJournalRecovery.Staged(
                image = GeneratedImage(byteArrayOf(4, 5, 6), "image/png"),
                metadata = GeneratedAssetMetadata("Gemini", "model"),
            ),
        )
        val assetStore = RecordingAssetStore()
        val completion = RecordingCompletionRepository(repository)
        val provider = ControlledProvider()
        val engine = engine(
            repository = repository,
            provider = provider,
            maxConcurrency = 1,
            ids = listOf("unused"),
            assetStore = assetStore,
            resultJournal = journal,
            completionRepository = completion,
        )
        try {
            assertEquals(CancelResult.NotFound, engine.cancel(TaskId("ready")))
            repository.insertTasks(listOf(failed))

            val report = engine.deleteTasks(setOf(failed.id))

            assertEquals(setOf(failed.id), report.deletedTaskIds)
            assertTrue(report.blockedTaskIds.isEmpty())
            assertEquals(listOf(failed.id), completion.committedTaskIds)
            assertEquals(1, assetStore.savedMetadata.size)
            assertTrue(provider.started.tryReceive().isFailure)
            assertTrue(repository.loadTasks().isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `unrecoverable paid bytes keep an asset-save failure in task history`() = runTest {
        val failed = persistedTask(
            "pending-local-recovery",
            TaskStatus.Failed(TaskFailureReason.AssetSaveFailed),
        )
        val repository = InMemoryTaskRepository()
        val journal = RecordingResultJournal(
            ResultJournalRecovery.Staged(
                image = GeneratedImage(byteArrayOf(4, 5, 6), "image/png"),
                metadata = GeneratedAssetMetadata("Gemini", "model"),
            ),
        )
        val engine = engine(
            repository = repository,
            provider = ControlledProvider(),
            maxConcurrency = 1,
            ids = listOf("unused"),
            assetStore = ThrowingAssetStore(),
            resultJournal = journal,
        )
        try {
            assertEquals(CancelResult.NotFound, engine.cancel(TaskId("ready")))
            repository.insertTasks(listOf(failed))

            val report = engine.deleteTasks(setOf(failed.id))

            assertTrue(report.deletedTaskIds.isEmpty())
            assertEquals(
                TaskDeletionBlockReason.ResultReconciliationPending,
                report.blockedTaskIds[failed.id],
            )
            assertEquals(failed, repository.findTask(failed.id))
            assertTrue(journal.deletedTaskIds.isEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `private cleanup failure keeps terminal task history`() = runTest {
        val failed = persistedTask("cleanup-failure", TaskStatus.Failed(TaskFailureReason.Transport))
        val repository = InMemoryTaskRepository()
        val engine = engine(
            repository = repository,
            provider = ControlledProvider(),
            maxConcurrency = 1,
            ids = listOf("unused"),
            resultJournal = FailingDeletionJournal(),
        )
        try {
            assertEquals(CancelResult.NotFound, engine.cancel(TaskId("ready")))
            repository.insertTasks(listOf(failed))

            val report = engine.deleteTasks(setOf(failed.id))

            assertTrue(report.deletedTaskIds.isEmpty())
            assertEquals(
                TaskDeletionBlockReason.LocalCleanupFailed,
                report.blockedTaskIds[failed.id],
            )
            assertEquals(failed, repository.findTask(failed.id))
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
        resultJournal: GenerationResultJournal = NoOpGenerationResultJournal,
        completionRepository: GenerationCompletionRepository =
            GenerationCompletionRepository(repository::updateTask),
    ) = DefaultGenerationEngine(
        taskRepository = repository,
        completionRepository = completionRepository,
        providerFactory = GenerationProviderFactory { provider },
        generatedAssetStore = assetStore,
        referencePreparer = ReferencePreparer { error("No references expected") },
        profileLoader = { geminiProfile() },
        maxConcurrency = maxConcurrency,
        externalScope = backgroundScope,
        workerDispatcher = UnconfinedTestDispatcher(testScheduler),
        nowEpochMillis = now,
        idGenerator = ids.iterator()::next,
        resultJournal = resultJournal,
    )
}

private class RecordingResultJournal(
    private var recovery: ResultJournalRecovery = ResultJournalRecovery.None,
) : GenerationResultJournal {
    val deletedTaskIds = mutableListOf<TaskId>()

    override suspend fun stage(
        taskId: TaskId,
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): Boolean {
        recovery = ResultJournalRecovery.Staged(image, metadata)
        return true
    }

    override suspend fun recordSaved(taskId: TaskId, asset: AssetRef): Boolean {
        recovery = ResultJournalRecovery.Saved(asset)
        return true
    }

    override suspend fun load(taskId: TaskId): ResultJournalRecovery = recovery

    override suspend fun delete(taskId: TaskId) {
        deletedTaskIds += taskId
        recovery = ResultJournalRecovery.None
    }
}

private class FailingDeletionJournal : GenerationResultJournal {
    override suspend fun stage(
        taskId: TaskId,
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): Boolean = true

    override suspend fun recordSaved(taskId: TaskId, asset: AssetRef): Boolean = true

    override suspend fun load(taskId: TaskId): ResultJournalRecovery = ResultJournalRecovery.None

    override suspend fun delete(taskId: TaskId) {
        error("simulated private cleanup failure")
    }
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

    override suspend fun checkReadable(asset: AssetRef): AssetAccessResult = error("Not used")

    override suspend fun delete(asset: AssetRef): AssetDeleteResult = error("Not used")
}

private class ThrowingAssetStore : GeneratedAssetStore {
    override suspend fun save(
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): AssetSaveResult = error("simulated storage failure")

    override suspend fun read(asset: AssetRef): AssetReadResult = error("Not used")

    override suspend fun checkReadable(asset: AssetRef): AssetAccessResult = error("Not used")

    override suspend fun delete(asset: AssetRef): AssetDeleteResult = error("Not used")
}

private class InMemoryTaskRepository(
    initialTasks: List<GenerationTask> = emptyList(),
) : GenerationTaskRepository {
    private val tasks = MutableStateFlow(initialTasks)
    private val replacementMutex = Mutex()

    override fun observeTasks(): Flow<List<GenerationTask>> = tasks

    override suspend fun loadTasks(): List<GenerationTask> = tasks.value

    override suspend fun findTask(id: TaskId): GenerationTask? =
        tasks.value.firstOrNull { it.id == id }

    override suspend fun commitDirectReplacement(task: GenerationTask): DirectReplacementCommit =
        replacementMutex.withLock {
            val sourceTaskId = requireNotNull(task.sourceTaskId)
            findDirectReplacement(sourceTaskId)?.let { existing ->
                return@withLock DirectReplacementCommit.Existing(existing.id)
            }
            insertTasks(listOf(task))
            DirectReplacementCommit.Inserted(task.id)
        }

    override suspend fun insertTasks(newTasks: List<GenerationTask>) {
        tasks.value = (newTasks + tasks.value).sortedByDescending(GenerationTask::createdAtEpochMillis)
    }

    override suspend fun updateTask(task: GenerationTask) {
        tasks.value = tasks.value.map { current -> if (current.id == task.id) task else current }
    }

    override suspend fun deleteTerminalTasks(taskIds: Set<TaskId>): Set<TaskId> {
        val deletable = tasks.value
            .filter { task ->
                task.id in taskIds && when (task.status) {
                    is TaskStatus.Succeeded,
                    is TaskStatus.Failed,
                    is TaskStatus.Cancelled,
                    -> true
                    TaskStatus.Queued,
                    TaskStatus.Running,
                    is TaskStatus.OutcomeUnknown,
                    -> false
                }
            }
            .mapTo(mutableSetOf(), GenerationTask::id)
        tasks.value = tasks.value.filterNot { task -> task.id in deletable }
        return deletable
    }
}

private class RecordingCompletionRepository(
    private val tasks: InMemoryTaskRepository,
) : GenerationCompletionRepository {
    val committedTaskIds = mutableListOf<TaskId>()

    override suspend fun commitSucceededTask(task: GenerationTask) {
        tasks.updateTask(task)
        committedTaskIds += task.id
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

private fun persistedTask(id: String, status: TaskStatus) = GenerationTask(
    id = TaskId(id),
    request = taskRequestSnapshot(),
    status = status,
    createdAtEpochMillis = 100L,
    finishedAtEpochMillis = if (status == TaskStatus.Queued || status == TaskStatus.Running) null else 200L,
)

private fun generatedAsset(id: String) = GeneratedAssetReference(
    id = "asset-$id",
    location = "content://gnbp/$id",
    displayName = "$id.png",
    mimeType = "image/png",
    byteSize = 3,
)
