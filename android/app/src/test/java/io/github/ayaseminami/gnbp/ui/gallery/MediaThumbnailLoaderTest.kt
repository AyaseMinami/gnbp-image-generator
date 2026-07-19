package io.github.ayaseminami.gnbp.ui.gallery

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaThumbnailLoaderTest {
    @Test
    fun `large image is decoded to a bounded thumbnail`() {
        val source = Bitmap.createBitmap(3200, 1600, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        source.recycle()

        val thumbnail = decodeBoundedThumbnail(
            open = { ByteArrayInputStream(encoded) },
            maxDimension = 512,
        )

        assertNotNull(thumbnail)
        assertEquals(512, thumbnail?.width)
        assertEquals(256, thumbnail?.height)
        thumbnail?.recycle()
    }

    @Test
    fun `missing external asset returns no thumbnail`() {
        assertNull(
            decodeBoundedThumbnail(
                open = { null },
                maxDimension = 512,
            ),
        )
    }
}
