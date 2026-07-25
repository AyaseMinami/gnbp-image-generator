package io.github.ayaseminami.gnbp.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AboutPageLauncherTest {
    @Test
    fun `about destinations use canonical public project pages`() {
        assertEquals(
            "https://github.com/AyaseMinami/gnbp-image-generator",
            AboutDestination.Repository.url,
        )
        assertEquals(
            "https://github.com/AyaseMinami/gnbp-image-generator/blob/main/LICENSE",
            AboutDestination.License.url,
        )
        assertEquals(
            "https://github.com/AyaseMinami/gnbp-image-generator/releases",
            AboutDestination.Releases.url,
        )
    }

    @Test
    fun `opening an about destination emits one system view intent`() {
        val launched = mutableListOf<Intent>()
        val launcher = AboutPageLauncher { intent -> launched.add(intent) }

        assertTrue(launcher.open(AboutDestination.Releases))

        val intent = launched.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AboutDestination.Releases.url, intent.dataString)
    }

    @Test
    fun `launcher does nothing until an about destination is opened`() {
        val launched = mutableListOf<Intent>()

        AboutPageLauncher { intent -> launched.add(intent) }

        assertTrue(launched.isEmpty())
    }

    @Test
    fun `missing browser reports failure without throwing`() {
        val launcher = AboutPageLauncher { throw ActivityNotFoundException("No browser") }

        assertFalse(launcher.open(AboutDestination.Repository))
    }
}
