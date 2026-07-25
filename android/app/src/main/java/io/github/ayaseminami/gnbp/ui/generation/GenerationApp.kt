package io.github.ayaseminami.gnbp.ui.generation

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.media.DurableReferenceAsset
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.prompt.PromptId
import io.github.ayaseminami.gnbp.persistence.prompt.PromptPreset
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResult
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import io.github.ayaseminami.gnbp.persistence.settings.GalleryLayoutMode
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.ui.theme.GnbpTheme
import io.github.ayaseminami.gnbp.ui.gallery.GalleryScreen
import io.github.ayaseminami.gnbp.ui.gallery.GalleryBulkAction
import io.github.ayaseminami.gnbp.ui.gallery.GalleryManagementFeedback
import io.github.ayaseminami.gnbp.ui.gallery.GalleryManagementState
import io.github.ayaseminami.gnbp.ui.settings.SettingsActions
import io.github.ayaseminami.gnbp.ui.settings.SettingsFailure
import io.github.ayaseminami.gnbp.ui.settings.SettingsFeedback
import io.github.ayaseminami.gnbp.ui.settings.SettingsScreen
import io.github.ayaseminami.gnbp.ui.settings.SettingsUiState
import java.text.DateFormat
import java.util.Date

private enum class AppSection {
    Generate,
    Tasks,
    Gallery,
    Settings,
}

