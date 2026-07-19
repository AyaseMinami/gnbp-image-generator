package io.github.ayaseminami.gnbp.background

import io.github.ayaseminami.gnbp.generation.GenerationEngine
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.ManagedGenerationEngine
import io.github.ayaseminami.gnbp.generation.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
                    RuntimeState.Stopping -> false
                }
            }
            if (!shouldCreate) {
                mutableState.first { state -> state != RuntimeState.Stopping }
                continue
            }
            try {
                val engine = engineFactory()
                transitionMutex.withLock {
                    mutableState.value = RuntimeState.Running(engine)
                }
                return
            } catch (error: CancellationException) {
                transitionMutex.withLock { mutableState.value = RuntimeState.Stopped }
                throw error
            } catch (_: Exception) {
                transitionMutex.withLock { mutableState.value = RuntimeState.Failed }
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

    suspend fun stop(interrupted: Boolean) {
        var engine: ManagedGenerationEngine? = null
        while (engine == null) {
            when (val decision = transitionMutex.withLock {
                when (val state = mutableState.value) {
                    is RuntimeState.Running -> {
                        mutableState.value = RuntimeState.Stopping
                        StopDecision.Stop(state.engine)
                    }
                    RuntimeState.Starting -> StopDecision.WaitForStart
                    RuntimeState.Stopped,
                    RuntimeState.Stopping,
                    RuntimeState.Failed,
                    -> StopDecision.AlreadyStopped
                }
            }) {
                is StopDecision.Stop -> engine = decision.engine
                StopDecision.WaitForStart -> {
                    mutableState.first { current -> current != RuntimeState.Starting }
                }
                StopDecision.AlreadyStopped -> return
            }
        }
        val stoppingEngine = checkNotNull(engine)
        try {
            if (interrupted) {
                stoppingEngine.shutdownForInterruption()
            } else {
                stoppingEngine.close()
            }
        } finally {
            withContext(NonCancellable) {
                transitionMutex.withLock {
                    if (mutableState.value == RuntimeState.Stopping) {
                        mutableState.value = RuntimeState.Stopped
                    }
                }
            }
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
    data object Stopping : RuntimeState
    data object Failed : RuntimeState
}

private sealed interface StopDecision {
    data class Stop(val engine: ManagedGenerationEngine) : StopDecision
    data object WaitForStart : StopDecision
    data object AlreadyStopped : StopDecision
}
