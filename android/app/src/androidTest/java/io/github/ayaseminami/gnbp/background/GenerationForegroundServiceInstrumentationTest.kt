package io.github.ayaseminami.gnbp.background

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayaseminami.gnbp.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationForegroundServiceInstrumentationTest {
    @Suppress("DEPRECATION")
    @Test
    fun serviceStartsInForegroundAndCreatesItsOngoingChannel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, GenerationForegroundService::class.java).setAction(
            GenerationForegroundService.ACTION_HOLD_FOREGROUND_FOR_INSTRUMENTATION,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val expectedName = context.getString(R.string.foreground_notification_channel_name)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)

            assertTrue(
                waitUntil {
                    val notificationFlags =
                        GenerationForegroundService.instrumentationNotificationFlags ?: 0
                    manager.notificationChannels.any { channel -> channel.name == expectedName } &&
                        activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
                            service.service.className == GenerationForegroundService::class.java.name &&
                                service.foreground
                        } &&
                        notificationFlags and Notification.FLAG_ONGOING_EVENT != 0
                },
            )
        } finally {
            context.stopService(serviceIntent)
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
