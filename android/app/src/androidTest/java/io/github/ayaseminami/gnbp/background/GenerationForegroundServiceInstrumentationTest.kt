package io.github.ayaseminami.gnbp.background

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayaseminami.gnbp.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationForegroundServiceInstrumentationTest {
    @Test
    fun serviceStartsInForegroundAndCreatesItsOngoingChannel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, GenerationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val expectedName = context.getString(R.string.foreground_notification_channel_name)
        try {
            GenerationForegroundService.start(context)

            assertTrue(
                waitUntil {
                    manager.notificationChannels.any { channel -> channel.name == expectedName } &&
                        manager.activeNotifications.any { status ->
                            status.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 &&
                                status.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
                        }
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
