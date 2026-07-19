package io.github.ayaseminami.gnbp.ui.notification

import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompletionTrackerTest {
    @Test
    fun `historical terminal tasks establish a baseline without notifications`() {
        val tracker = TaskCompletionTracker()
        val historical = task("historical", succeeded())

        assertTrue(tracker.accept(listOf(historical)).isEmpty())
        assertTrue(tracker.accept(listOf(historical)).isEmpty())
    }

    @Test
    fun `new terminal transitions emit once and exclude cancellation`() {
        val tracker = TaskCompletionTracker()
        tracker.accept(
            listOf(
                task("success", TaskStatus.Running),
                task("failure", TaskStatus.Running),
                task("unknown", TaskStatus.Running),
                task("cancelled", TaskStatus.Queued),
            ),
        )

        val terminal = listOf(
            task("success", succeeded()),
            task("failure", TaskStatus.Failed(TaskFailureReason.Transport)),
            task(
                "unknown",
                TaskStatus.OutcomeUnknown(TaskOutcomeUnknownReason.ProviderResponseUnknown),
            ),
            task("cancelled", TaskStatus.Cancelled(io.github.ayaseminami.gnbp.generation.TaskCancellationReason.UserRequested)),
        )
        val events = tracker.accept(terminal)

        assertEquals(3, events.size)
        assertTrue(events[0] is TaskCompletionEvent.Succeeded)
        assertTrue(events[1] is TaskCompletionEvent.Failed)
        assertTrue(events[2] is TaskCompletionEvent.OutcomeUnknown)
        assertTrue(tracker.accept(terminal).isEmpty())
    }

    private fun task(id: String, status: TaskStatus) = GenerationTask(
        id = TaskId(id),
        request = TaskRequestSnapshot(
            profileId = ProfileId("profile"),
            profileName = "Profile",
            providerKind = GenerationProviderKind.Gemini,
            model = "model",
            prompt = "prompt",
            parameters = GenerationParameters.Gemini("1:1", "1K", 0.9),
            references = emptyList(),
        ),
        status = status,
        createdAtEpochMillis = 1L,
    )

    private fun succeeded() = TaskStatus.Succeeded(
        GeneratedAssetReference(
            id = "asset",
            location = "content://example.invalid/asset",
            displayName = "asset.png",
            mimeType = "image/png",
            byteSize = 3,
        ),
    )
}
