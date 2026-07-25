package io.github.ayaseminami.gnbp.ui.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResult
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import io.github.ayaseminami.gnbp.persistence.settings.GalleryLayoutMode
import java.text.DateFormat
import java.util.Date

internal const val GALLERY_SELECTION_MODE_TEST_TAG = "gallery-selection-mode"
internal const val GALLERY_SELECT_ALL_TEST_TAG = "gallery-select-all"
internal const val GALLERY_SHARE_SELECTED_TEST_TAG = "gallery-share-selected"
internal const val GALLERY_MORE_ACTIONS_TEST_TAG = "gallery-more-actions"
internal const val GALLERY_REMOVE_SELECTED_TEST_TAG = "gallery-remove-selected"
internal const val GALLERY_DELETE_SELECTED_TEST_TAG = "gallery-delete-selected"
internal const val GALLERY_SELECTION_TEST_TAG_PREFIX = "gallery-selection-"
internal const val GALLERY_LAYOUT_LARGE_TEST_TAG = "gallery-layout-large"
internal const val GALLERY_LAYOUT_COMPACT_TEST_TAG = "gallery-layout-compact"
internal const val GALLERY_LAYOUT_LIST_TEST_TAG = "gallery-layout-list"
internal const val GALLERY_LARGE_GRID_TEST_TAG = "gallery-large-grid"
internal const val GALLERY_COMPACT_GRID_TEST_TAG = "gallery-compact-grid"
internal const val GALLERY_LIST_TEST_TAG = "gallery-list"
internal const val GALLERY_PROMPT_DETAILS_TEST_TAG = "gallery-prompt-details"

private const val LARGE_THUMBNAIL_MAX_DIMENSION = 512
private const val COMPACT_THUMBNAIL_MAX_DIMENSION = 256
private const val LIST_THUMBNAIL_MAX_DIMENSION = 192

private data class GalleryLayoutDescriptor(
    val icon: ImageVector,
    @param:StringRes val labelResource: Int,
    val testTag: String,
)

private val GalleryLayoutMode.descriptor: GalleryLayoutDescriptor
    get() = when (this) {
        GalleryLayoutMode.LargeGrid -> GalleryLayoutDescriptor(
            icon = Icons.Default.GridView,
            labelResource = R.string.gallery_layout_large_grid,
            testTag = GALLERY_LAYOUT_LARGE_TEST_TAG,
        )
        GalleryLayoutMode.CompactGrid -> GalleryLayoutDescriptor(
            icon = Icons.Default.ViewModule,
            labelResource = R.string.gallery_layout_compact_grid,
            testTag = GALLERY_LAYOUT_COMPACT_TEST_TAG,
        )
        GalleryLayoutMode.List -> GalleryLayoutDescriptor(
            icon = Icons.AutoMirrored.Filled.ViewList,
            labelResource = R.string.gallery_layout_list,
            testTag = GALLERY_LAYOUT_LIST_TEST_TAG,
        )
    }

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
    layoutMode: GalleryLayoutMode,
    isWorking: Boolean,
    onLayoutModeChange: (GalleryLayoutMode) -> Unit,
    onOpenResult: (GeneratedAssetReference) -> Unit,
    onShareResult: (GeneratedAssetReference) -> Unit,
    onShareResults: (Set<GeneratedResultId>) -> Unit,
    onReuseResult: (GeneratedAssetReference) -> Unit,
    onCopyPrompt: (String) -> Unit,
    onReusePrompt: (String) -> Unit,
    onSetFavorite: (GeneratedResultId, Boolean) -> Unit,
    onRemoveFromLibrary: (Set<GeneratedResultId>) -> Unit,
    onDeleteFromDevice: (Set<GeneratedResultId>) -> Unit,
) {
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedResultIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingConfirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var promptDetailsResultId by rememberSaveable { mutableStateOf<String?>(null) }
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
            layoutMode = layoutMode,
            isWorking = isWorking,
            onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },
            onLayoutModeChange = onLayoutModeChange,
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
        when {
            results.isEmpty() -> GalleryEmptyState(
                messageResource = R.string.gallery_empty,
                modifier = Modifier.weight(1f),
            )
            visibleResults.isEmpty() -> GalleryEmptyState(
                messageResource = R.string.gallery_favorites_empty,
                modifier = Modifier.weight(1f),
            )
            else -> GalleryResults(
                results = visibleResults,
                layoutMode = layoutMode,
                modifier = Modifier.weight(1f),
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onToggleSelection = { id ->
                    selectedResultIds = if (id.value in selectedIds) {
                        selectedResultIds - id.value
                    } else {
                        selectedResultIds + id.value
                    }
                },
                onOpenResult = onOpenResult,
                onShareResult = onShareResult,
                onReuseResult = onReuseResult,
                onShowPromptDetails = { result -> promptDetailsResultId = result.id.value },
                onSetFavorite = onSetFavorite,
            )
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
                    Text(stringResource(confirmation.messageResource, selectedResults.size))
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
    promptDetailsResultId
        ?.let { resultId -> results.firstOrNull { result -> result.id.value == resultId } }
        ?.let { result ->
            ResultPromptDetailsDialog(
                prompt = result.request.prompt.takeUnless(String::isBlank),
                onDismiss = { promptDetailsResultId = null },
                onCopyPrompt = onCopyPrompt,
                onReusePrompt = { prompt ->
                    promptDetailsResultId = null
                    onReusePrompt(prompt)
                },
            )
        }
}

