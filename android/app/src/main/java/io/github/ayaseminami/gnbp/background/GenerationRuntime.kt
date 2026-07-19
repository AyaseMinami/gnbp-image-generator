package io.github.ayaseminami.gnbp.background

import io.github.ayaseminami.gnbp.generation.GenerationEngine
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.ManagedGenerationEngine
import io.github.ayaseminami.gnbp.generation.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ForegroundWorkSnapshot(
    val pendingCommands: Int,
    val queuedCount: Int,
    val runningCount: Int,
) {
    val isActive: Boolean = pendingCommands > 0 || queuedCount > 0 || runningCount > 0

    companion object {
        fun from(tasks: List<GenerationTask>, pendingCommands: Int): ForegroundWorkSnapshot =
            ForegroundWorkSnapshot(
                pendingCommands = pendingCommands,
                queuedCount = tasks.count { task -> task.status == TaskStatus.Queued },
                runningCount = tasks.count { task -> task.status == TaskStatus.Running },
            )
    }
}

internal class GenerationRuntime(
    private val shutdownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val beforeEnginePublished: suspend () -> Unit = {},
    private val engineFactory: suspend () -> ManagedGenerationEngine,
) {
    private val transitionMutex = Mutex()
    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    private val mutablePendingCommands = MutableStateFlow(0)

    val pendingCommands: StateFlow<Int> = mutablePendingCommands.asStateFlow()

    suspend fun start() {
        while (true) {
            val shouldCreate = transitionMutex.withLock {
                when (mutableState.value) {
                    RuntimeState.Stopped,
                    RuntimeState.Failed,
                    -> {
                        mutableState.value = RuntimeState.Starting
                        true
                    }
                    RuntimeState.Starting,
                    is RuntimeState.Running,
                    -> return
                    is RuntimeState.Stopping -> false
                }
            }
            if (!shouldCreate) {
                mutableState.first { state -> state !is RuntimeState.Stopping }
                continue
            }
            var createdEngine: ManagedGenerationEngine? = null
            try {
                createdEngine = engineFactory()
                beforeEnginePublished()
                transitionMutex.withLock {
                    mutableState.value = RuntimeState.Running(createdEngine)
                }
                return
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    abandonStartingEngine(createdEngine, interrupted = true)
                }
                throw error
            } catch (_: Exception) {
                abandonStartingEngine(createdEngine, interrupted = true)
                throw GenerationRuntimeUnavailableException()
            }
        }
    }

    suspend fun <T> runCommand(
        startForegroundWork: () -> Unit,
        block: suspend (GenerationEngine) -> T,
    ): T {
        transitionMutex.withLock {
            mutablePendingCommands.value += 1
            if (mutableState.value == RuntimeState.Failed) {
                mutableState.value = RuntimeState.Stopped
            }
        }
        try {
            startForegroundWork()
            val engine = mutableState.first { state ->
                state is RuntimeState.Running || state == RuntimeState.Failed
            }.let { state ->
                when (state) {
                    is RuntimeState.Running -> state.engine
                    else -> throw GenerationRuntimeUnavailableException()
                }
            }
            return block(engine)
        } finally {
            transitionMutex.withLock {
                mutablePendingCommands.value = (mutablePendingCommands.value - 1).coerceAtLeast(0)
            }
        }
    }

    suspend fun stop(interrupted: Boolean): Boolean {
        while (true) {
            when (val decision = transitionMutex.withLock {
                when (val state = mutableState.value) {
                    is RuntimeState.Running -> {
                        val completion = CompletableDeferred<Boolean>()
                        mutableState.value = RuntimeState.Stopping(completion)
                        launchStop(
                            engine = state.engine,
                            interrupted = interrupted,
                            completion = completion,
                        )
                        StopDecision.Await(completion)
                    }
                    RuntimeState.Starting -> StopDecision.WaitForStart
                    is RuntimeState.Stopping -> StopDecision.Await(state.completion)
                    RuntimeState.Stopped,
                    RuntimeState.Failed,
                    -> StopDecision.AlreadyStopped
                }
            }) {
                is StopDecision.Await -> return decision.completion.await()
                StopDecision.WaitForStart -> {
                    mutableState.first { current -> current != RuntimeState.Starting }
                }
                StopDecision.AlreadyStopped -> return true
            }
        }
    }

    private suspend fun abandonStartingEngine(
        engine: ManagedGenerationEngine?,
        interrupted: Boolean,
    ) {
        transitionMutex.withLock {
            if (engine == null) {
                mutableState.value = if (interrupted) RuntimeState.Failed else RuntimeState.Stopped
            } else {
                val completion = CompletableDeferred<Boolean>()
                mutableState.value = RuntimeState.Stopping(completion)
                launchStop(engine, interrupted, completion)
            }
        }
    }

    private fun launchStop(
        engine: ManagedGenerationEngine,
        interrupted: Boolean,
        completion: CompletableDeferred<Boolean>,
    ) {
        shutdownScope.launch {
            val succeeded = try {
                if (interrupted) {
                    engine.shutdownForInterruption()
                } else {
                    engine.close()
                }
                true
            } catch (_: Exception) {
                runCatching { engine.close() }
                false
            }
            transitionMutex.withLock {
                val current = mutableState.value
                if (current is RuntimeState.Stopping && current.completion === completion) {
                    mutableState.value = if (interrupted) RuntimeState.Failed else RuntimeState.Stopped
                }
            }
            completion.complete(succeeded)
        }
    }
}

internal class GenerationRuntimeUnavailableException : IllegalStateException(
    "Generation runtime is unavailable",
)

private sealed interface RuntimeState {
    data object Stopped : RuntimeState
    data object Starting : RuntimeState
    data class Running(val engine: ManagedGenerationEngine) : RuntimeState
    data class Stopping(val completion: CompletableDeferred<Boolean>) : RuntimeState
    data object Failed : RuntimeState
}

private sealed interface StopDecision {
    data class Await(val completion: CompletableDeferred<Boolean>) : StopDecision
    data object WaitForStart : StopDecision
    data object AlreadyStopped : StopDecision
}
