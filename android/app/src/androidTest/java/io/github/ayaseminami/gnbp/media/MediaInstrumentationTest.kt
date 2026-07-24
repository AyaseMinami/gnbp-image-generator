package io.github.ayaseminami.gnbp.media

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class ScopedMediaInstrumentationTest {
    @Test
    fun mediaStorePickerReuseAndExternalDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetStore = MediaStoreGeneratedAssetStore.create(context)
        val saved = assetStore.save(testImage(), testMetadata())

        assertTrue(saved is AssetSaveResult.Saved)
        saved as AssetSaveResult.Saved
        assertTrue(assetStore.read(saved.asset) is AssetReadResult.Opened)

        val referenceStore = ContentUriReferenceStore.create(context)
        val imported = referenceStore.import(saved.asset.uri)
        assertTrue(imported is ReferenceImportResult.Imported)
        imported as ReferenceImportResult.Imported
        assertTrue(imported.asset.asReferenceImage(BoundedImagePreparer()) is ImagePreparationResult.Prepared)

        assertEquals(AssetDeleteResult.Deleted, assetStore.delete(saved.asset))
        assertEquals(AssetReadResult.ExternalAssetMissing, assetStore.read(saved.asset))
        assertEquals(
            ReferenceImportResult.Failed(ReferenceImportFailure.AccessRevoked),
            referenceStore.import(saved.asset.uri),
        )
        assertTrue(referenceStore.delete(imported.asset))
    }

    @Test
    fun revokedPickerGrantIsMappedToAccessRevoked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val referenceStore = ContentUriReferenceStore(
            reader = RevokedContentUriReader,
            rootDirectory = File(context.cacheDir, "revoked-reference-test"),
        )

        assertEquals(
            ReferenceImportResult.Failed(ReferenceImportFailure.AccessRevoked),
            referenceStore.import(Uri.parse("content://picker/revoked")),
        )
    }

    @Test
    fun largeImageIsBoundedAndSaveFailureIsTyped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "m4-large-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        source.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        bitmap.recycle()
        val prepared = BoundedImagePreparer().prepare(source, "large.png", "image/png")
        source.delete()

        assertTrue(prepared is ImagePreparationResult.Prepared)
        prepared as ImagePreparationResult.Prepared
        assertEquals(1536, prepared.width)
        assertEquals(768, prepared.height)

        val malformed = File(context.cacheDir, "m4-malformed-${System.nanoTime()}.png")
            .apply { writeText("not an image") }
        assertEquals(
            ImagePreparationResult.Failed(ImagePreparationFailure.InvalidImage),
            BoundedImagePreparer().prepare(malformed, "malformed.png", "image/png"),
        )
        malformed.delete()

        val failingStore = MediaStoreGeneratedAssetStore(
            gateway = FailingMediaStoreGateway,
            sdkInt = Build.VERSION.SDK_INT,
            hasLegacyWritePermission = { true },
            legacyPicturesDirectory = context.cacheDir,
        )
        assertEquals(
            AssetSaveResult.Failed(AssetSaveFailure.WriteFailed),
            failingStore.save(testImage(), testMetadata()),
        )
    }
}

@RunWith(AndroidJUnit4::class)
@SdkSuppress(maxSdkVersion = Build.VERSION_CODES.P)
class LegacyMediaStoreInstrumentationTest {
    @get:Rule
    val storagePermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    @Test
    fun legacyMediaStoreSavesAndReadsWithRuntimePermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetStore = MediaStoreGeneratedAssetStore.create(context)

        val saved = assetStore.save(testImage(), testMetadata())

        assertTrue(saved is AssetSaveResult.Saved)
        saved as AssetSaveResult.Saved
        assertTrue(assetStore.read(saved.asset) is AssetReadResult.Opened)
        assertEquals(AssetDeleteResult.Deleted, assetStore.delete(saved.asset))
    }
}

private object FailingMediaStoreGateway : MediaStoreGateway {
    private val insertedUri = Uri.parse("content://media/external/images/failing")

    override fun insert(values: ContentValues): Uri = insertedUri

    override fun openOutput(uri: Uri): OutputStream? = null

    override fun publish(uri: Uri): Boolean = false

    override fun openInput(uri: Uri): InputStream? = null

    override fun delete(uri: Uri): Boolean = true
}

private object RevokedContentUriReader : ContentUriReader {
    override fun mimeType(uri: Uri): String = "image/png"

    override fun displayName(uri: Uri): String = "revoked.png"

    override fun open(uri: Uri): InputStream = throw SecurityException("revoked")
}

private fun testImage(): GeneratedImage {
    val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLUE)
    }
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    bitmap.recycle()
    return GeneratedImage(output.toByteArray(), "image/png")
}

private fun testMetadata() = GeneratedAssetMetadata(provider = "Gemini", model = "instrumented")
