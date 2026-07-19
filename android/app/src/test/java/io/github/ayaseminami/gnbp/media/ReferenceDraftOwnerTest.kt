package io.github.ayaseminami.gnbp.media

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReferenceDraftOwnerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("gnbp-reference-owner-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `owner retains imported assets and deletes them on remove or clear`() {
        val first = asset("first")
        val second = asset("second")
        val owner = ReferenceDraftOwner { asset -> asset.file.delete() }

        owner.accept(
            listOf(
                ReferenceImportResult.Imported(first),
                ReferenceImportResult.Failed(ReferenceImportFailure.AccessRevoked),
                ReferenceImportResult.Imported(second),
            ),
        )

        assertEquals(listOf(first, second), owner.state.value.assets)
        assertEquals(1, owner.state.value.failedImportCount)
        assertTrue(owner.remove(first.id))
        assertFalse(first.file.exists())
        assertEquals(listOf(second), owner.state.value.assets)

        owner.clear()
        assertFalse(second.file.exists())
        assertEquals(ReferenceDraftState(), owner.state.value)
    }

    @Test
    fun `transferring assets clears the draft without deleting task owned files`() {
        val first = asset("first")
        val second = asset("second")
        val owner = ReferenceDraftOwner { asset -> asset.file.delete() }
        owner.accept(
            listOf(
                ReferenceImportResult.Imported(first),
                ReferenceImportResult.Imported(second),
            ),
        )

        val transferred = owner.transferAssets()

        assertEquals(listOf(first, second), transferred)
        assertEquals(ReferenceDraftState(), owner.state.value)
        assertTrue(first.file.exists())
        assertTrue(second.file.exists())
        owner.clear()
        assertTrue(first.file.exists())
        assertTrue(second.file.exists())
    }

    @Test
    fun `claiming assets for a task releases draft ownership without deleting files`() {
        val first = asset("first")
        val second = asset("second")
        val owner = ReferenceDraftOwner { asset -> asset.file.delete() }
        owner.accept(
            listOf(
                ReferenceImportResult.Imported(first),
                ReferenceImportResult.Imported(second),
            ),
        )

        val taskOwnedAssets = owner.claimForTask(listOf(first))

        assertEquals(listOf(first), taskOwnedAssets)
        assertEquals(listOf(second), owner.state.value.assets)
        owner.clear()
        assertTrue(first.file.exists())
        assertFalse(second.file.exists())
    }

    private fun asset(id: String): DurableReferenceAsset {
        val file = File(root, "$id.input").apply { writeText("image") }
        return DurableReferenceAsset(
            id = MediaAssetId(id),
            file = file,
            displayName = "$id.png",
            mimeType = "image/png",
        )
    }
}
