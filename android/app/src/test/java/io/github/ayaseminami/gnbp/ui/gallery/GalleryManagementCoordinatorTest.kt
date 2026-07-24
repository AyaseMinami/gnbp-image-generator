package io.github.ayaseminami.gnbp.ui.gallery

import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationProviderKind
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot
import io.github.ayaseminami.gnbp.media.AssetDeleteResult
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResult
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GalleryManagementCoordinatorTest {
    @Test
    fun `remove from library reports missing rows without touching media`() = runTest {
        val first = result("first")
        val missing = GeneratedResultId("result-missing")
        val removedRequests = mutableListOf<Set<GeneratedResultId>>()
        var mediaDeleteCount = 0
        val coordinator = GalleryManagementCoordinator(
            scope = this,
            findResult = { error("Library removal must not load media") },
            removeResults = { ids ->
                removedRequests += ids
                setOf(first.id)
            },
            deleteAsset = {
                mediaDeleteCount += 1
                AssetDeleteResult.Deleted
            },
        )

        coordinator.removeFromLibrary(setOf(first.id, missing))
        advanceUntilIdle()

        assertEquals(listOf(setOf(first.id, missing)), removedRequests)
        assertEquals(0, mediaDeleteCount)
        assertEquals(
            GalleryManagementFeedback.Completed(
                action = GalleryBulkAction.RemoveFromLibrary,
                completedCount = 1,
                failedCount = 1,
            ),
            coordinator.state.value.feedback,
        )
        assertFalse(coordinator.state.value.isWorking)
    }

    @Test
    fun `delete from device removes only records whose media deletion succeeded`() = runTest {
        val deleted = result("deleted")
        val denied = result("denied")
        val missingMedia = result("missing-media")
        val missingResultId = GeneratedResultId("result-missing-row")
        val byId = listOf(deleted, denied, missingMedia).associateBy(GeneratedResult::id)
        val removedRequests = mutableListOf<Set<GeneratedResultId>>()
        val coordinator = GalleryManagementCoordinator(
            scope = this,
            findResult = byId::get,
            removeResults = { ids ->
                removedRequests += ids
                ids
            },
            deleteAsset = { asset ->
                when (asset.id.value) {
                    "deleted" -> AssetDeleteResult.Deleted
                    "denied" -> AssetDeleteResult.PermissionDenied
                    else -> AssetDeleteResult.Missing
                }
            },
        )

        coordinator.deleteFromDevice(setOf(deleted.id, denied.id, missingMedia.id, missingResultId))
        advanceUntilIdle()

        assertEquals(listOf(setOf(deleted.id)), removedRequests)
        assertEquals(
            GalleryManagementFeedback.Completed(
                action = GalleryBulkAction.DeleteFromDevice,
                completedCount = 1,
                failedCount = 3,
            ),
            coordinator.state.value.feedback,
        )
    }

    @Test
    fun `per item media exception does not stop remaining deletions`() = runTest {
        val failing = result("failing")
        val succeeding = result("succeeding")
        val byId = listOf(failing, succeeding).associateBy(GeneratedResult::id)
        val coordinator = GalleryManagementCoordinator(
            scope = this,
            findResult = byId::get,
            removeResults = { it },
            deleteAsset = { asset ->
                if (asset.id.value == "failing") error("provider rejected deletion")
                AssetDeleteResult.Deleted
            },
        )

        coordinator.deleteFromDevice(setOf(failing.id, succeeding.id))
        advanceUntilIdle()

        assertEquals(
            GalleryManagementFeedback.Completed(
                action = GalleryBulkAction.DeleteFromDevice,
                completedCount = 1,
                failedCount = 1,
            ),
            coordinator.state.value.feedback,
        )
    }

    @Test
    fun `a second command is ignored while gallery management is active`() = runTest {
        val allowRemoval = CompletableDeferred<Unit>()
        var commandCount = 0
        val coordinator = GalleryManagementCoordinator(
            scope = this,
            findResult = { null },
            removeResults = { ids ->
                commandCount += 1
                allowRemoval.await()
                ids
            },
            deleteAsset = { AssetDeleteResult.Deleted },
        )

        coordinator.removeFromLibrary(setOf(GeneratedResultId("result-first")))
        runCurrent()
        assertTrue(coordinator.state.value.isWorking)
        coordinator.removeFromLibrary(setOf(GeneratedResultId("result-second")))
        runCurrent()
        assertEquals(1, commandCount)

        allowRemoval.complete(Unit)
        advanceUntilIdle()
        assertFalse(coordinator.state.value.isWorking)
    }

    @Test
    fun `cancelled command releases the working state without reporting success`() = runTest {
        val coordinator = GalleryManagementCoordinator(
            scope = this,
            findResult = { null },
            removeResults = { throw CancellationException("cancelled") },
            deleteAsset = { AssetDeleteResult.Deleted },
        )

        coordinator.removeFromLibrary(setOf(GeneratedResultId("result-cancelled")))
        advanceUntilIdle()

        assertFalse(coordinator.state.value.isWorking)
        assertEquals(null, coordinator.state.value.feedback)
    }
}

private fun result(name: String): GeneratedResult = GeneratedResult(
    id = GeneratedResultId("result-$name"),
    sourceTaskId = TaskId("task-$name"),
    request = TaskRequestSnapshot(
        profileId = ProfileId("profile"),
        profileName = "Profile",
        providerKind = GenerationProviderKind.Gemini,
        model = "model",
        prompt = "private prompt",
        parameters = GenerationParameters.Gemini("1:1", "1K", 0.5),
        references = emptyList(),
    ),
    asset = GeneratedAssetReference(
        id = name,
        location = "content://media/external/images/$name",
        displayName = "$name.png",
        mimeType = "image/png",
        byteSize = 1,
    ),
    createdAtEpochMillis = 1,
)
