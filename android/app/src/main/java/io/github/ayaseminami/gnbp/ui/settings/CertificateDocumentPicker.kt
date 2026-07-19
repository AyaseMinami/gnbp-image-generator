package io.github.ayaseminami.gnbp.ui.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CertificateDocumentPicker(
    activity: ComponentActivity,
    private val onImported: (ByteArray) -> Unit,
    private val onFailed: () -> Unit,
) {
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            activity.lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    readCertificate(activity, uri)
                }
                if (bytes == null) onFailed() else onImported(bytes)
            }
        }
    }

    fun launch() {
        launcher.launch(
            arrayOf(
                "application/x-x509-ca-cert",
                "application/pkix-cert",
                "application/octet-stream",
                "text/plain",
            ),
        )
    }
}

private fun readCertificate(activity: ComponentActivity, uri: Uri): ByteArray? = try {
    activity.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_CERTIFICATE_BYTES) return null
            output.write(buffer, 0, count)
        }
        output.toByteArray().takeIf(ByteArray::isNotEmpty)
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private const val MAX_CERTIFICATE_BYTES = 1024 * 1024
