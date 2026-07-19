package io.github.ayaseminami.gnbp.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import io.github.ayaseminami.gnbp.MainActivity
import io.github.ayaseminami.gnbp.R

internal class ForegroundGenerationNotification(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.foreground_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(
                    R.string.foreground_notification_channel_description,
                )
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun start(service: Service) {
        val notification = build(ForegroundWorkSnapshot(1, 0, 0))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun update(snapshot: ForegroundWorkSnapshot) {
        runCatching { manager.notify(NOTIFICATION_ID, build(snapshot)) }
    }

    private fun build(snapshot: ForegroundWorkSnapshot): Notification {
        val message = if (snapshot.queuedCount == 0 && snapshot.runningCount == 0) {
            applicationContext.getString(R.string.foreground_notification_preparing)
        } else {
            applicationContext.getString(
                R.string.foreground_notification_progress,
                snapshot.runningCount,
                snapshot.queuedCount,
            )
        }
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.foreground_notification_title))
            .setContentText(message)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        NOTIFICATION_ID,
        Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL_ID = "generation_foreground_work"
        const val NOTIFICATION_ID = 0x474E4250
    }
}
