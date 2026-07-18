package io.github.ayaseminami.gnbp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.github.ayaseminami.gnbp.media.ReferenceDraftViewModel
import io.github.ayaseminami.gnbp.media.ReferenceImagePicker
import io.github.ayaseminami.gnbp.ui.theme.GnbpTheme

class MainActivity : ComponentActivity() {
    private val referenceDraft by viewModels<ReferenceDraftViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val referencePicker = ReferenceImagePicker(
            activity = this,
            onPicked = referenceDraft::importPickedUris,
        )
        setContent {
            val draftState by referenceDraft.state.collectAsState()
            GnbpApp(
                importedReferenceCount = draftState.assets.size,
                failedReferenceCount = draftState.failedImportCount,
                onPickReferences = referencePicker::launch,
            )
        }
    }
}

@Composable
private fun GnbpApp(
    importedReferenceCount: Int,
    failedReferenceCount: Int,
    onPickReferences: () -> Unit,
) {
    GnbpTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Button(
                    modifier = Modifier.padding(top = 24.dp),
                    onClick = onPickReferences,
                ) {
                    Text(stringResource(R.string.pick_reference_images))
                }
                if (importedReferenceCount > 0) {
                    Text(
                        modifier = Modifier.padding(top = 16.dp),
                        text = pluralStringResource(
                            R.plurals.selected_reference_count,
                            importedReferenceCount,
                            importedReferenceCount,
                        ),
                    )
                }
                if (failedReferenceCount > 0) {
                    Text(
                        modifier = Modifier.padding(top = 8.dp),
                        text = pluralStringResource(
                            R.plurals.reference_import_failure_count,
                            failedReferenceCount,
                            failedReferenceCount,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GnbpAppPreview() {
    GnbpApp(
        importedReferenceCount = 2,
        failedReferenceCount = 0,
        onPickReferences = {},
    )
}
