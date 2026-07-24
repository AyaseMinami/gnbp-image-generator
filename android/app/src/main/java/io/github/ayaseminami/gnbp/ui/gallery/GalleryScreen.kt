package io.github.ayaseminami.gnbp.ui.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResult
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import java.text.DateFormat
import java.util.Date

internal const val GALLERY_SELECTION_MODE_TEST_TAG = "gallery-selection-mode"
internal const val GALLERY_SELECT_ALL_TEST_TAG = "gallery-select-all"
internal const val GALLERY_SHARE_SELECTED_TEST_TAG = "gallery-share-selected"
internal const val GALLERY_MORE_ACTIONS_TEST_TAG = "gallery-more-actions"
internal const val GALLERY_REMOVE_SELECTED_TEST_TAG = "gallery-remove-selected"
internal const val GALLERY_DELETE_SELECTED_TEST_TAG = "gallery-delete-selected"
internal const val GALLERY_SELECTION_TEST_TAG_PREFIX = "gallery-selection-"

private enum class GalleryConfirmation(
    @param:StringRes val titleResource: Int,
    @param:StringRes val messageResource: Int,
    @param:StringRes val confirmResource: Int,
) {
    RemoveFromLibrary(
        R.string.confirm_remove_results_title,
        R.string.confirm_remove_results_message,
        R.string.confirm_remove_results,
    ),
    DeleteFromDevice(
        R.string.confirm_delete_results_device_title,
        R.string.confirm_delete_results_device_message,
        R.string.confirm_delete_results_device,
    ),
}

@Composable
fun GalleryScreen(
    results: List<GeneratedResult>,
    isWorking: Boolean,
    onOpenResult: (GeneratedAssetReference) -> Unit,
    onShareResult: (GeneratedAssetReference) -> Unit,
    onShareResults: (Set<GeneratedResultId>) -> Unit,
    onReuseResult: (GeneratedAssetReference) -> Unit,
    onSetFavorite: (GeneratedResultId, Boolean) -> Unit,
    onRemoveFromLibrary: (Set<GeneratedResultId>) -> Unit,
    onDeleteFromDevice: (Set<GeneratedResultId>) -> Unit,
) {
    if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.gallery_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedResultIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingConfirmation by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleResults = if (favoritesOnly) results.filter(GeneratedResult::isFavorite) else results
    val visibleIds = visibleResults.map { result -> result.id.value }
    val selectedIds = selectedResultIds.toSet()
    val selectedResults = visibleResults.filter { result -> result.id.value in selectedIds }
    val selectedFavoriteCount = selectedResults.count(GeneratedResult::isFavorite)
    LaunchedEffect(visibleIds) {
        selectedResultIds = selectedResultIds.filter(visibleIds::contains)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        GallerySelectionToolbar(
            selectionMode = selectionMode,
            selectedCount = selectedResults.size,
            allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all(selectedIds::contains),
            hasVisibleResults = visibleIds.isNotEmpty(),
            favoritesOnly = favoritesOnly,
            isWorking = isWorking,
            onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },
            onEnterSelection = { selectionMode = true },
            onExitSelection = {
                selectionMode = false
                selectedResultIds = emptyList()
            },
            onToggleSelectAll = {
                selectedResultIds = if (visibleIds.all(selectedIds::contains)) emptyList() else visibleIds
            },
            onShareSelected = {
                onShareResults(selectedResults.mapTo(mutableSetOf(), GeneratedResult::id))
            },
            onRemoveSelected = { pendingConfirmation = GalleryConfirmation.RemoveFromLibrary.name },
            onDeleteSelected = { pendingConfirmation = GalleryConfirmation.DeleteFromDevice.name },
        )
        if (visibleResults.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.gallery_favorites_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleResults, key = { result -> result.id.value }) { result ->
                    GalleryItem(
                        result = result,
                        selectionMode = selectionMode,
                        selected = result.id.value in selectedIds,
                        onToggleSelection = {
                            selectedResultIds = if (result.id.value in selectedIds) {
                                selectedResultIds - result.id.value
                            } else {
                                selectedResultIds + result.id.value
                            }
                        },
                        onOpen = { onOpenResult(result.asset) },
                        onShare = { onShareResult(result.asset) },
                        onReuse = { onReuseResult(result.asset) },
                        onSetFavorite = { favorite -> onSetFavorite(result.id, favorite) },
                    )
                }
            }
        }
    }
    pendingConfirmation?.let { rawConfirmation ->
        val confirmation = GalleryConfirmation.valueOf(rawConfirmation)
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = {
                Text(stringResource(confirmation.titleResource))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(confirmation.messageResource, selectedResults.size),
                    )
                    if (selectedFavoriteCount > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.selected_favorite_count,
                                selectedFavoriteCount,
                                selectedFavoriteCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isWorking,
                    onClick = {
                        val requested = selectedResults.mapTo(mutableSetOf(), GeneratedResult::id)
                        pendingConfirmation = null
                        selectionMode = false
                        selectedResultIds = emptyList()
                        if (confirmation == GalleryConfirmation.RemoveFromLibrary) {
                            onRemoveFromLibrary(requested)
                        } else {
                            onDeleteFromDevice(requested)
                        }
                    },
                ) {
                    Text(stringResource(confirmation.confirmResource))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text(stringResource(R.string.dismiss_dialog))
                }
            },
        )
    }
}

