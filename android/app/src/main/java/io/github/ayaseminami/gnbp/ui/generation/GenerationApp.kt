package io.github.ayaseminami.gnbp.ui.generation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.ui.theme.GnbpTheme
import java.text.DateFormat
import java.util.Date

private enum class AppSection {
    Generate,
    Tasks,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationApp(
    state: GenerationUiState,
    tasks: List<GenerationTask>,
    references: List<DurableReferenceAsset>,
    failedReferenceCount: Int,
    onSelectProfile: (ProfileId) -> Unit,
    onPromptChange: (String) -> Unit,
    onBatchCountChange: (Int) -> Unit,
    onGeminiAspectRatioChange: (String) -> Unit,
    onGeminiImageSizeChange: (String) -> Unit,
    onGeminiTemperatureChange: (Double) -> Unit,
    onOpenAiSizeChange: (String) -> Unit,
    onOpenAiQualityChange: (String) -> Unit,
    onPickReferences: () -> Unit,
    onRemoveReference: (MediaAssetId) -> Unit,
    onSubmit: () -> Unit,
    onCancelTask: (TaskId) -> Unit,
    onRetryTask: (TaskId) -> Unit,
    onOpenResult: (AssetRef) -> Unit,
    onFeedbackShown: () -> Unit,
) {
    var selectedSectionName by rememberSaveable { mutableStateOf(AppSection.Generate.name) }
    val selectedSection = AppSection.valueOf(selectedSectionName)
    val snackbarHostState = remember { SnackbarHostState() }
    val feedbackMessage = state.feedback?.let { feedbackText(it) }
    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            snackbarHostState.showSnackbar(feedbackMessage)
            onFeedbackShown()
        }
    }

    GnbpTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Column {
                    TopAppBar(title = { Text(stringResource(R.string.app_name)) })
                    PrimaryTabRow(selectedTabIndex = selectedSection.ordinal) {
                        AppSection.entries.forEach { section ->
                            Tab(
                                selected = selectedSection == section,
                                onClick = { selectedSectionName = section.name },
                                text = {
                                    Text(
                                        stringResource(
                                            if (section == AppSection.Generate) {
                                                R.string.tab_generate
                                            } else {
                                                R.string.tab_tasks
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (selectedSection) {
                    AppSection.Generate -> GenerateScreen(
                        state = state,
                        references = references,
                        failedReferenceCount = failedReferenceCount,
                        onSelectProfile = onSelectProfile,
                        onPromptChange = onPromptChange,
                        onBatchCountChange = onBatchCountChange,
                        onGeminiAspectRatioChange = onGeminiAspectRatioChange,
                        onGeminiImageSizeChange = onGeminiImageSizeChange,
                        onGeminiTemperatureChange = onGeminiTemperatureChange,
                        onOpenAiSizeChange = onOpenAiSizeChange,
                        onOpenAiQualityChange = onOpenAiQualityChange,
                        onPickReferences = onPickReferences,
                        onRemoveReference = onRemoveReference,
                        onSubmit = onSubmit,
                    )
                    AppSection.Tasks -> TasksScreen(
                        tasks = tasks,
                        onCancelTask = onCancelTask,
                        onRetryTask = onRetryTask,
                        onOpenResult = onOpenResult,
                    )
                }
            }
        }
    }
}

@Composable
private fun GenerateScreen(
    state: GenerationUiState,
    references: List<DurableReferenceAsset>,
    failedReferenceCount: Int,
    onSelectProfile: (ProfileId) -> Unit,
    onPromptChange: (String) -> Unit,
    onBatchCountChange: (Int) -> Unit,
    onGeminiAspectRatioChange: (String) -> Unit,
    onGeminiImageSizeChange: (String) -> Unit,
    onGeminiTemperatureChange: (Double) -> Unit,
    onOpenAiSizeChange: (String) -> Unit,
    onOpenAiQualityChange: (String) -> Unit,
    onPickReferences: () -> Unit,
    onRemoveReference: (MediaAssetId) -> Unit,
    onSubmit: () -> Unit,
) {
    val selectedProfile = state.profiles.firstOrNull { it.id == state.selectedProfileId }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OptionPicker(
            label = stringResource(R.string.profile_label),
            selected = selectedProfile?.name ?: stringResource(R.string.no_profile),
            options = state.profiles,
            optionLabel = { it.name },
            enabled = !state.isLoading && state.profiles.isNotEmpty() && !state.isSubmitting,
            onSelect = { onSelectProfile(it.id) },
        )
        OutlinedTextField(
            value = state.prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isSubmitting,
            label = { Text(stringResource(R.string.prompt_label)) },
            placeholder = { Text(stringResource(R.string.prompt_placeholder)) },
            minLines = 5,
            maxLines = 10,
        )
        if (selectedProfile?.providerKind == ProviderKind.OpenAiCompatible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OptionPicker(
                    label = stringResource(R.string.image_size_label),
                    selected = state.openAiSize,
                    options = OPENAI_SIZES,
                    optionLabel = { it },
                    enabled = !state.isSubmitting,
                    onSelect = onOpenAiSizeChange,
                    modifier = Modifier.weight(1f),
                )
                OptionPicker(
                    label = stringResource(R.string.quality_label),
                    selected = state.openAiQuality,
                    options = OPENAI_QUALITIES,
                    optionLabel = { it },
                    enabled = !state.isSubmitting,
                    onSelect = onOpenAiQualityChange,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OptionPicker(
                    label = stringResource(R.string.aspect_ratio_label),
                    selected = state.geminiAspectRatio,
                    options = GEMINI_ASPECT_RATIOS,
                    optionLabel = { it },
                    enabled = !state.isSubmitting,
                    onSelect = onGeminiAspectRatioChange,
                    modifier = Modifier.weight(1f),
                )
                OptionPicker(
                    label = stringResource(R.string.image_size_label),
                    selected = state.geminiImageSize,
                    options = GEMINI_IMAGE_SIZES,
                    optionLabel = { it },
                    enabled = !state.isSubmitting,
                    onSelect = onGeminiImageSizeChange,
                    modifier = Modifier.weight(1f),
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.temperature_label, state.geminiTemperature),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = state.geminiTemperature.toFloat(),
                    onValueChange = { onGeminiTemperatureChange(it.toDouble()) },
                    enabled = !state.isSubmitting,
                    valueRange = 0f..2f,
                    steps = 19,
                )
            }
        }
        BatchStepper(
            count = state.batchCount,
            enabled = !state.isSubmitting,
            onCountChange = onBatchCountChange,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.reference_images_label),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedButton(
                onClick = onPickReferences,
                enabled = !state.isSubmitting,
            ) {
                Text(stringResource(R.string.pick_reference_images))
            }
            references.forEach { reference ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = reference.displayName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(
                        onClick = { onRemoveReference(reference.id) },
                        enabled = !state.isSubmitting,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.remove_reference),
                        )
                    }
                }
                HorizontalDivider()
            }
            if (references.isNotEmpty()) {
                Text(
                    text = pluralStringResource(
                        R.plurals.selected_reference_count,
                        references.size,
                        references.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (failedReferenceCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.reference_import_failure_count,
                        failedReferenceCount,
                        failedReferenceCount,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Button(
            onClick = onSubmit,
            enabled = !state.isSubmitting && selectedProfile != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (state.isSubmitting) {
                        R.string.submitting_generation
                    } else {
                        R.string.enqueue_generation
                    },
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BatchStepper(
    count: Int,
    enabled: Boolean,
    onCountChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.batch_count_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onCountChange(count - 1) },
                enabled = enabled && count > 1,
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.decrease_batch_count),
                )
            }
            Text(
                text = count.toString(),
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = { onCountChange(count + 1) },
                enabled = enabled && count < 16,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.increase_batch_count),
                )
            }
        }
    }
}

