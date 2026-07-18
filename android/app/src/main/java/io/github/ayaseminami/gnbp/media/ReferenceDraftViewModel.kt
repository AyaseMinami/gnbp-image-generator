package io.github.ayaseminami.gnbp.media

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReferenceDraftState(
    val assets: List<DurableReferenceAsset> = emptyList(),
    val failedImportCount: Int = 0,
)

class ReferenceDraftViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val referenceStore = ContentUriReferenceStore.create(application)
    private val owner = ReferenceDraftOwner(referenceStore::delete)
    val state: StateFlow<ReferenceDraftState> = owner.state

    fun importPickedUris(uris: List<Uri>) {
        viewModelScope.launch {
            val results = uris.map { uri -> referenceStore.import(uri) }
            owner.accept(results)
        }
    }

    fun remove(assetId: MediaAssetId): Boolean = owner.remove(assetId)

    override fun onCleared() {
        owner.clear()
        super.onCleared()
    }
}

internal class ReferenceDraftOwner(
    private val deleteAsset: (DurableReferenceAsset) -> Boolean,
) {
    private val mutableState = MutableStateFlow(ReferenceDraftState())
    val state: StateFlow<ReferenceDraftState> = mutableState.asStateFlow()

    fun accept(results: List<ReferenceImportResult>) {
        val imported = results.mapNotNull { result ->
            (result as? ReferenceImportResult.Imported)?.asset
        }
        val failedCount = results.count { it is ReferenceImportResult.Failed }
        mutableState.update { current ->
            current.copy(
                assets = current.assets + imported,
                failedImportCount = current.failedImportCount + failedCount,
            )
        }
    }

    fun remove(assetId: MediaAssetId): Boolean {
        val asset = mutableState.value.assets.firstOrNull { it.id == assetId } ?: return false
        if (!deleteAsset(asset)) return false
        mutableState.update { current ->
            current.copy(assets = current.assets.filterNot { it.id == assetId })
        }
        return true
    }

    fun clear() {
        mutableState.value.assets.forEach { asset -> deleteAsset(asset) }
        mutableState.value = ReferenceDraftState()
    }
}
