package io.github.ayaseminami.gnbp.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.ayaseminami.gnbp.GnbpApplication
import io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.ui.notification.AndroidTaskCompletionNotifier
import io.github.ayaseminami.gnbp.ui.notification.TaskCompletionTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class GenerationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var applicationGraph: io.github.ayaseminami.gnbp.GnbpAppGraph
    private lateinit var foregroundNotification: ForegroundGenerationNotification
    private val completionTracker = TaskCompletionTracker()
    private lateinit var completionNotifier: AndroidTaskCompletionNotifier
    private var latestStartId = 0
    private var collectionStarted = false
    private var idleStopRequested = false
    private var runtimeStopped = false
    private var stopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        applicationGraph = (application as GnbpApplication).graph
        foregroundNotification = ForegroundGenerationNotification(this)
        completionNotifier = AndroidTaskCompletionNotifier(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        idleStopRequested = false
        runtimeStopped = false
        foregroundNotification.start(this)
        if (!collectionStarted) {
            collectionStarted = true
            serviceScope.launch { runForegroundWork() }
        } else {
            serviceScope.launch { startRuntimeOrStop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        interruptAndStop(startId)
    }

    override fun onDestroy() {
        stopJob?.cancel()
        serviceScope.cancel()
        if (!runtimeStopped) {
            runBlocking {
                val stopped = withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) {
                    applicationGraph.generationRuntime.stop(interrupted = !idleStopRequested)
                    true
                } == true
                if (!stopped && !idleStopRequested) {
                    withTimeoutOrNull(FALLBACK_RECONCILIATION_TIMEOUT_MILLIS) {
                        reconcileForcedInterruption()
                    }
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun runForegroundWork() {
        try {
            completionTracker.accept(applicationGraph.persistence.tasks.loadTasks())
            startRuntimeOrStop()
            combine(
                applicationGraph.persistence.tasks.observeTasks(),
                applicationGraph.generationRuntime.pendingCommands,
                applicationGraph.persistence.settings.observeSettings(),
            ) { tasks, pendingCommands, settings -> Triple(tasks, pendingCommands, settings) }
                .collect { (tasks, pendingCommands, settings) ->
                    completionTracker.accept(tasks).forEach { event ->
                        completionNotifier.notify(event, settings)
                    }
                    val snapshot = ForegroundWorkSnapshot.from(tasks, pendingCommands)
                    foregroundNotification.update(snapshot)
                    if (snapshot.isActive) {
                        stopJob?.cancel()
                        stopJob = null
                        serviceScope.launch { startRuntimeOrStop() }
                    } else {
                        scheduleIdleStop()
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            interruptAndStop(latestStartId)
        }
    }

    private suspend fun startRuntimeOrStop() {
        try {
            applicationGraph.generationRuntime.start()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            stopSelfResult(latestStartId)
        }
    }

    private fun scheduleIdleStop() {
        if (stopJob?.isActive == true) return
        val observedStartId = latestStartId
        stopJob = serviceScope.launch {
            delay(IDLE_SETTLE_MILLIS)
            val tasks = applicationGraph.persistence.tasks.loadTasks()
            val snapshot = ForegroundWorkSnapshot.from(
                tasks,
                applicationGraph.generationRuntime.pendingCommands.value,
            )
            if (snapshot.isActive) return@launch
            applicationGraph.generationRuntime.stop(interrupted = false)
            runtimeStopped = true
            idleStopRequested = true
            if (stopSelfResult(observedStartId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                idleStopRequested = false
                runtimeStopped = false
                startRuntimeOrStop()
            }
        }
    }

    private fun interruptAndStop(startId: Int) {
        stopJob?.cancel()
        stopJob = serviceScope.launch {
            try {
                val stopped = try {
                    withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) {
                        applicationGraph.generationRuntime.stop(interrupted = true)
                        true
                    } == true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
                if (!stopped) runCatching { reconcileForcedInterruption() }
                runCatching {
                    val settings = applicationGraph.persistence.settings.observeSettings().first()
                    val tasks = applicationGraph.persistence.tasks.loadTasks()
                    completionTracker.accept(tasks).forEach { event ->
                        completionNotifier.notify(event, settings)
                    }
                }
            } finally {
                runtimeStopped = true
                idleStopRequested = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private suspend fun reconcileForcedInterruption() {
        applicationGraph.persistence.tasks.loadTasks()
            .filter { task -> task.status == TaskStatus.Running }
            .forEach { task ->
                applicationGraph.persistence.tasks.updateTask(
                    task.copy(
                        status = TaskStatus.OutcomeUnknown(
                            TaskOutcomeUnknownReason.ProcessInterrupted,
                        ),
                        finishedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GenerationForegroundService::class.java),
            )
        }

        internal fun hasActiveTasks(tasks: List<io.github.ayaseminami.gnbp.generation.GenerationTask>): Boolean =
            tasks.any { task -> task.status == TaskStatus.Queued || task.status == TaskStatus.Running }

        private const val IDLE_SETTLE_MILLIS = 500L
        private const val SHUTDOWN_TIMEOUT_MILLIS = 4_000L
        private const val FALLBACK_RECONCILIATION_TIMEOUT_MILLIS = 1_000L
    }
}
