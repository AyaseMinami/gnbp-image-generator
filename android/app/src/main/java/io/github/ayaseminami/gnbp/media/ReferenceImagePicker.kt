package io.github.ayaseminami.gnbp.media

import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import android.net.Uri

class ReferenceImagePicker(
    activity: ComponentActivity,
    private val onPicked: (List<Uri>) -> Unit,
) {
    private val launcher = activity.registerForActivityResult(PhotoPicker.multipleImages()) { uris ->
        onPicked(uris)
    }

    fun launch() {
        launcher.launch(PickVisualMediaRequest(PhotoPicker.imageOnlyRequest))
    }
}
