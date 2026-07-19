package io.github.ayaseminami.gnbp.background

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import io.github.ayaseminami.gnbp.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationForegroundServiceInstrumentationTest {
    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun serviceStartsInForegroundAndCreatesItsOngoingChannel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        serviceRule.startService(
            Intent(context, GenerationForegroundService::class.java),
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val expectedName = context.getString(R.string.foreground_notification_channel_name)
        assertTrue(manager.notificationChannels.any { channel -> channel.name == expectedName })
        assertTrue(
            manager.activeNotifications.any { status ->
                status.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 &&
                    status.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            },
        )
    }
}
