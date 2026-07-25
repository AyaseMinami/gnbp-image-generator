package io.github.ayaseminami.gnbp.ui.about

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.BuildConfig
import io.github.ayaseminami.gnbp.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AboutResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `about identity is backed by the installed BuildConfig version`() {
        val info = AboutAppInfo.current()

        assertEquals(BuildConfig.VERSION_NAME, info.versionName)
        assertEquals(BuildConfig.VERSION_CODE, info.versionCode)
        assertEquals("Ayase Minami", info.author)
    }

    @Test
    fun `Chinese and English privacy text describe the actual network posture`() {
        val chinese = localizedContext(Locale.SIMPLIFIED_CHINESE)
            .getString(R.string.about_privacy_summary)
        val english = localizedContext(Locale.US)
            .getString(R.string.about_privacy_summary)

        assertTrue(chinese.contains("没有第一方后端、账号系统或遥测"))
        assertTrue(chinese.contains("用户配置的服务商"))
        assertTrue(english.contains("no first-party backend, account system, or telemetry"))
        assertTrue(english.contains("user-configured providers"))
        assertTrue(
            localizedContext(Locale.SIMPLIFIED_CHINESE)
                .getString(R.string.settings_error_browser_unavailable)
                .contains("浏览器"),
        )
        assertTrue(
            localizedContext(Locale.US)
                .getString(R.string.settings_error_browser_unavailable)
                .contains("browser"),
        )
    }

    private fun localizedContext(locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}
