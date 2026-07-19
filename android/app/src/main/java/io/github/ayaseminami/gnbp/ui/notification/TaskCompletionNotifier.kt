package io.github.ayaseminami.gnbp.ui.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.ayaseminami.gnbp.MainActivity
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.media.previewIntent
import io.github.ayaseminami.gnbp.media.toAssetRef
import io.github.ayaseminami.gnbp.persistence.settings.AppSettings

sealed interface TaskCompletionEvent {
    val taskId: String

    data class Succeeded(
        override val taskId: String,
        val asset: GeneratedAssetReference,
    ) : TaskCompletionEvent

    data class Failed(
        override val taskId: String,
        val reason: TaskFailureReason,
    ) : TaskCompletionEvent

    data class OutcomeUnknown(
        override val taskId: String,
        val reason: TaskOutcomeUnknownReason,
    ) : TaskCompletionEvent
}

class TaskCompletionTracker {
    private var initialized = false
    private var previousStatuses: Map<String, TaskStatus> = emptyMap()

    fun accept(tasks: List<GenerationTask>): List<TaskCompletionEvent> {
        val current = tasks.associate { it.id.value to it.status }
        if (!initialized) {
            initialized = true
            previousStatuses = current
            return emptyList()
        }
        val events = tasks.mapNotNull { task ->
            val previous = previousStatuses[task.id.value]
            if (previous == task.status || previous.isTerminal()) return@mapNotNull null
            when (val status = task.status) {
                is TaskStatus.Succeeded -> TaskCompletionEvent.Succeeded(task.id.value, status.asset)
                is TaskStatus.Failed -> TaskCompletionEvent.Failed(task.id.value, status.reason)
                is TaskStatus.OutcomeUnknown ->
                    TaskCompletionEvent.OutcomeUnknown(task.id.value, status.reason)
                TaskStatus.Queued,
                TaskStatus.Running,
                is TaskStatus.Cancelled,
                -> null
            }
        }
        previousStatuses = current
        return events
    }
}

class AndroidTaskCompletionNotifier(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(NotificationManager::class.java)

    init {
        runCatching(::createChannels)
    }

    fun notify(event: TaskCompletionEvent, settings: AppSettings): Boolean {
        if (!settings.completionNotifications || !canPostNotifications()) return false
        return runCatching {
            val channelId = if (settings.soundNotification) CHANNEL_AUDIBLE else CHANNEL_SILENT
            val title = when (event) {
                is TaskCompletionEvent.Succeeded -> R.string.notification_success_title
                is TaskCompletionEvent.Failed -> R.string.notification_failure_title
                is TaskCompletionEvent.OutcomeUnknown -> R.string.notification_unknown_title
            }
            val message = when (event) {
                is TaskCompletionEvent.Succeeded -> R.string.notification_success_message
                is TaskCompletionEvent.Failed -> R.string.notification_failure_message
                is TaskCompletionEvent.OutcomeUnknown -> R.string.notification_unknown_message
            }
            val notification = Notification.Builder(applicationContext, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(applicationContext.getString(title))
                .setContentText(applicationContext.getString(message))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(event.contentIntent())
                .build()
            manager.notify(event.taskId.hashCode(), notification)
            true
        }.getOrDefault(false)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AUDIBLE,
                applicationContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = applicationContext.getString(R.string.notification_channel_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                applicationContext.getString(R.string.notification_silent_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun TaskCompletionEvent.contentIntent(): PendingIntent {
        val intent = when (this) {
            is TaskCompletionEvent.Succeeded -> asset.toAssetRef().previewIntent()
            is TaskCompletionEvent.Failed,
            is TaskCompletionEvent.OutcomeUnknown,
            -> Intent(applicationContext, MainActivity::class.java)
        }
        return PendingIntent.getActivity(
            applicationContext,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_AUDIBLE = "generation_completion"
        const val CHANNEL_SILENT = "generation_completion_silent"
    }
}

private fun TaskStatus?.isTerminal(): Boolean = when (this) {
    null,
    TaskStatus.Queued,
    TaskStatus.Running,
    -> false
    is TaskStatus.Succeeded,
    is TaskStatus.Failed,
    is TaskStatus.Cancelled,
    is TaskStatus.OutcomeUnknown,
    -> true
}
