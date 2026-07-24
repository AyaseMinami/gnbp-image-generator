package io.github.ayaseminami.gnbp.media

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreGeneratedAssetStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("gnbp-media-store-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `scoped storage saves publishes and creates safe share and preview intents`() = runTest {
        val gateway = FakeMediaStoreGateway()
        val store = testStore(gateway, Build.VERSION_CODES.Q, hasPermission = false)
        val image = GeneratedImage("png-bytes".encodeToByteArray(), "image/png")
        val metadata = GeneratedAssetMetadata(
            provider = "Gemini",
            model = "sentinel-model-name",
            parameters = mapOf("quality" to "high", "api_key" to "sentinel-secret"),
            referenceDisplayNames = listOf("C:\\private\\reference.png"),
        )
        assertFalse(metadata.toString().contains("sentinel-secret"))
        assertFalse(metadata.toString().contains("sentinel-model-name"))

        val result = store.save(image, metadata)

        assertTrue(result is AssetSaveResult.Saved)
        result as AssetSaveResult.Saved
        assertEquals("Pictures/GNBP", gateway.insertedValues?.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, gateway.insertedValues?.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertNull(gateway.insertedValues?.getAsString(MediaStore.Images.Media.DATA))
        assertTrue(gateway.published)
        assertArrayEquals(image.bytes, gateway.output.toByteArray())
        val description = gateway.insertedValues?.getAsString(MediaStore.Images.Media.DESCRIPTION).orEmpty()
        assertFalse(description.contains("sentinel-secret"))
        assertFalse(description.contains("private"))
        assertFalse(image.toString().contains("png-bytes"))
        assertFalse(AssetReadResult.Opened(image.bytes).toString().contains("png-bytes"))

        gateway.inputBytes = image.bytes
        val read = store.read(result.asset)
        assertTrue(read is AssetReadResult.Opened)
        assertArrayEquals(image.bytes, (read as AssetReadResult.Opened).bytes)

        val share = result.asset.shareIntent()
        val preview = result.asset.previewIntent()
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals(result.asset.uri, share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(Intent.ACTION_VIEW, preview.action)
        assertEquals(result.asset.uri, preview.data)
        assertFalse(result.asset.toString().contains(result.asset.uri.toString()))
    }

    @Test
    fun `batch share grants every image URI and selects an honest MIME type`() {
        val png = AssetRef(
            id = MediaAssetId("png"),
            uri = Uri.parse("content://media/external/images/1"),
            displayName = "first.png",
            mimeType = "image/png",
            byteSize = 1,
        )
        val jpeg = AssetRef(
            id = MediaAssetId("jpeg"),
            uri = Uri.parse("content://media/external/images/2"),
            displayName = "second.jpg",
            mimeType = "image/jpeg",
            byteSize = 1,
        )

        val sameType = listOf(png, png.copy(id = MediaAssetId("png-two"))).shareIntent()
        val mixedType = listOf(png, jpeg).shareIntent()

        assertEquals(Intent.ACTION_SEND_MULTIPLE, mixedType.action)
        assertEquals("image/png", sameType.type)
        assertEquals("image/*", mixedType.type)
        assertEquals(
            arrayListOf(png.uri, jpeg.uri),
            mixedType.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java),
        )
        assertTrue(mixedType.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(2, mixedType.clipData?.itemCount)
        assertEquals(png.uri, mixedType.clipData?.getItemAt(0)?.uri)
        assertEquals(jpeg.uri, mixedType.clipData?.getItemAt(1)?.uri)
    }

    @Test
    fun `legacy storage requires permission and uses the pre-29 data path`() = runTest {
        val deniedGateway = FakeMediaStoreGateway()
        val denied = testStore(deniedGateway, Build.VERSION_CODES.P, hasPermission = false)
            .save(testImage(), testMetadata())
        assertEquals(AssetSaveResult.Failed(AssetSaveFailure.PermissionRequired), denied)
        assertNull(deniedGateway.insertedValues)

        val gateway = FakeMediaStoreGateway()
        val saved = testStore(gateway, Build.VERSION_CODES.P, hasPermission = true)
            .save(testImage(), testMetadata())
        assertTrue(saved is AssetSaveResult.Saved)
        val dataPath = gateway.insertedValues?.getAsString(MediaStore.Images.Media.DATA).orEmpty()
        assertTrue(dataPath.endsWith("GNBP${File.separator}gnbp_1234_asset_1.png"))
        assertNull(gateway.insertedValues?.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertFalse(gateway.published)
    }

    @Test
    fun `save failure is rolled back and external deletion is typed`() = runTest {
        val gateway = FakeMediaStoreGateway(outputAvailable = false)
        val store = testStore(gateway, Build.VERSION_CODES.Q, hasPermission = true)

        assertEquals(
            AssetSaveResult.Failed(AssetSaveFailure.WriteFailed),
            store.save(testImage(), testMetadata()),
        )
        assertTrue(gateway.deleted)

        val missing = AssetRef(
            id = MediaAssetId("missing"),
            uri = gateway.insertUri,
            displayName = "missing.png",
            mimeType = "image/png",
            byteSize = 1,
        )
        gateway.inputBytes = null
        assertEquals(AssetReadResult.ExternalAssetMissing, store.read(missing))
    }

    @Test
    fun `delete distinguishes removed missing denied and failed media`() = runTest {
        val gateway = FakeMediaStoreGateway()
        val store = testStore(gateway, Build.VERSION_CODES.Q, hasPermission = true)
        val asset = AssetRef(
            id = MediaAssetId("generated"),
            uri = gateway.insertUri,
            displayName = "generated.png",
            mimeType = "image/png",
            byteSize = 1,
        )

        assertEquals(AssetDeleteResult.Deleted, store.delete(asset))
        gateway.deleteResult = false
        assertEquals(AssetDeleteResult.Missing, store.delete(asset))
        gateway.deleteFailure = SecurityException("denied")
        assertEquals(AssetDeleteResult.PermissionDenied, store.delete(asset))
        gateway.deleteFailure = IllegalArgumentException("invalid URI")
        assertEquals(AssetDeleteResult.Failed, store.delete(asset))
    }

    @Test
    fun `share access check distinguishes available missing denied and failed media`() = runTest {
        val gateway = FakeMediaStoreGateway()
        val store = testStore(gateway, Build.VERSION_CODES.Q, hasPermission = true)
        val asset = AssetRef(
            id = MediaAssetId("shareable"),
            uri = gateway.insertUri,
            displayName = "shareable.png",
            mimeType = "image/png",
            byteSize = 1,
        )

        gateway.inputBytes = byteArrayOf(1)
        assertEquals(AssetAccessResult.Available, store.checkReadable(asset))
        gateway.inputBytes = null
        assertEquals(AssetAccessResult.Missing, store.checkReadable(asset))
        gateway.inputFailure = SecurityException("denied")
        assertEquals(AssetAccessResult.PermissionDenied, store.checkReadable(asset))
        gateway.inputFailure = IllegalArgumentException("invalid URI")
        assertEquals(AssetAccessResult.Failed, store.checkReadable(asset))
    }

    @Test
    fun `same millisecond legacy saves do not truncate collision safe identities`() = runTest {
        val gateway = FakeMediaStoreGateway()
        val ids = ArrayDeque(listOf("12345678-first", "12345678-second"))
        val store = MediaStoreGeneratedAssetStore(
            gateway = gateway,
            sdkInt = Build.VERSION_CODES.P,
            hasLegacyWritePermission = { true },
            legacyPicturesDirectory = root,
            nowEpochMillis = { 1234L },
            idGenerator = ids::removeFirst,
        )

        val first = store.save(testImage(), testMetadata()) as AssetSaveResult.Saved
        val second = store.save(testImage(), testMetadata()) as AssetSaveResult.Saved

        assertFalse(first.asset.id == second.asset.id)
        assertFalse(first.asset.displayName == second.asset.displayName)
        assertTrue(first.asset.displayName.contains("12345678-first"))
        assertTrue(second.asset.displayName.contains("12345678-second"))
    }

    private fun testStore(
        gateway: FakeMediaStoreGateway,
        sdkInt: Int,
        hasPermission: Boolean,
    ) = MediaStoreGeneratedAssetStore(
        gateway = gateway,
        sdkInt = sdkInt,
        hasLegacyWritePermission = { hasPermission },
        legacyPicturesDirectory = root,
        nowEpochMillis = { 1234L },
        idGenerator = { "asset_1" },
    )
}

private class FakeMediaStoreGateway(
    private val outputAvailable: Boolean = true,
) : MediaStoreGateway {
    val insertUri: Uri = Uri.parse("content://media/external/images/1")
    val output = ByteArrayOutputStream()
    var insertedValues: ContentValues? = null
    var inputBytes: ByteArray? = null
    var inputFailure: RuntimeException? = null
    var published: Boolean = false
    var deleted: Boolean = false
    var deleteResult: Boolean = true
    var deleteFailure: RuntimeException? = null

    override fun insert(values: ContentValues): Uri {
        insertedValues = ContentValues(values)
        return insertUri
    }

    override fun openOutput(uri: Uri): OutputStream? = if (outputAvailable) output else null

    override fun publish(uri: Uri): Boolean {
        published = true
        return true
    }

    override fun openInput(uri: Uri): InputStream? {
        inputFailure?.let { throw it }
        return inputBytes?.let(::ByteArrayInputStream)
    }

    override fun delete(uri: Uri): Boolean {
        deleteFailure?.let { throw it }
        deleted = true
        return deleteResult
    }
}

private fun testImage() = GeneratedImage("png-bytes".encodeToByteArray(), "image/png")

private fun testMetadata() = GeneratedAssetMetadata(provider = "Gemini", model = "model")
