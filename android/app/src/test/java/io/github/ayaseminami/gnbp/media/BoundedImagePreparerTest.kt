package io.github.ayaseminami.gnbp.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoundedImagePreparerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("gnbp-image-preparer-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `large image is decoded within bounds and converted to jpeg 85 contract`() {
        val source = File(root, "source.png")
        val bitmap = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        FileOutputStream(source).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()

        val result = BoundedImagePreparer().prepare(
            source,
            "C:\\private\\source.png",
            "image/png",
        )

        assertTrue(result is ImagePreparationResult.Prepared)
        result as ImagePreparationResult.Prepared
        assertEquals(1536, result.width)
        assertEquals(768, result.height)
        assertEquals("image/jpeg", result.reference.mimeType)
        assertEquals("source.png", result.reference.displayName)
        val encoded = BitmapFactory.decodeByteArray(
            result.reference.bytes,
            0,
            result.reference.bytes.size,
        )
        assertEquals(1536, encoded.width)
        assertEquals(768, encoded.height)
        encoded.recycle()
    }

    @Test
    fun `missing unsupported and oversized sources return typed failures`() {
        val missing = BoundedImagePreparer().prepare(
            File(root, "missing.png"),
            "missing.png",
            "image/png",
        )
        assertEquals(
            ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing),
            missing,
        )

        val unsupported = File(root, "unsupported.bin").apply { writeText("not an image") }
        assertEquals(
            ImagePreparationResult.Failed(ImagePreparationFailure.InvalidImage),
            BoundedImagePreparer().prepare(
                unsupported,
                "unsupported.bin",
                "application/octet-stream",
            ),
        )
        assertEquals(
            ImagePreparationResult.Failed(ImagePreparationFailure.TooLarge),
            BoundedImagePreparer(maxSourceBytes = 2L)
                .prepare(unsupported, "unsupported.png", "image/png"),
        )
        assertEquals(
            ImagePreparationResult.Failed(ImagePreparationFailure.SourceMissing),
            BoundedImagePreparer(openSource = { throw IOException("unreadable") })
                .prepare(unsupported, "unsupported.png", "image/png"),
        )
    }
}
