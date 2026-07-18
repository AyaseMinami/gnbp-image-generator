package io.github.ayaseminami.gnbp.media

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoPickerContractTest {
    @Test
    fun `multiple image contract creates an image request and parses returned content uris`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contract = PhotoPicker.multipleImages(maxItems = 4)
        val requestIntent = contract.createIntent(
            context,
            PickVisualMediaRequest(PhotoPicker.imageOnlyRequest),
        )
        val first = Uri.parse("content://picker/images/1")
        val second = Uri.parse("content://picker/images/2")
        val resultIntent = Intent().apply {
            clipData = ClipData.newUri(context.contentResolver, "images", first).apply {
                addItem(ClipData.Item(second))
            }
        }

        val parsed = contract.parseResult(Activity.RESULT_OK, resultIntent)

        assertEquals("image/*", requestIntent.type)
        assertEquals(listOf(first, second), parsed)
        assertTrue(requestIntent.action?.isNotBlank() == true)
    }
}
