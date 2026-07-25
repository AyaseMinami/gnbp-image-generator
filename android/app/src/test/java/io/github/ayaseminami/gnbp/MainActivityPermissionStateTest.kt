package io.github.ayaseminami.gnbp

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.media.ReferenceDraftViewModel
import io.github.ayaseminami.gnbp.ui.generation.GenerationFeedback
import io.github.ayaseminami.gnbp.ui.generation.GenerationViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityPermissionStateTest {
    @Test
    fun `permission registry grant after recreation consumes pending submission once`() {
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
                activity.onGenerationPermissionResult(true)
                assertNull(recreatedViewModel.consumePendingPermissionSubmission())
            }
        }
    }

    @Test
    fun `permission registry denial after recreation discards only pending state`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[ReferenceDraftViewModel::class.java]
                    .retainPendingPermissionSubmission(emptyList())
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val recreatedViewModel = ViewModelProvider(activity)[ReferenceDraftViewModel::class.java]
                activity.onGenerationPermissionResult(false)
                assertNull(recreatedViewModel.consumePendingPermissionSubmission())
            }
        }
    }

    @Test
    fun `granted permission submits real references exactly once`() {
        val reference = DurableReferenceAsset(
            id = MediaAssetId("permission-reference"),
            file = File("permission-reference.input"),
            displayName = "reference.png",
            mimeType = "image/png",
        )
        var pendingSubmission: List<DurableReferenceAsset>? = listOf(reference)
        val submitted = mutableListOf<List<DurableReferenceAsset>>()
        var deniedCount = 0
        val coordinator = PermissionSubmissionCoordinator(
            consumePendingSubmission = {
                pendingSubmission.also { pendingSubmission = null }
            },
            discardPendingSubmission = { pendingSubmission = null },
            submit = submitted::add,
            permissionDenied = { deniedCount += 1 },
        )

        coordinator.onResult(true)
        coordinator.onResult(true)

        assertEquals(listOf(listOf(reference)), submitted)
        assertNull(pendingSubmission)
        assertEquals(0, deniedCount)
    }

    @Test
    fun `reusing a copied prompt preserves feedback while editing clears it`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val generation = ViewModelProvider(activity)[GenerationViewModel::class.java]

                generation.promptCopied()
                generation.reusePrompt("lighthouse")
                assertEquals(GenerationFeedback.PromptCopied, generation.uiState.value.feedback)

                generation.updatePrompt("edited prompt")
                assertNull(generation.uiState.value.feedback)
            }
        }
    }
}