@Composable
private fun GalleryEmptyState(
    @StringRes messageResource: Int,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(messageResource),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    layoutMode: GalleryLayoutMode,
    isWorking: Boolean,
    onToggleFavoritesOnly: () -> Unit,
    onLayoutModeChange: (GalleryLayoutMode) -> Unit,
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
            IconButton(
                onClick = onToggleFavoritesOnly,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (favoritesOnly) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (favoritesOnly) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
                modifier = Modifier.semantics { selected = favoritesOnly },
            ) {
                Icon(
                    if (favoritesOnly) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.gallery_favorites_only),
                )
            }
            Spacer(Modifier.weight(1f))
            GalleryLayoutSelector(layoutMode, onLayoutModeChange)
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
private fun GalleryLayoutSelector(
    layoutMode: GalleryLayoutMode,
    onLayoutModeChange: (GalleryLayoutMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row {
            GalleryLayoutMode.entries.forEach { mode ->
                val selected = layoutMode == mode
                val descriptor = mode.descriptor
                IconButton(
                    onClick = { onLayoutModeChange(mode) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { this.selected = selected }
                        .testTag(descriptor.testTag),
                ) {
                    Icon(descriptor.icon, contentDescription = stringResource(descriptor.labelResource))
                }
            }
        }
    }
}

@Composable
private fun GalleryResults(
    results: List<GeneratedResult>,
    layoutMode: GalleryLayoutMode,
    modifier: Modifier,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (GeneratedResultId) -> Unit,
    onOpenResult: (GeneratedAssetReference) -> Unit,
    onShareResult: (GeneratedAssetReference) -> Unit,
    onReuseResult: (GeneratedAssetReference) -> Unit,
    onShowPromptDetails: (GeneratedResult) -> Unit,
    onSetFavorite: (GeneratedResultId, Boolean) -> Unit,
) {
    val contentPadding = PaddingValues(12.dp)
    when (layoutMode) {
        GalleryLayoutMode.LargeGrid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            modifier = modifier.fillMaxSize().testTag(GALLERY_LARGE_GRID_TEST_TAG),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(results, key = { result -> result.id.value }) { result ->
                LargeGalleryItem(
                    result = result,
                    selectionMode = selectionMode,
                    selected = result.id.value in selectedIds,
                    onToggleSelection = { onToggleSelection(result.id) },
                    onOpen = { onOpenResult(result.asset) },
                    onShare = { onShareResult(result.asset) },
                    onReuse = { onReuseResult(result.asset) },
                    onShowPromptDetails = { onShowPromptDetails(result) },
                    onSetFavorite = { favorite -> onSetFavorite(result.id, favorite) },
                )
            }
        }
        GalleryLayoutMode.CompactGrid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 132.dp),
            modifier = modifier.fillMaxSize().testTag(GALLERY_COMPACT_GRID_TEST_TAG),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gridItems(results, key = { result -> result.id.value }) { result ->
                CompactGalleryItem(
                    result = result,
                    selectionMode = selectionMode,
                    selected = result.id.value in selectedIds,
                    onToggleSelection = { onToggleSelection(result.id) },
                    onOpen = { onOpenResult(result.asset) },
                    onShare = { onShareResult(result.asset) },
                    onReuse = { onReuseResult(result.asset) },
                    onShowPromptDetails = { onShowPromptDetails(result) },
                    onSetFavorite = { favorite -> onSetFavorite(result.id, favorite) },
                )
            }
        }
        GalleryLayoutMode.List -> LazyColumn(
            modifier = modifier.fillMaxSize().testTag(GALLERY_LIST_TEST_TAG),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listItems(results, key = { result -> result.id.value }) { result ->
                ListGalleryItem(
                    result = result,
                    selectionMode = selectionMode,
                    selected = result.id.value in selectedIds,
                    onToggleSelection = { onToggleSelection(result.id) },
                    onOpen = { onOpenResult(result.asset) },
                    onShare = { onShareResult(result.asset) },
                    onReuse = { onReuseResult(result.asset) },
                    onShowPromptDetails = { onShowPromptDetails(result) },
                    onSetFavorite = { favorite -> onSetFavorite(result.id, favorite) },
                )
            }
        }
    }
}