@Composable
private fun GallerySelectionToolbar(
    selectionMode: Boolean,
    selectedCount: Int,
    allVisibleSelected: Boolean,
    hasVisibleResults: Boolean,
    favoritesOnly: Boolean,
    isWorking: Boolean,
    onToggleFavoritesOnly: () -> Unit,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onShareSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    var moreActionsExpanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.exit_gallery_selection))
            }
            Text(
                pluralStringResource(R.plurals.selected_result_count, selectedCount, selectedCount),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onToggleSelectAll,
                enabled = hasVisibleResults && !isWorking,
                modifier = Modifier.testTag(GALLERY_SELECT_ALL_TEST_TAG),
            ) {
                Icon(
                    Icons.Default.SelectAll,
                    contentDescription = stringResource(
                        if (allVisibleSelected) R.string.clear_result_selection else R.string.select_all_results,
                    ),
                )
            }
            IconButton(
                onClick = onShareSelected,
                enabled = selectedCount > 0 && !isWorking,
                modifier = Modifier.testTag(GALLERY_SHARE_SELECTED_TEST_TAG),
            ) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_selected_results))
            }
            Box {
                IconButton(
                    onClick = { moreActionsExpanded = true },
                    enabled = selectedCount > 0 && !isWorking,
                    modifier = Modifier.testTag(GALLERY_MORE_ACTIONS_TEST_TAG),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_result_actions))
                }
                DropdownMenu(
                    expanded = moreActionsExpanded,
                    onDismissRequest = { moreActionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_selected_from_library)) },
                        onClick = {
                            moreActionsExpanded = false
                            onRemoveSelected()
                        },
                        leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, contentDescription = null) },
                        modifier = Modifier.testTag(GALLERY_REMOVE_SELECTED_TEST_TAG),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_selected_from_device)) },
                        onClick = {
                            moreActionsExpanded = false
                            onDeleteSelected()
                        },
                        leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
                        modifier = Modifier.testTag(GALLERY_DELETE_SELECTED_TEST_TAG),
                    )
                }
            }
        } else {
            FilterChip(
                selected = favoritesOnly,
                onClick = onToggleFavoritesOnly,
                label = { Text(stringResource(R.string.gallery_favorites_only)) },
                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onEnterSelection,
                enabled = hasVisibleResults && !isWorking,
                modifier = Modifier.testTag(GALLERY_SELECTION_MODE_TEST_TAG),
            ) {
                Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.select_results))
            }
        }
    }
}

@Composable
private fun GalleryItem(
    result: GeneratedResult,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onSetFavorite: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$GALLERY_SELECTION_TEST_TAG_PREFIX${result.id.value}")
            .clickable(enabled = selectionMode, onClick = onToggleSelection),
    ) {
        Column {
            Box {
                GeneratedThumbnail(result.asset)
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    result.request.prompt,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    rememberTimestamp(result.createdAtEpochMillis),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { onSetFavorite(!result.isFavorite) },
                        enabled = !selectionMode,
                    ) {
                        Icon(
                            if (result.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = stringResource(
                                if (result.isFavorite) R.string.unfavorite_result else R.string.favorite_result,
                            ),
                        )
                    }
                    IconButton(onClick = onReuse, enabled = !selectionMode) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = stringResource(R.string.reuse_as_reference),
                        )
                    }
                    IconButton(onClick = onShare, enabled = !selectionMode) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_result),
                        )
                    }
                    IconButton(onClick = onOpen, enabled = !selectionMode) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.open_result),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratedThumbnail(asset: GeneratedAssetReference) {
    val context = LocalContext.current
    val loader = androidx.compose.runtime.remember(context) {
        MediaThumbnailLoader(context.contentResolver)
    }
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = asset.location,
    ) {
        value = loader.load(asset.location)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded == null) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = stringResource(R.string.gallery_thumbnail_unavailable),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = stringResource(R.string.gallery_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun rememberTimestamp(epochMillis: Long): String =
    androidx.compose.runtime.remember(epochMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
    }