internal const val GENERATION_SUBMIT_TEST_TAG = "generation-submit"
internal const val GENERATION_APP_SURFACE_TEST_TAG = "generation-app-surface"
internal const val TASKS_SELECTION_MODE_TEST_TAG = "tasks-selection-mode"
internal const val TASKS_SELECT_ALL_TEST_TAG = "tasks-select-all"
internal const val TASKS_DELETE_SELECTED_TEST_TAG = "tasks-delete-selected"
internal const val TASKS_CANCEL_SELECTED_TEST_TAG = "tasks-cancel-selected"
internal const val TASKS_CLEAR_FAILED_TEST_TAG = "tasks-clear-failed"
internal const val TASK_SELECTION_TEST_TAG_PREFIX = "task-selection-"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationApp(
    state: GenerationUiState,
    settingsState: SettingsUiState,
    taskManagementState: TaskManagementState,
    galleryManagementState: GalleryManagementState,
    settingsActions: SettingsActions,
    tasks: List<GenerationTask>,
    generatedResults: List<GeneratedResult>,
    references: List<DurableReferenceAsset>,
    failedReferenceCount: Int,
    onSelectProfile: (ProfileId) -> Unit,
    onSelectPrompt: (PromptId?) -> Unit,
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
    onCancelTasks: (Set<TaskId>) -> Unit,
    onDeleteTasks: (Set<TaskId>) -> Unit,
    onRetryTask: (TaskId) -> Unit,
    onOpenResult: (GeneratedAssetReference) -> Unit,
    onShareResult: (GeneratedAssetReference) -> Unit,
    onShareResults: (Set<GeneratedResultId>) -> Unit,
    onReuseResult: (GeneratedAssetReference) -> Unit,
    onCopyPrompt: (String) -> Unit,
    onCopyTaskDiagnostic: (String) -> Unit,
    onReusePrompt: (String) -> Unit,
    onSetResultFavorite: (GeneratedResultId, Boolean) -> Unit,
    onRemoveResultsFromLibrary: (Set<GeneratedResultId>) -> Unit,
    onDeleteResultsFromDevice: (Set<GeneratedResultId>) -> Unit,
    onGalleryLayoutModeChange: (GalleryLayoutMode) -> Unit,
    onFeedbackShown: () -> Unit,
    onSettingsFeedbackShown: () -> Unit,
    onTaskManagementFeedbackShown: () -> Unit,
    onGalleryManagementFeedbackShown: () -> Unit,
) {
    var selectedSectionName by rememberSaveable { mutableStateOf(AppSection.Generate.name) }
    val selectedSection = AppSection.valueOf(selectedSectionName)
    val snackbarHostState = remember { SnackbarHostState() }
    val generationFeedbackMessage = state.feedback?.let { feedbackText(it) }
    val settingsFeedbackMessage = settingsState.feedback?.let { settingsFeedbackText(it) }
    val taskManagementFeedbackMessage = taskManagementState.feedback?.let { taskManagementFeedbackText(it) }
    val galleryManagementFeedbackMessage = galleryManagementState.feedback?.let {
        galleryManagementFeedbackText(it)
    }
    LaunchedEffect(
        generationFeedbackMessage,
        settingsFeedbackMessage,
        taskManagementFeedbackMessage,
        galleryManagementFeedbackMessage,
    ) {
        when {
            generationFeedbackMessage != null -> {
                snackbarHostState.showSnackbar(generationFeedbackMessage)
                onFeedbackShown()
            }
            settingsFeedbackMessage != null -> {
                snackbarHostState.showSnackbar(settingsFeedbackMessage)
                onSettingsFeedbackShown()
            }
            taskManagementFeedbackMessage != null -> {
                snackbarHostState.showSnackbar(taskManagementFeedbackMessage)
                onTaskManagementFeedbackShown()
            }
            galleryManagementFeedbackMessage != null -> {
                snackbarHostState.showSnackbar(galleryManagementFeedbackMessage)
                onGalleryManagementFeedbackShown()
            }
        }
    }

    GnbpTheme(themeMode = settingsState.appSettings.themeMode) {
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
                                            section.labelResource(),
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
                    .padding(contentPadding)
                    .testTag(GENERATION_APP_SURFACE_TEST_TAG),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (selectedSection) {
                    AppSection.Generate -> GenerateScreen(
                        state = state,
                        references = references,
                        failedReferenceCount = failedReferenceCount,
                        onSelectProfile = onSelectProfile,
                        onSelectPrompt = onSelectPrompt,
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
                        isDeleting = taskManagementState.isDeleting,
                        onCancelTasks = onCancelTasks,
                        onDeleteTasks = onDeleteTasks,
                        onRetryTask = onRetryTask,
                        onOpenResult = onOpenResult,
                        onCopyTaskDiagnostic = onCopyTaskDiagnostic,
                    )
                    AppSection.Gallery -> GalleryScreen(
                        results = generatedResults,
                        layoutMode = settingsState.appSettings.galleryLayoutMode,
                        isWorking = galleryManagementState.isWorking,
                        onLayoutModeChange = onGalleryLayoutModeChange,
                        onOpenResult = onOpenResult,
                        onShareResult = onShareResult,
                        onShareResults = onShareResults,
                        onReuseResult = { asset ->
                            onReuseResult(asset)
                            selectedSectionName = AppSection.Generate.name
                        },
                        onCopyPrompt = onCopyPrompt,
                        onReusePrompt = { prompt ->
                            onReusePrompt(prompt)
                            selectedSectionName = AppSection.Generate.name
                        },
                        onSetFavorite = onSetResultFavorite,
                        onRemoveFromLibrary = onRemoveResultsFromLibrary,
                        onDeleteFromDevice = onDeleteResultsFromDevice,
                    )
                    AppSection.Settings -> SettingsScreen(
                        state = settingsState,
                        actions = settingsActions,
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
    onSelectPrompt: (PromptId?) -> Unit,
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
        if (selectedProfile?.isUnsafe == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.generation_unsafe_profile_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        PromptPresetPicker(
            prompts = state.prompts,
            selectedPromptId = state.selectedPromptId,
            enabled = !state.isSubmitting,
            onSelect = onSelectPrompt,
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
            enabled = !state.isSubmitting && selectedProfile != null && state.prompt.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(GENERATION_SUBMIT_TEST_TAG),
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
private fun PromptPresetPicker(
    prompts: List<PromptPreset>,
    selectedPromptId: PromptId?,
    enabled: Boolean,
    onSelect: (PromptId?) -> Unit,
) {
    val noPresetLabel = stringResource(R.string.no_prompt_preset)
    val selected = prompts.firstOrNull { it.id == selectedPromptId }
    val options = remember(prompts) { listOf<PromptPreset?>(null) + prompts }
    OptionPicker(
        label = stringResource(R.string.prompt_preset_label),
        selected = selected?.name ?: noPresetLabel,
        options = options,
        optionLabel = { it?.name ?: noPresetLabel },
        enabled = enabled,
        onSelect = { onSelect(it?.id) },
    )
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

private enum class TaskDeleteConfirmation {
    Selected,
    Failed,
}

@Composable
internal fun TasksScreen(
    tasks: List<GenerationTask>,
    isDeleting: Boolean,
    onCancelTasks: (Set<TaskId>) -> Unit,
    onDeleteTasks: (Set<TaskId>) -> Unit,
    onRetryTask: (TaskId) -> Unit,
    onOpenResult: (GeneratedAssetReference) -> Unit,
    onCopyTaskDiagnostic: (String) -> Unit,
) {
    var uncertainRetryTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedTaskIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingConfirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeletionIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val visibleIds = tasks.map { task -> task.id.value }
    val selectedIds = selectedTaskIds.toSet()
    val selectedActiveIds = tasks
        .filter { task ->
            task.id.value in selectedIds &&
                (task.status == TaskStatus.Queued || task.status == TaskStatus.Running)
        }
        .mapTo(mutableSetOf(), GenerationTask::id)
    val failedIds = tasks
        .filter { task -> task.status is TaskStatus.Failed }
        .map { task -> task.id.value }
    LaunchedEffect(visibleIds) {
        selectedTaskIds = selectedTaskIds.filter { id -> id in visibleIds }
        if (visibleIds.isEmpty()) selectionMode = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TaskSelectionToolbar(
            selectionMode = selectionMode,
            selectedCount = selectedTaskIds.size,
            allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all(selectedIds::contains),
            hasVisibleTasks = visibleIds.isNotEmpty(),
            hasActiveSelection = selectedActiveIds.isNotEmpty(),
            failedCount = failedIds.size,
            isDeleting = isDeleting,
            onEnterSelection = { selectionMode = true },
            onExitSelection = {
                selectionMode = false
                selectedTaskIds = emptyList()
            },
            onToggleSelectAll = {
                selectedTaskIds = if (visibleIds.all(selectedIds::contains)) emptyList() else visibleIds
            },
            onCancelSelected = { onCancelTasks(selectedActiveIds) },
            onDeleteSelected = {
                pendingDeletionIds = selectedTaskIds
                pendingConfirmation = TaskDeleteConfirmation.Selected.name
            },
            onClearFailed = {
                pendingDeletionIds = failedIds
                pendingConfirmation = TaskDeleteConfirmation.Failed.name
            },
        )
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.tasks_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id.value }) { task ->
                    TaskCard(
                        task = task,
                        selectionMode = selectionMode,
                        selected = task.id.value in selectedIds,
                        onToggleSelection = {
                            selectedTaskIds = if (task.id.value in selectedIds) {
                                selectedTaskIds - task.id.value
                            } else {
                                selectedTaskIds + task.id.value
                            }
                        },
                        onCancel = { onCancelTasks(setOf(task.id)) },
                        onRetry = {
                            if (task.status is TaskStatus.OutcomeUnknown) {
                                uncertainRetryTaskId = task.id.value
                            } else {
                                onRetryTask(task.id)
                            }
                        },
                        onOpenResult = onOpenResult,
                        onCopyTaskDiagnostic = onCopyTaskDiagnostic,
                    )
                }
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
    pendingConfirmation?.let { rawConfirmation ->
        val confirmation = TaskDeleteConfirmation.valueOf(rawConfirmation)
        AlertDialog(
            onDismissRequest = {
                pendingConfirmation = null
                pendingDeletionIds = emptyList()
            },
            title = {
                Text(
                    stringResource(
                        if (confirmation == TaskDeleteConfirmation.Failed) {
                            R.string.confirm_clear_failed_title
                        } else {
                            R.string.confirm_delete_tasks_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (confirmation == TaskDeleteConfirmation.Failed) {
                            R.string.confirm_clear_failed_message
                        } else {
                            R.string.confirm_delete_tasks_message
                        },
                        pendingDeletionIds.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeleting,
                    onClick = {
                        val requested = pendingDeletionIds.mapTo(mutableSetOf(), ::TaskId)
                        pendingConfirmation = null
                        pendingDeletionIds = emptyList()
                        onDeleteTasks(requested)
                    },
                ) {
                    Text(
                        stringResource(
                            if (isDeleting) R.string.deleting_tasks else R.string.confirm_delete_tasks,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingConfirmation = null
                        pendingDeletionIds = emptyList()
                    },
                ) { Text(stringResource(R.string.dismiss_dialog)) }
            },
        )
    }
}

@Composable
private fun TaskSelectionToolbar(
    selectionMode: Boolean,
    selectedCount: Int,
    allVisibleSelected: Boolean,
    hasVisibleTasks: Boolean,
    hasActiveSelection: Boolean,
    failedCount: Int,
    isDeleting: Boolean,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onCancelSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearFailed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.exit_task_selection))
            }
            Text(pluralStringResource(R.plurals.selected_task_count, selectedCount, selectedCount))
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onToggleSelectAll,
                modifier = Modifier.testTag(TASKS_SELECT_ALL_TEST_TAG),
            ) {
                Icon(
                    Icons.Default.SelectAll,
                    contentDescription = stringResource(
                        if (allVisibleSelected) R.string.clear_task_selection else R.string.select_all_tasks,
                    ),
                )
            }
            if (hasActiveSelection) {
                IconButton(
                    onClick = onCancelSelected,
                    modifier = Modifier.testTag(TASKS_CANCEL_SELECTED_TEST_TAG),
                ) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = stringResource(R.string.cancel_selected_tasks),
                    )
                }
            }
            IconButton(
                onClick = onDeleteSelected,
                enabled = selectedCount > 0 && !isDeleting,
                modifier = Modifier.testTag(TASKS_DELETE_SELECTED_TEST_TAG),
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_selected_tasks))
            }
        } else {
            if (failedCount > 0) {
                TextButton(
                    onClick = onClearFailed,
                    enabled = !isDeleting,
                    modifier = Modifier.testTag(TASKS_CLEAR_FAILED_TEST_TAG),
                ) { Text(stringResource(R.string.clear_failed_tasks)) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onEnterSelection,
                enabled = hasVisibleTasks && !isDeleting,
                modifier = Modifier.testTag(TASKS_SELECTION_MODE_TEST_TAG),
            ) {
                Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.select_tasks))
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: GenerationTask,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenResult: (GeneratedAssetReference) -> Unit,
    onCopyTaskDiagnostic: (String) -> Unit,
) {
    val diagnostic = LocalContext.current.taskDiagnosticSummary(task.status)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$TASK_SELECTION_TEST_TAG_PREFIX${task.id.value}")
            .clickable(enabled = selectionMode, onClick = onToggleSelection),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                    }
                }
                Text(
                    text = taskStatusLabel(task.status),
                    color = taskStatusColor(task.status),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                if (!selectionMode) when (val status = task.status) {
                    TaskStatus.Queued,
                    TaskStatus.Running,
                    -> IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = stringResource(R.string.cancel_task),
                        )
                    }
                    is TaskStatus.Failed -> {
                        diagnostic?.let { summary ->
                            IconButton(onClick = { onCopyTaskDiagnostic(summary) }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_task_diagnostic),
                                )
                            }
                        }
                        IconButton(onClick = onRetry) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.retry_task),
                            )
                        }
                    }
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
                            Icons.AutoMirrored.Filled.OpenInNew,
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
            diagnostic?.let { summary ->
                Text(
                    text = summary,
                    color = if (
                        task.status is TaskStatus.Failed ||
                        task.status is TaskStatus.OutcomeUnknown
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
    GenerationFeedback.ResultUnavailable -> stringResource(R.string.feedback_result_unavailable)
    GenerationFeedback.PromptCopied -> stringResource(R.string.feedback_prompt_copied)
    GenerationFeedback.DiagnosticCopied -> stringResource(R.string.feedback_diagnostic_copied)
}

@Composable
private fun taskManagementFeedbackText(feedback: TaskManagementFeedback): String = when (feedback) {
    is TaskManagementFeedback.DeletionCompleted -> stringResource(
        R.string.task_deletion_feedback,
        feedback.deletedCount,
        feedback.blockedCount,
        feedback.cleanupFailedCount,
    )
    TaskManagementFeedback.DeletionFailed -> stringResource(R.string.task_deletion_failed)
}

@Composable
private fun galleryManagementFeedbackText(feedback: GalleryManagementFeedback): String = when (feedback) {
    is GalleryManagementFeedback.Completed -> stringResource(
        when (feedback.action) {
            GalleryBulkAction.Share -> R.string.gallery_share_feedback
            GalleryBulkAction.RemoveFromLibrary -> R.string.gallery_remove_feedback
            GalleryBulkAction.DeleteFromDevice -> R.string.gallery_device_delete_feedback
        },
        feedback.completedCount,
        feedback.failedCount,
    )
}

@Composable
private fun settingsFeedbackText(feedback: SettingsFeedback): String? = when (feedback) {
    SettingsFeedback.ProfileSaved -> stringResource(R.string.settings_feedback_profile_saved)
    SettingsFeedback.ProfileDeleted -> stringResource(R.string.settings_feedback_profile_deleted)
    SettingsFeedback.PromptSaved -> stringResource(R.string.settings_feedback_prompt_saved)
    SettingsFeedback.PromptDeleted -> stringResource(R.string.settings_feedback_prompt_deleted)
    SettingsFeedback.CertificateImported ->
        stringResource(R.string.settings_feedback_certificate_imported)
    is SettingsFeedback.Failed -> stringResource(
        when (feedback.reason) {
            SettingsFailure.InvalidName -> R.string.settings_error_invalid_name
            SettingsFailure.InvalidEndpoint -> R.string.settings_error_invalid_endpoint
            SettingsFailure.InvalidModel -> R.string.settings_error_invalid_model
            SettingsFailure.InvalidApiKey -> R.string.settings_error_invalid_api_key
            SettingsFailure.MissingCertificate -> R.string.settings_error_missing_certificate
            SettingsFailure.InvalidCertificate -> R.string.settings_error_invalid_certificate
            SettingsFailure.InvalidPin -> R.string.settings_error_invalid_pin
            SettingsFailure.UnsafeAcknowledgementRequired -> R.string.settings_error_unsafe_ack
            SettingsFailure.AuthorityChangeRequiresSecurityReset ->
                R.string.settings_error_authority_reset
            SettingsFailure.SecretUnavailable -> R.string.settings_error_secret_unavailable
            SettingsFailure.ProfileUnavailable -> R.string.settings_error_profile_unavailable
            SettingsFailure.InvalidPrompt -> R.string.settings_error_invalid_prompt
            SettingsFailure.StorageUnavailable -> R.string.settings_error_storage
            SettingsFailure.NotificationPermissionDenied ->
                R.string.settings_error_notification_permission
            SettingsFailure.BrowserUnavailable -> R.string.settings_error_browser_unavailable
        },
    )
}

private fun AppSection.labelResource(): Int = when (this) {
    AppSection.Generate -> R.string.tab_generate
    AppSection.Tasks -> R.string.tab_tasks
    AppSection.Gallery -> R.string.tab_gallery
    AppSection.Settings -> R.string.tab_settings
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
