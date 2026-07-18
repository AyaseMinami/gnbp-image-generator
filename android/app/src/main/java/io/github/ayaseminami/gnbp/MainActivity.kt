package io.github.ayaseminami.gnbp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.ReferenceDraftViewModel
import io.github.ayaseminami.gnbp.media.ReferenceImagePicker
import io.github.ayaseminami.gnbp.media.previewIntent
import io.github.ayaseminami.gnbp.ui.generation.GenerationApp
import io.github.ayaseminami.gnbp.ui.generation.GenerationPermission
import io.github.ayaseminami.gnbp.ui.generation.GenerationViewModel

class MainActivity : ComponentActivity() {
    private val referenceDraft by viewModels<ReferenceDraftViewModel>()
    private val generation by viewModels<GenerationViewModel>()
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var pendingPermissionReferences: List<DurableReferenceAsset>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            val references = pendingPermissionReferences
            pendingPermissionReferences = null
            if (granted && references != null) {
                submit(references)
            } else if (!granted) {
                generation.permissionDenied()
            }
        }
        val referencePicker = ReferenceImagePicker(
            activity = this,
            onPicked = referenceDraft::importPickedUris,
        )
        setContent {
            val draftState by referenceDraft.state.collectAsState()
            val generationState by generation.uiState.collectAsState()
            val tasks by generation.tasks.collectAsState()
            GenerationApp(
                state = generationState,
                tasks = tasks,
                references = draftState.assets,
                failedReferenceCount = draftState.failedImportCount,
                onSelectProfile = generation::selectProfile,
                onPromptChange = generation::updatePrompt,
                onBatchCountChange = generation::updateBatchCount,
                onGeminiAspectRatioChange = generation::updateGeminiAspectRatio,
                onGeminiImageSizeChange = generation::updateGeminiImageSize,
                onGeminiTemperatureChange = generation::updateGeminiTemperature,
                onOpenAiSizeChange = generation::updateOpenAiSize,
                onOpenAiQualityChange = generation::updateOpenAiQuality,
                onPickReferences = referencePicker::launch,
                onRemoveReference = referenceDraft::remove,
                onSubmit = { submit(draftState.assets) },
                onCancelTask = generation::cancel,
                onRetryTask = generation::retry,
                onOpenResult = ::openResult,
                onFeedbackShown = generation::clearFeedback,
            )
        }
    }

    private fun submit(references: List<DurableReferenceAsset>) {
        generation.submit(
            references = references,
            onAccepted = { accepted ->
                referenceDraft.transferAssets(accepted.mapTo(mutableSetOf()) { it.id })
            },
            onPermissionRequired = { permission -> requestPermission(permission, references) },
        )
    }

    private fun requestPermission(
        permission: GenerationPermission,
        references: List<DurableReferenceAsset>,
    ) {
        pendingPermissionReferences = references
        permissionLauncher.launch(permission.manifestPermission)
    }

    private fun openResult(asset: AssetRef) {
        runCatching { startActivity(asset.previewIntent()) }
    }
}
