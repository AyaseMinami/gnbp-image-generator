package io.github.ayaseminami.gnbp

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityThemeInstrumentationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("DEPRECATION")
    fun darkOverrideKeepsSystemNavigationContrastAcrossSupportedApis() {
        val systemNightMode = compose.activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        assertEquals(Configuration.UI_MODE_NIGHT_NO, systemNightMode)

        compose.onNodeWithText(compose.activity.getString(R.string.tab_settings)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.theme_dark))
            .performScrollTo()
            .performClick()
            .assertIsSelected()

        compose.waitForIdle()
        val window = compose.activity.window
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            assertEquals(Color.argb(0x80, 0x1b, 0x1b, 0x1b), window.navigationBarColor)
        } else {
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        }
        assertFalse(
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightNavigationBars,
        )
    }
}