@Composable
private fun LargeGalleryItem(
    result: GeneratedResult,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onShowPromptDetails: () -> Unit,
    onSetFavorite: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = galleryItemModifier(result, selectionMode, onToggleSelection, onOpen),
    ) {
        Column {
            Box {
                GeneratedThumbnail(
                    asset = result.asset,
                    maxDimension = LARGE_THUMBNAIL_MAX_DIMENSION,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                GallerySelectionCheckbox(selectionMode, selected, onToggleSelection)
            }
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GalleryPrompt(result, maxLines = 2)
                GalleryTimestamp(result.createdAtEpochMillis)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    GalleryFavoriteButton(result, selectionMode, onSetFavorite)
                    if (!selectionMode) {
                        GalleryItemActionsMenu(onOpen, onShare, onReuse, onShowPromptDetails)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactGalleryItem(
    result: GeneratedResult,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onShowPromptDetails: () -> Unit,
    onSetFavorite: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = galleryItemModifier(result, selectionMode, onToggleSelection, onOpen),
    ) {
        Column {
            Box {
                GeneratedThumbnail(
                    asset = result.asset,
                    maxDimension = COMPACT_THUMBNAIL_MAX_DIMENSION,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                GallerySelectionCheckbox(selectionMode, selected, onToggleSelection)
            }
            GalleryPrompt(
                result = result,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp),
            )
            if (!selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    GalleryFavoriteButton(result, false, onSetFavorite)
                    GalleryItemActionsMenu(onOpen, onShare, onReuse, onShowPromptDetails)
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ListGalleryItem(
    result: GeneratedResult,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onShowPromptDetails: () -> Unit,
    onSetFavorite: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = galleryItemModifier(result, selectionMode, onToggleSelection, onOpen),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                GeneratedThumbnail(
                    asset = result.asset,
                    maxDimension = LIST_THUMBNAIL_MAX_DIMENSION,
                    modifier = Modifier.size(96.dp),
                )
                GallerySelectionCheckbox(selectionMode, selected, onToggleSelection)
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GalleryPrompt(result, maxLines = 2)
                Text(
                    stringResource(
                        R.string.gallery_result_metadata,
                        result.request.profileName,
                        result.request.model,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                GalleryTimestamp(result.createdAtEpochMillis)
            }
            if (!selectionMode) {
                Column {
                    GalleryFavoriteButton(result, false, onSetFavorite)
                    GalleryItemActionsMenu(onOpen, onShare, onReuse, onShowPromptDetails)
                }
            }
        }
    }
}

private fun galleryItemModifier(
    result: GeneratedResult,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
): Modifier = Modifier
    .fillMaxWidth()
    .testTag("$GALLERY_SELECTION_TEST_TAG_PREFIX${result.id.value}")
    .clickable(onClick = if (selectionMode) onToggleSelection else onOpen)

@Composable
private fun BoxScope.GallerySelectionCheckbox(
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    if (selectionMode) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggleSelection() },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun GalleryPrompt(
    result: GeneratedResult,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        result.request.prompt,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun GalleryTimestamp(epochMillis: Long) {
    Text(
        rememberTimestamp(epochMillis),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun GalleryFavoriteButton(
    result: GeneratedResult,
    selectionMode: Boolean,
    onSetFavorite: (Boolean) -> Unit,
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
}

@Composable
private fun GalleryItemActionsMenu(
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onShowPromptDetails: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_result_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.view_prompt_details)) },
                onClick = {
                    expanded = false
                    onShowPromptDetails()
                },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open_result)) },
                onClick = {
                    expanded = false
                    onOpen()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_result)) },
                onClick = {
                    expanded = false
                    onShare()
                },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reuse_as_reference)) },
                onClick = {
                    expanded = false
                    onReuse()
                },
                leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun ResultPromptDetailsDialog(
    prompt: String?,
    onDismiss: () -> Unit,
    onCopyPrompt: (String) -> Unit,
    onReusePrompt: (String) -> Unit,
) {
    var copied by rememberSaveable(prompt) { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.testTag(GALLERY_PROMPT_DETAILS_TEST_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.result_prompt_details_title)) },
        text = {
            if (prompt == null) {
                Text(stringResource(R.string.result_prompt_unavailable))
            } else {
                SelectionContainer {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(text = prompt)
                        if (copied) {
                            Text(
                                text = stringResource(R.string.prompt_copied),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss_dialog))
                }
                if (prompt != null) {
                    TextButton(
                        onClick = {
                            onCopyPrompt(prompt)
                            copied = true
                        },
                    ) {
                        Text(stringResource(R.string.copy_prompt))
                    }
                    TextButton(onClick = { onReusePrompt(prompt) }) {
                        Text(stringResource(R.string.reuse_prompt_in_generate))
                    }
                }
            }
        },
    )
}

@Composable
private fun GeneratedThumbnail(
    asset: GeneratedAssetReference,
    maxDimension: Int,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val loader = androidx.compose.runtime.remember(context, maxDimension) {
        MediaThumbnailLoader(context.contentResolver, maxDimension)
    }
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = asset.location,
        key2 = maxDimension,
    ) {
        value = loader.load(asset.location)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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