@Composable
private fun TasksScreen(
    tasks: List<GenerationTask>,
    onCancelTask: (TaskId) -> Unit,
    onRetryTask: (TaskId) -> Unit,
    onOpenResult: (AssetRef) -> Unit,
) {
    var uncertainRetryTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    if (tasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.tasks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tasks, key = { it.id.value }) { task ->
                TaskCard(
                    task = task,
                    onCancel = { onCancelTask(task.id) },
                    onRetry = {
                        if (task.status is TaskStatus.OutcomeUnknown) {
                            uncertainRetryTaskId = task.id.value
                        } else {
                            onRetryTask(task.id)
                        }
                    },
                    onOpenResult = onOpenResult,
                )
            }
        }
    }
    uncertainRetryTaskId?.let { rawTaskId ->
        AlertDialog(
            onDismissRequest = { uncertainRetryTaskId = null },
            title = { Text(stringResource(R.string.retry_unknown_title)) },
            text = { Text(stringResource(R.string.retry_unknown_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        uncertainRetryTaskId = null
                        onRetryTask(TaskId(rawTaskId))
                    },
                ) { Text(stringResource(R.string.confirm_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { uncertainRetryTaskId = null }) {
                    Text(stringResource(R.string.dismiss_dialog))
                }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: GenerationTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenResult: (AssetRef) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = taskStatusLabel(task.status),
                    color = taskStatusColor(task.status),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                when (val status = task.status) {
                    TaskStatus.Queued,
                    TaskStatus.Running,
                    -> IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = stringResource(R.string.cancel_task),
                        )
                    }
                    is TaskStatus.Failed,
                    is TaskStatus.Cancelled,
                    is TaskStatus.OutcomeUnknown,
                    -> IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.retry_task),
                        )
                    }
                    is TaskStatus.Succeeded -> IconButton(onClick = { onOpenResult(status.asset) }) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = stringResource(R.string.open_result),
                        )
                    }
                }
            }
            Text(
                text = task.request.profileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.request.prompt,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.task_created_at,
                    remember(task.createdAtEpochMillis) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(task.createdAtEpochMillis))
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (task.status is TaskStatus.OutcomeUnknown) {
                Text(
                    text = stringResource(R.string.task_unknown_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun <T> OptionPicker(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun taskStatusLabel(status: TaskStatus): String = stringResource(
    when (status) {
        TaskStatus.Queued -> R.string.task_status_queued
        TaskStatus.Running -> R.string.task_status_running
        is TaskStatus.Succeeded -> R.string.task_status_succeeded
        is TaskStatus.Failed -> R.string.task_status_failed
        is TaskStatus.Cancelled -> R.string.task_status_cancelled
        is TaskStatus.OutcomeUnknown -> R.string.task_status_unknown
    },
)

@Composable
private fun taskStatusColor(status: TaskStatus): Color = when (status) {
    is TaskStatus.Succeeded -> MaterialTheme.colorScheme.primary
    is TaskStatus.Failed,
    is TaskStatus.OutcomeUnknown,
    -> MaterialTheme.colorScheme.error
    is TaskStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
    TaskStatus.Queued,
    TaskStatus.Running,
    -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun feedbackText(feedback: GenerationFeedback): String = when (feedback) {
    is GenerationFeedback.Queued -> pluralStringResource(
        R.plurals.feedback_tasks_queued,
        feedback.count,
        feedback.count,
    )
    GenerationFeedback.NoProfile -> stringResource(R.string.feedback_no_profile)
    GenerationFeedback.ProfileUnavailable -> stringResource(R.string.feedback_profile_unavailable)
    GenerationFeedback.BlankPrompt -> stringResource(R.string.feedback_blank_prompt)
    GenerationFeedback.InvalidRequest -> stringResource(R.string.feedback_invalid_request)
    GenerationFeedback.ReferencePreparationFailed -> stringResource(R.string.feedback_reference_failed)
    GenerationFeedback.TaskNotAvailable -> stringResource(R.string.feedback_task_unavailable)
    GenerationFeedback.PermissionDenied -> stringResource(R.string.feedback_permission_denied)
}

private val GEMINI_ASPECT_RATIOS = listOf("1:1", "4:5", "3:4", "2:3", "9:16", "4:3", "16:9", "21:9")
private val GEMINI_IMAGE_SIZES = listOf("1K", "2K", "4K")
private val OPENAI_SIZES = listOf(
    "auto",
    "1024x1024",
    "1536x1024",
    "1024x1536",
    "2048x2048",
    "2048x1152",
    "1152x2048",
    "3840x2160",
    "2160x3840",
)
private val OPENAI_QUALITIES = listOf("auto", "low", "medium", "high")
