package io.github.ayaseminami.gnbp.generation

import android.net.Uri
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileGenerationResultJournalTest {
    @Test
    fun `first journal access removes an orphaned atomic-write temporary file`() = runTest {
        val root = Files.createTempDirectory("gnbp-result-orphan").toFile()
        val orphan = File(root, ".task.stage.00000000-0000-0000-0000-000000000000.tmp")
        orphan.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(
            ResultJournalRecovery.None,
            FileGenerationResultJournal(root).load(TaskId("task")),
        )
        assertFalse(orphan.exists())
    }

    @Test
    fun `staged provider result survives a new journal instance without leaking content`() = runTest {
        val root = Files.createTempDirectory("gnbp-result-journal").toFile()
        val taskId = TaskId("journal-stage")
        val image = GeneratedImage(byteArrayOf(1, 2, 3, 4), "image/png")
        val metadata = GeneratedAssetMetadata(
            provider = "Gemini",
            model = "private-model",
            parameters = mapOf("size" to "1K"),
            referenceDisplayNames = listOf("private-reference.png"),
        )

        assertTrue(FileGenerationResultJournal(root).stage(taskId, image, metadata))
        val recovered = FileGenerationResultJournal(root).load(taskId)

        assertTrue(recovered is ResultJournalRecovery.Staged)
        recovered as ResultJournalRecovery.Staged
        assertArrayEquals(image.bytes, recovered.image.bytes)
        assertEquals(metadata, recovered.metadata)
        assertFalse(recovered.toString().contains("private-model"))
        assertFalse(recovered.toString().contains("private-reference"))
    }

    @Test
    fun `saved receipt wins over staged bytes and delete clears both records`() = runTest {
        val root = Files.createTempDirectory("gnbp-result-receipt").toFile()
        val journal = FileGenerationResultJournal(root)
        val taskId = TaskId("journal-receipt")
        val asset = AssetRef(
            id = MediaAssetId("saved-asset"),
            uri = Uri.parse("content://private.example/results/1"),
            displayName = "result.png",
            mimeType = "image/png",
            byteSize = 3,
        )
        assertTrue(
            journal.stage(
                taskId,
                GeneratedImage(byteArrayOf(7, 8, 9), "image/png"),
                GeneratedAssetMetadata("Gemini", "model"),
            ),
        )

        assertTrue(journal.recordSaved(taskId, asset))
        val recovered = FileGenerationResultJournal(root).load(taskId)

        assertTrue(recovered is ResultJournalRecovery.Saved)
        recovered as ResultJournalRecovery.Saved
        assertEquals(asset, recovered.asset)
        assertFalse(recovered.toString().contains("private.example"))

        journal.delete(taskId)
        assertEquals(ResultJournalRecovery.None, FileGenerationResultJournal(root).load(taskId))
    }
}
