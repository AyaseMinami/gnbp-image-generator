package io.github.ayaseminami.gnbp.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.TaskStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun GalleryScreen(
    tasks: List<GenerationTask>,
    onOpenResult: (GeneratedAssetReference) -> Unit,
    onShareResult: (GeneratedAssetReference) -> Unit,
    onReuseResult: (GeneratedAssetReference) -> Unit,
) {
    val entries = tasks.mapNotNull { task ->
        (task.status as? TaskStatus.Succeeded)?.let { status -> task to status.asset }
    }.sortedByDescending { (task, _) -> task.finishedAtEpochMillis ?: task.createdAtEpochMillis }
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.gallery_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { (task, _) -> task.id.value }) { (task, asset) ->
            GalleryItem(
                task = task,
                asset = asset,
                onOpen = { onOpenResult(asset) },
                onShare = { onShareResult(asset) },
                onReuse = { onReuseResult(asset) },
            )
        }
    }
}

@Composable
private fun GalleryItem(
    task: GenerationTask,
    asset: GeneratedAssetReference,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
) {
    Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            GeneratedThumbnail(asset)
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    task.request.prompt,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    rememberTimestamp(task.finishedAtEpochMillis ?: task.createdAtEpochMillis),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onReuse) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = stringResource(R.string.reuse_as_reference),
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_result),
                        )
                    }
                    IconButton(onClick = onOpen) {
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
