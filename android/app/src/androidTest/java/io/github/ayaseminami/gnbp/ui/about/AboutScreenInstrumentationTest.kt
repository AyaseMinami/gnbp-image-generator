package io.github.ayaseminami.gnbp.ui.about

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.BuildConfig
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.ui.theme.GnbpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutScreenInstrumentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aboutShowsIdentityPrivacyAndOnlyOpensLinksAfterUserClicks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val openedDestinations = mutableListOf<AboutDestination>()
        compose.setContent {
            GnbpTheme {
                AboutScreen(
                    onBack = {},
                    onOpenDestination = { destination -> openedDestinations.add(destination) },
                )
            }
        }

        compose.onNodeWithText("GNBP Image Generator").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.about_author, "Ayase Minami"))
            .assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(
                R.string.about_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        )
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.about_privacy_summary))
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(emptyList<AboutDestination>(), openedDestinations)

        compose.onNodeWithText(context.getString(R.string.about_source_repository))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.about_license))
            .performScrollTo()
            .performClick()

        compose.onNodeWithText(context.getString(R.string.about_open_releases))
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(
                AboutDestination.Repository,
                AboutDestination.License,
                AboutDestination.Releases,
            ),
            openedDestinations,
        )
    }
}
