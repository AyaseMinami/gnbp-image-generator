package io.github.ayaseminami.gnbp.background

import io.github.ayaseminami.gnbp.generation.CancelResult
import io.github.ayaseminami.gnbp.generation.EnqueueResult
import io.github.ayaseminami.gnbp.generation.GenerationBatchRequest
import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.ManagedGenerationEngine
import io.github.ayaseminami.gnbp.generation.RetryResult
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRuntimeTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `command lease starts foreground work before awaiting the singleton engine`() = runTest {
        val engine = RecordingManagedEngine()
        var foregroundStarted = false
        var factoryCalls = 0
        val runtime = GenerationRuntime {
            factoryCalls += 1
            engine
        }

        val command = async {
            runtime.runCommand(
                startForegroundWork = { foregroundStarted = true },
            ) { readyEngine ->
                assertTrue(readyEngine === engine)
                "completed"
            }
        }
        runCurrent()

        assertTrue(foregroundStarted)
        assertEquals(1, runtime.pendingCommands.value)
        runtime.start()

        assertEquals("completed", command.await())
        assertEquals(0, runtime.pendingCommands.value)
        runtime.start()
        assertEquals(1, factoryCalls)
    }

    @Test
    fun `interrupted stop uses the reconciliation path while idle stop only closes`() = runTest {
        val interrupted = RecordingManagedEngine()
        val interruptedRuntime = GenerationRuntime { interrupted }
        interruptedRuntime.start()

        interruptedRuntime.stop(interrupted = true)

        assertTrue(interrupted.interrupted)
        assertFalse(interrupted.closed)

        val idle = RecordingManagedEngine()
        val idleRuntime = GenerationRuntime { idle }
        idleRuntime.start()

        idleRuntime.stop(interrupted = false)

        assertFalse(idle.interrupted)
        assertTrue(idle.closed)
    }

    @Test
    fun `a command can restart the runtime after an earlier factory failure`() = runTest {
        val engine = RecordingManagedEngine()
        var factoryCalls = 0
        val runtime = GenerationRuntime {
            factoryCalls += 1
            if (factoryCalls == 1) error("first startup fails")
            engine
        }
        val starter = {
            backgroundScope.launch { runCatching { runtime.start() } }
            Unit
        }

        assertTrue(
            runCatching {
                runtime.runCommand(startForegroundWork = starter) { "not reached" }
            }.isFailure,
        )
        assertEquals(
            "recovered",
            runtime.runCommand(startForegroundWork = starter) { "recovered" },
        )
        assertEquals(2, factoryCalls)
    }

    @Test
    fun `foreground snapshot treats command preparation queued and running as active`() {
        assertFalse(ForegroundWorkSnapshot.from(emptyList(), pendingCommands = 0).isActive)
        assertTrue(ForegroundWorkSnapshot.from(emptyList(), pendingCommands = 1).isActive)

        val snapshot = ForegroundWorkSnapshot.from(
            tasks = listOf(
                task("queued", TaskStatus.Queued),
                task("running", TaskStatus.Running),
                task("finished", TaskStatus.Cancelled(
                    io.github.ayaseminami.gnbp.generation.TaskCancellationReason.UserRequested,
                )),
            ),
            pendingCommands = 0,
        )

        assertEquals(1, snapshot.queuedCount)
        assertEquals(1, snapshot.runningCount)
        assertTrue(snapshot.isActive)
    }

    private fun task(id: String, status: TaskStatus): GenerationTask = GenerationTask(
        id = TaskId(id),
        request = TaskRequestSnapshot(
            profileId = ProfileId("profile"),
            profileName = "Profile",
            providerKind = GenerationProviderKind.Gemini,
            model = "model",
            prompt = "prompt",
            parameters = GenerationParameters.Gemini("1:1", "1K", 0.9),
            references = emptyList(),
        ),
        status = status,
        createdAtEpochMillis = 1L,
    )
}

private class RecordingManagedEngine : ManagedGenerationEngine {
    var closed = false
    var interrupted = false

    override suspend fun enqueue(request: GenerationBatchRequest): EnqueueResult =
        error("Not used")

    override fun observeTasks(): Flow<List<GenerationTask>> = emptyFlow()

    override suspend fun cancel(id: TaskId): CancelResult = error("Not used")

    override suspend fun retry(id: TaskId): RetryResult = error("Not used")

    override suspend fun shutdownForInterruption() {
        interrupted = true
    }

    override fun close() {
        closed = true
    }
}
