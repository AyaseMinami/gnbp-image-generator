package io.github.ayaseminami.gnbp.ui.theme

import io.github.ayaseminami.gnbp.persistence.settings.ThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnbpThemeTest {
    @Test
    fun `system mode follows the current system theme`() {
        assertFalse(ThemeMode.System.resolveDarkTheme(systemInDarkTheme = false))
        assertTrue(ThemeMode.System.resolveDarkTheme(systemInDarkTheme = true))
    }

    @Test
    fun `explicit modes override the current system theme`() {
        assertFalse(ThemeMode.Light.resolveDarkTheme(systemInDarkTheme = true))
        assertTrue(ThemeMode.Dark.resolveDarkTheme(systemInDarkTheme = false))
    }
}
