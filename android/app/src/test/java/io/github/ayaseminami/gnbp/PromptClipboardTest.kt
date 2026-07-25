package io.github.ayaseminami.gnbp

import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromptClipboardTest {
    @Test
    fun `copy prompt writes only the complete prompt to clipboard`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prompt = "A lighthouse on a cliff at sunrise, with warm mist and fine detail."

        copyPromptToClipboard(context, prompt)

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(context.getString(R.string.prompt_label), clip.description.label)
        assertEquals(prompt, clip.getItemAt(0).coerceToText(context).toString())
    }
}
