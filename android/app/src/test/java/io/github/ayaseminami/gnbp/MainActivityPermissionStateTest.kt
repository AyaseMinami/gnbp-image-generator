package io.github.ayaseminami.gnbp

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import io.github.ayaseminami.gnbp.media.ReferenceDraftViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityPermissionStateTest {
    @Test
    fun `pending permission submission survives activity recreation and is consumed once`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var originalViewModel: ReferenceDraftViewModel
            scenario.onActivity { activity ->
                originalViewModel = ViewModelProvider(activity)[ReferenceDraftViewModel::class.java]
                originalViewModel.retainPendingPermissionSubmission(emptyList())
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val recreatedViewModel = ViewModelProvider(activity)[ReferenceDraftViewModel::class.java]
                assertSame(originalViewModel, recreatedViewModel)
                assertEquals(emptyList<Any>(), recreatedViewModel.consumePendingPermissionSubmission())
                assertNull(recreatedViewModel.consumePendingPermissionSubmission())
            }
        }
    }
}
