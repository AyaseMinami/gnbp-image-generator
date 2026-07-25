package io.github.ayaseminami.gnbp.ui.generation

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.generation.TaskFailureDiagnostic
import io.github.ayaseminami.gnbp.generation.TaskFailureReason
import io.github.ayaseminami.gnbp.generation.TaskStatus
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskDiagnosticsTest {
    @Test
    fun `HTTP diagnostic summary is localized and includes safe provider details`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.US)
        }
        val englishContext = context.createConfigurationContext(configuration)
        val status = TaskStatus.Failed(
            reason = TaskFailureReason.HttpStatus,
            diagnostic = TaskFailureDiagnostic(
                httpStatusCode = 524,
                providerMessage = "Upstream request timed out",
            ),
        )

        assertEquals(
            "Provider returned HTTP 524: Upstream request timed out",
            englishContext.taskDiagnosticSummary(status),
        )
        assertFalse(status.toString().contains("Upstream request timed out"))
    }

    @Test
    fun `diagnostic model rejects unsafe provider messages`() {
        assertTrue(
            runCatching {
                TaskFailureDiagnostic(524, "unsafe\nmessage")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                TaskFailureDiagnostic(524, "x".repeat(201))
            }.isFailure,
        )
    }
}
