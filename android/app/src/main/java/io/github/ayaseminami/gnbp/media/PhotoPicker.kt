package io.github.ayaseminami.gnbp.media

import androidx.activity.result.contract.ActivityResultContracts

object PhotoPicker {
    fun multipleImages(maxItems: Int = DEFAULT_MAX_ITEMS) =
        ActivityResultContracts.PickMultipleVisualMedia(maxItems)

    val imageOnlyRequest = ActivityResultContracts.PickVisualMedia.ImageOnly

    private const val DEFAULT_MAX_ITEMS = 8
}
