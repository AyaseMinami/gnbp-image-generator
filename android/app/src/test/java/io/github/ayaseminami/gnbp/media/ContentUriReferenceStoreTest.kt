package io.github.ayaseminami.gnbp.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import io.github.ayaseminami.gnbp.generation.ReferenceAssetInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
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
class ContentUriReferenceStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("gnbp-reference-store-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `picker result is copied durably prepared and retained during orphan cleanup`() = runTest {
        val reader = FakeContentUriReader(
            bytes = testPng(),
            mimeType = "image/png",
            displayName = "C:\\private\\reference.png",
        )
        val store = ContentUriReferenceStore(
            reader = reader,
            rootDirectory = root,
            idGenerator = { "reference_1" },
        )

        val imported = store.import(Uri.parse("content://picker/private/42"))

        assertTrue(imported is ReferenceImportResult.Imported)
        imported as ReferenceImportResult.Imported
        assertEquals("reference.png", imported.asset.displayName)
        assertTrue(imported.asset.file.isFile)
        assertEquals(
            imported.asset,
            store.resolve(
                ReferenceAssetInput(
                    id = imported.asset.id.value,
                    displayName = imported.asset.displayName,
                    mimeType = imported.asset.mimeType,
                ),
            ),
        )
        assertFalse(imported.asset.toString().contains("private"))
        assertTrue(imported.asset.asReferenceImage(BoundedImagePreparer()) is ImagePreparationResult.Prepared)
        val prepared = imported.asset.asReferenceImage(BoundedImagePreparer()) as ImagePreparationResult.Prepared
        assertFalse(prepared.reference.toString().contains("reference.png"))

        val orphan = File(root, "orphan.input").apply { writeText("old") }
        val temporary = File(root, ".temporary.tmp").apply { writeText("old") }
        val now = System.currentTimeMillis() + 1_000L
        orphan.setLastModified(now - 1)
        temporary.setLastModified(now - 1)
        imported.asset.file.setLastModified(now - 1)

        assertEquals(
            0,
            store.cleanupOrphanedCopies(
                retainedAssetIds = setOf(imported.asset.id),
                nowEpochMillis = now,
            ),
        )
        assertEquals(
            2,
            store.cleanupOrphanedCopies(
                retainedAssetIds = setOf(imported.asset.id),
                nowEpochMillis = now,
                policy = ReferenceCleanupPolicy(
                    orphanRetentionMillis = 0,
                    incompleteCopyRetentionMillis = 0,
                ),
            ),
        )
        assertTrue(imported.asset.file.exists())
        assertTrue(store.delete(imported.asset))
    }

    @Test
    fun `revoked access and oversized picker results fail without leaving partial files`() = runTest {
        val revokedStore = ContentUriReferenceStore(
            reader = FakeContentUriReader(
                bytes = null,
                mimeType = "image/png",
                displayName = "revoked.png",
                throwSecurityException = true,
            ),
            rootDirectory = root,
            idGenerator = { "revoked" },
        )
        assertEquals(
            ReferenceImportResult.Failed(ReferenceImportFailure.AccessRevoked),
            revokedStore.import(Uri.parse("content://picker/revoked")),
        )

        val oversizedStore = ContentUriReferenceStore(
            reader = FakeContentUriReader(ByteArray(4), "image/png", "large.png"),
            rootDirectory = root,
            maxInputBytes = 3,
            idGenerator = { "oversized" },
        )
        assertEquals(
            ReferenceImportResult.Failed(ReferenceImportFailure.TooLarge),
            oversizedStore.import(Uri.parse("content://picker/large")),
        )
        assertFalse(File(root, ".oversized.tmp").exists())
        assertFalse(File(root, "oversized.input").exists())
    }
}

private class FakeContentUriReader(
    private val bytes: ByteArray?,
    private val mimeType: String?,
    private val displayName: String?,
    private val throwSecurityException: Boolean = false,
) : ContentUriReader {
    override fun mimeType(uri: Uri): String? = mimeType

    override fun displayName(uri: Uri): String? = displayName

    override fun open(uri: Uri): InputStream? =
        if (throwSecurityException) throw SecurityException("revoked") else bytes?.let(::ByteArrayInputStream)
}

private fun testPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.CYAN)
    }
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    bitmap.recycle()
    return output.toByteArray()
}
