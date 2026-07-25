package io.github.ayaseminami.gnbp.ui.generation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.DefaultGenerationEngine
import io.github.ayaseminami.gnbp.generation.DirectReplacementCommit
import io.github.ayaseminami.gnbp.generation.EnqueueResult
import io.github.ayaseminami.gnbp.generation.GenerationBatchRequest
import io.github.ayaseminami.gnbp.generation.GenerationCompletionRepository
import io.github.ayaseminami.gnbp.generation.GeneratedAssetReference
import io.github.ayaseminami.gnbp.generation.GenerationProviderFactory
import io.github.ayaseminami.gnbp.generation.GenerationTask
import io.github.ayaseminami.gnbp.generation.GenerationTaskRepository
import io.github.ayaseminami.gnbp.generation.OfflineFakeImageGenerationProvider
import io.github.ayaseminami.gnbp.generation.NoOpGenerationResultJournal
import io.github.ayaseminami.gnbp.generation.ReferencePreparer
import io.github.ayaseminami.gnbp.generation.TaskId
import io.github.ayaseminami.gnbp.generation.TaskStatus
import io.github.ayaseminami.gnbp.media.AssetReadResult
import io.github.ayaseminami.gnbp.media.AssetRef
import io.github.ayaseminami.gnbp.media.AssetAccessResult
import io.github.ayaseminami.gnbp.media.AssetDeleteResult
import io.github.ayaseminami.gnbp.media.AssetSaveResult
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.GeneratedAssetStore
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSummary
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.persistence.settings.GalleryLayoutMode
import io.github.ayaseminami.gnbp.provider.ApiKey
import io.github.ayaseminami.gnbp.provider.GeneratedImage
import io.github.ayaseminami.gnbp.provider.GenerationParameters
import io.github.ayaseminami.gnbp.provider.transport.ProfileId
import io.github.ayaseminami.gnbp.provider.transport.ProviderEndpoint
import io.github.ayaseminami.gnbp.provider.transport.TransportBinding
import io.github.ayaseminami.gnbp.ui.settings.SettingsActions
import io.github.ayaseminami.gnbp.ui.settings.SettingsUiState
import io.github.ayaseminami.gnbp.ui.settings.ProfileEditorState
import io.github.ayaseminami.gnbp.ui.settings.ProfileTextField
import io.github.ayaseminami.gnbp.ui.settings.SETTINGS_ADD_PROFILE_TEST_TAG
import io.github.ayaseminami.gnbp.ui.settings.SETTINGS_SAVE_PROFILE_TEST_TAG
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResult
import io.github.ayaseminami.gnbp.persistence.result.GeneratedResultId
import io.github.ayaseminami.gnbp.ui.gallery.GalleryScreen
import io.github.ayaseminami.gnbp.ui.gallery.GalleryManagementState
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_DELETE_SELECTED_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_COMPACT_GRID_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_LAYOUT_COMPACT_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_LAYOUT_LIST_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_LARGE_GRID_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_LIST_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_MORE_ACTIONS_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_REMOVE_SELECTED_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_SELECTION_MODE_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_SELECTION_TEST_TAG_PREFIX
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_SELECT_ALL_TEST_TAG
import io.github.ayaseminami.gnbp.ui.gallery.GALLERY_SHARE_SELECTED_TEST_TAG
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationAppInstrumentationTest {
    @get:Rule
    val compose = createComposeRule()

    private var engine: DefaultGenerationEngine? = null
    private var workflowScope: CoroutineScope? = null

    @After
    fun tearDown() {
        engine?.close()
        workflowScope?.cancel()
    }

    @Test
    fun fakeProviderCompletesTheGenerateAndTasksWorkflow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = profile()
        val repository = InstrumentedTaskRepository()
        val fakeImage = GeneratedImage(byteArrayOf(1, 2, 3), "image/png")
        val enqueueResult = AtomicReference<EnqueueResult?>()
        val enqueueError = AtomicReference<Throwable?>()
        val submitInvoked = AtomicReference(false)
        val shareInvoked = AtomicBoolean(false)
        val reuseInvoked = AtomicBoolean(false)
        val generatedResults = MutableStateFlow<List<GeneratedResult>>(emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
            workflowScope = it
        }
        val createdEngine = DefaultGenerationEngine(
            taskRepository = repository,
            completionRepository = GenerationCompletionRepository { task ->
                repository.updateTask(task)
                val asset = (task.status as TaskStatus.Succeeded).asset
                generatedResults.value = listOf(
                    GeneratedResult(
                        id = GeneratedResultId.forTask(task.id),
                        sourceTaskId = task.id,
                        request = task.request,
                        asset = asset,
                        createdAtEpochMillis = task.finishedAtEpochMillis ?: task.createdAtEpochMillis,
                    ),
                )
            },
            providerFactory = GenerationProviderFactory {
                OfflineFakeImageGenerationProvider(fakeImage)
            },
            generatedAssetStore = InstrumentedAssetStore(),
            referencePreparer = ReferencePreparer { error("No references expected") },
            profileLoader = { profile },
            maxConcurrency = 1,
            externalScope = scope,
            resultJournal = NoOpGenerationResultJournal,
            idGenerator = { "fake-workflow-task" },
        ).also { engine = it }
        val submitRequest: (String) -> Unit = { prompt ->
            submitInvoked.set(true)
            scope.launch {
                try {
                    val result = createdEngine.enqueue(
                        GenerationBatchRequest(
                            profileId = profile.id,
                            prompt = prompt,
                            parameters = GenerationParameters.Gemini("3:4", "2K", 0.9),
                        ),
                    )
                    enqueueResult.set(result)
                } catch (error: Throwable) {
                    enqueueError.set(error)
                }
            }
        }

        compose.setContent {
            val tasks by createdEngine.observeTasks().collectAsState(initial = emptyList())
            val results by generatedResults.collectAsState()
            var state by remember {
                mutableStateOf(
                    GenerationUiState(
                        profiles = listOf(
                            ProfileSummary(
                                id = profile.id,
                                name = profile.name,
                                providerKind = profile.providerKind,
                                model = profile.model,
                                isUnsafe = false,
                                sortOrder = 0,
                            ),
                        ),
                        selectedProfileId = profile.id,
                        isLoading = false,
                    ),
                )
            }
            GenerationApp(
                state = state,
                settingsState = SettingsUiState(),
                taskManagementState = TaskManagementState(),
                galleryManagementState = GalleryManagementState(),
                settingsActions = noOpSettingsActions(),
                tasks = tasks,
                generatedResults = results,
                references = emptyList(),
                failedReferenceCount = 0,
                onSelectProfile = { state = state.copy(selectedProfileId = it) },
                onSelectPrompt = {},
                onPromptChange = { state = state.copy(prompt = it) },
                onBatchCountChange = { state = state.copy(batchCount = it) },
                onGeminiAspectRatioChange = { state = state.copy(geminiAspectRatio = it) },
                onGeminiImageSizeChange = { state = state.copy(geminiImageSize = it) },
                onGeminiTemperatureChange = { state = state.copy(geminiTemperature = it) },
                onOpenAiSizeChange = { state = state.copy(openAiSize = it) },
                onOpenAiQualityChange = { state = state.copy(openAiQuality = it) },
                onPickReferences = {},
                onRemoveReference = {},
                onSubmit = { submitRequest(state.prompt) },
                onCancelTasks = {},
                onDeleteTasks = {},
                onRetryTask = {},
                onOpenResult = {},
                onShareResult = { shareInvoked.set(true) },
                onShareResults = {},
                onReuseResult = { reuseInvoked.set(true) },
                onSetResultFavorite = { _, _ -> },
                onRemoveResultsFromLibrary = {},
                onDeleteResultsFromDevice = {},
                onGalleryLayoutModeChange = {},
                onFeedbackShown = {},
                onSettingsFeedbackShown = {},
                onTaskManagementFeedbackShown = {},
                onGalleryManagementFeedbackShown = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.prompt_label)).performTextInput("lighthouse")
        compose.onNodeWithTag(GENERATION_SUBMIT_TEST_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { submitInvoked.get() }
        compose.waitUntil(timeoutMillis = 10_000) {
            enqueueResult.get() != null || enqueueError.get() != null
        }
        assertTrue("enqueue failed: ${enqueueError.get()}", enqueueResult.get() is EnqueueResult.Accepted)
        compose.waitUntil(timeoutMillis = 10_000) { repository.hasTerminalTask() }
        assertTrue(repository.tasksSnapshot().single().status is TaskStatus.Succeeded)

        compose.onNodeWithText(context.getString(R.string.tab_tasks)).performClick()
        val completed = context.getString(R.string.task_status_succeeded)
        compose.onNodeWithText(completed).assertExists()
        compose.onNodeWithText("lighthouse").assertExists()

        compose.onNodeWithText(context.getString(R.string.tab_gallery)).performClick()
        compose.onNodeWithText("lighthouse").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.share_result)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { shareInvoked.get() }
        compose.onNodeWithContentDescription(context.getString(R.string.reuse_as_reference)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { reuseInvoked.get() }
        compose.onNodeWithText(context.getString(R.string.prompt_label)).assertExists()
    }

    @Test
    fun galleryReadsGeneratedResultsAndFiltersFavoritesWithoutTaskHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            var results by remember {
                mutableStateOf(
                    listOf(
                        generatedResult("favorite", "favorite prompt", favorite = true),
                        generatedResult("ordinary", "ordinary prompt", favorite = false),
                    ),
                )
            }
            GalleryScreen(
                results = results,
                layoutMode = GalleryLayoutMode.LargeGrid,
                isWorking = false,
                onLayoutModeChange = {},
                onOpenResult = {},
                onShareResult = {},
                onShareResults = {},
                onReuseResult = {},
                onSetFavorite = { id, favorite ->
                    results = results.map { result ->
                        if (result.id == id) result.copy(isFavorite = favorite) else result
                    }
                },
                onRemoveFromLibrary = {},
                onDeleteFromDevice = {},
            )
        }

        compose.onNodeWithText("favorite prompt").assertIsDisplayed()
        compose.onNodeWithText("ordinary prompt").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.gallery_favorites_only)).performClick()
        compose.onNodeWithText("favorite prompt").assertIsDisplayed()
        compose.onAllNodesWithText("ordinary prompt").assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.unfavorite_result)).performClick()
        compose.onNodeWithText(context.getString(R.string.gallery_favorites_empty)).assertIsDisplayed()
    }

    @Test
    fun galleryKeepsMissingExternalResultVisible() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            GalleryScreen(
                results = listOf(
                    generatedResult("missing", "recoverable prompt", favorite = false),
                ),
                layoutMode = GalleryLayoutMode.LargeGrid,
                isWorking = false,
                onLayoutModeChange = {},
                onOpenResult = {},
                onShareResult = {},
                onShareResults = {},
                onReuseResult = {},
                onSetFavorite = { _, _ -> },
                onRemoveFromLibrary = {},
                onDeleteFromDevice = {},
            )
        }

        compose.onNodeWithText("recoverable prompt").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(
            context.getString(R.string.gallery_thumbnail_unavailable),
        ).assertCountEquals(1)
    }

    @Test
    fun gallerySwitchesAllLayoutModesAndShowsSafeListMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            var layoutMode by remember { mutableStateOf(GalleryLayoutMode.LargeGrid) }
            GalleryScreen(
                results = listOf(
                    generatedResult("favorite-layout", "favorite layout prompt", favorite = true),
                    generatedResult("ordinary-layout", "ordinary layout prompt", favorite = false),
                ),
                layoutMode = layoutMode,
                isWorking = false,
                onLayoutModeChange = { selectedLayoutMode -> layoutMode = selectedLayoutMode },
                onOpenResult = {},
                onShareResult = {},
                onShareResults = {},
                onReuseResult = {},
                onSetFavorite = { _, _ -> },
                onRemoveFromLibrary = {},
                onDeleteFromDevice = {},
            )
        }

        compose.onNodeWithTag(GALLERY_LARGE_GRID_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GALLERY_LAYOUT_COMPACT_TEST_TAG).performClick()
        compose.onNodeWithTag(GALLERY_COMPACT_GRID_TEST_TAG).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(
            context.getString(R.string.gallery_thumbnail_unavailable),
        ).assertCountEquals(2)
        compose.onNodeWithContentDescription(context.getString(R.string.gallery_favorites_only)).performClick()
        compose.onNodeWithText("favorite layout prompt").assertIsDisplayed()
        compose.onAllNodesWithText("ordinary layout prompt").assertCountEquals(0)
        compose.onNodeWithTag(GALLERY_LAYOUT_LIST_TEST_TAG).performClick()
        compose.onNodeWithTag(GALLERY_LIST_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("Gemini / model").assertIsDisplayed()
        compose.onNodeWithText("favorite layout prompt").assertIsDisplayed()
        compose.onAllNodesWithText("ordinary layout prompt").assertCountEquals(0)
    }

    @Test
    fun gallerySelectionSharesAndRemovesOnlyResultsInTheCurrentFilter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val favorite = generatedResult("favorite", "favorite prompt", favorite = true)
        val ordinary = generatedResult("ordinary", "ordinary prompt", favorite = false)
        val shared = AtomicReference<Set<GeneratedResultId>>(emptySet())
        val removed = AtomicReference<Set<GeneratedResultId>>(emptySet())
        compose.setContent {
            GalleryScreen(
                results = listOf(favorite, ordinary),
                layoutMode = GalleryLayoutMode.LargeGrid,
                isWorking = false,
                onLayoutModeChange = {},
                onOpenResult = {},
                onShareResult = {},
                onShareResults = shared::set,
                onReuseResult = {},
                onSetFavorite = { _, _ -> },
                onRemoveFromLibrary = removed::set,
                onDeleteFromDevice = {},
            )
        }

        compose.onNodeWithContentDescription(context.getString(R.string.gallery_favorites_only)).performClick()
        compose.onNodeWithTag(GALLERY_SELECTION_MODE_TEST_TAG).performClick()
        compose.onNodeWithTag(GALLERY_SELECT_ALL_TEST_TAG).performClick()
        compose.onNodeWithTag(GALLERY_SHARE_SELECTED_TEST_TAG).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { shared.get().isNotEmpty() }
        assertEquals(setOf(favorite.id), shared.get())

        compose.onNodeWithTag(GALLERY_MORE_ACTIONS_TEST_TAG).performClick()
        compose.onNodeWithTag(GALLERY_REMOVE_SELECTED_TEST_TAG).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm_remove_results_title)).assertIsDisplayed()
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.selected_favorite_count, 1, 1),
        ).assertIsDisplayed()
        assertTrue(removed.get().isEmpty())
        compose.onNodeWithText(context.getString(R.string.confirm_remove_results)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { removed.get().isNotEmpty() }
        assertEquals(setOf(favorite.id), removed.get())
    }

    @Test
    fun galleryDeleteFromDeviceRequiresExplicitConfirmation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = generatedResult("device", "device prompt", favorite = false)
        val deleted = AtomicReference<Set<GeneratedResultId>>(emptySet())
        compose.setContent {
            GalleryScreen(
                results = listOf(result),
                layoutMode = GalleryLayoutMode.LargeGrid,
                isWorking = false,
                onLayoutModeChange = {},
                onOpenResult = {},
                onShareResult = {},
                onShareResults = {},
                onReuseResult = {},
                onSetFavorite = { _, _ -> },
                onRemoveFromLibrary = {},
                onDeleteFromDevice = deleted::set,
            )
        }

        compose.onNodeWithTag(GALLERY_SELECTION_MODE_TEST_TAG).performClick()
        compose.onNodeWithTag("$GALLERY_SELECTION_TEST_TAG_PREFIX${result.id.value}").performClick()
        compose.onNodeWithTag(GALLERY_MORE_ACTIONS_TEST_TAG).performClick()
        compose.onNodeWithTag(GALLERY_DELETE_SELECTED_TEST_TAG).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm_delete_results_device_title))
            .assertIsDisplayed()
        assertTrue(deleted.get().isEmpty())
        compose.onNodeWithText(context.getString(R.string.confirm_delete_results_device)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { deleted.get().isNotEmpty() }
        assertEquals(setOf(result.id), deleted.get())
    }

    @Test
    fun tasksSelectionSelectsVisibleTasksAndConfirmsBeforeDeleting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requestedDeletion = AtomicReference<Set<TaskId>>(emptySet())
        val tasks = taskSelectionFixture()
        compose.setContent {
            TasksScreen(
                tasks = tasks,
                isDeleting = false,
                onCancelTasks = {},
                onDeleteTasks = requestedDeletion::set,
                onRetryTask = {},
                onOpenResult = {},
            )
        }

        compose.onNodeWithTag(TASKS_SELECTION_MODE_TEST_TAG).performClick()
        compose.onNodeWithTag(TASKS_SELECT_ALL_TEST_TAG).performClick()
        compose.onNodeWithTag(TASKS_DELETE_SELECTED_TEST_TAG).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm_delete_tasks_title))
            .assertIsDisplayed()
        assertTrue(requestedDeletion.get().isEmpty())

        compose.onNodeWithText(context.getString(R.string.confirm_delete_tasks)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { requestedDeletion.get().isNotEmpty() }
        assertEquals(tasks.mapTo(mutableSetOf(), GenerationTask::id), requestedDeletion.get())
    }

    @Test
    fun clearFailedConfirmsAndDeletesOnlyFailedTasks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requestedDeletion = AtomicReference<Set<TaskId>>(emptySet())
        compose.setContent {
            TasksScreen(
                tasks = taskSelectionFixture(),
                isDeleting = false,
                onCancelTasks = {},
                onDeleteTasks = requestedDeletion::set,
                onRetryTask = {},
                onOpenResult = {},
            )
        }

        compose.onNodeWithTag(TASKS_CLEAR_FAILED_TEST_TAG).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm_clear_failed_title))
            .assertIsDisplayed()
        assertTrue(requestedDeletion.get().isEmpty())

        compose.onNodeWithText(context.getString(R.string.confirm_delete_tasks)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { requestedDeletion.get().isNotEmpty() }
        assertEquals(setOf(TaskId("failed")), requestedDeletion.get())
    }

    @Test
    fun tasksSelectionCanDeleteOneIndividuallySelectedTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val requestedDeletion = AtomicReference<Set<TaskId>>(emptySet())
        compose.setContent {
            TasksScreen(
                tasks = taskSelectionFixture(),
                isDeleting = false,
                onCancelTasks = {},
                onDeleteTasks = requestedDeletion::set,
                onRetryTask = {},
                onOpenResult = {},
            )
        }

        compose.onNodeWithTag(TASKS_SELECTION_MODE_TEST_TAG).performClick()
        compose.onNodeWithTag("${TASK_SELECTION_TEST_TAG_PREFIX}failed").performClick()
        compose.onNodeWithTag(TASKS_DELETE_SELECTED_TEST_TAG).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm_delete_tasks)).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { requestedDeletion.get().isNotEmpty() }

        assertEquals(setOf(TaskId("failed")), requestedDeletion.get())
    }

    @Test
    fun cancellingSelectedTasksUsesOnlyActiveTaskIds() {
        val requestedCancellation = AtomicReference<Set<TaskId>>(emptySet())
        compose.setContent {
            TasksScreen(
                tasks = taskSelectionFixture(),
                isDeleting = false,
                onCancelTasks = requestedCancellation::set,
                onDeleteTasks = {},
                onRetryTask = {},
                onOpenResult = {},
            )
        }

        compose.onNodeWithTag(TASKS_SELECTION_MODE_TEST_TAG).performClick()
        compose.onNodeWithTag(TASKS_SELECT_ALL_TEST_TAG).performClick()
        compose.onNodeWithTag(TASKS_CANCEL_SELECTED_TEST_TAG).performClick()
        compose.waitUntil(timeoutMillis = 2_000) { requestedCancellation.get().isNotEmpty() }

        assertEquals(
            setOf(TaskId("queued"), TaskId("running")),
            requestedCancellation.get(),
        )
    }

    @Test
    fun settingsProfileEditorSurvivesNavigationRestoreAndUsesRealFormActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val saved = AtomicBoolean(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var settingsState by remember { mutableStateOf(SettingsUiState()) }
            val actions = remember {
                noOpSettingsActions(
                    onNewProfile = {
                        settingsState = settingsState.copy(
                            profileEditor = ProfileEditorState(
                                id = ProfileId("ui-profile"),
                                isNew = true,
                            ),
                        )
                    },
                    onUpdateProfileText = { field, value ->
                        val editor = settingsState.profileEditor ?: return@noOpSettingsActions
                        settingsState = settingsState.copy(
                            profileEditor = when (field) {
                                ProfileTextField.Name -> editor.copy(name = value)
                                ProfileTextField.Endpoint -> editor.copy(endpoint = value)
                                ProfileTextField.Model -> editor.copy(model = value)
                                ProfileTextField.ApiKey -> editor.copy(apiKeyReplacement = value)
                                ProfileTextField.SpkiPins -> editor.copy(spkiPins = value)
                            },
                        )
                    },
                    onSaveProfile = { saved.set(true) },
                )
            }
            GenerationApp(
                state = GenerationUiState(isLoading = false),
                settingsState = settingsState,
                taskManagementState = TaskManagementState(),
                galleryManagementState = GalleryManagementState(),
                settingsActions = actions,
                tasks = emptyList(),
                generatedResults = emptyList(),
                references = emptyList(),
                failedReferenceCount = 0,
                onSelectProfile = {},
                onSelectPrompt = {},
                onPromptChange = {},
                onBatchCountChange = {},
                onGeminiAspectRatioChange = {},
                onGeminiImageSizeChange = {},
                onGeminiTemperatureChange = {},
                onOpenAiSizeChange = {},
                onOpenAiQualityChange = {},
                onPickReferences = {},
                onRemoveReference = {},
                onSubmit = {},
                onCancelTasks = {},
                onDeleteTasks = {},
                onRetryTask = {},
                onOpenResult = {},
                onShareResult = {},
                onShareResults = {},
                onReuseResult = {},
                onSetResultFavorite = { _, _ -> },
                onRemoveResultsFromLibrary = {},
                onDeleteResultsFromDevice = {},
                onGalleryLayoutModeChange = {},
                onFeedbackShown = {},
                onSettingsFeedbackShown = {},
                onTaskManagementFeedbackShown = {},
                onGalleryManagementFeedbackShown = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.tab_settings)).performClick()
        compose.onNodeWithText(context.getString(R.string.settings_behavior_title)).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.settings_behavior_title)).assertExists()

        compose.onNodeWithTag(SETTINGS_ADD_PROFILE_TEST_TAG).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.profile_name_label)).performTextInput("Relay")
        compose.onNodeWithText(context.getString(R.string.model_label)).performTextInput("image-model")
        compose.onNodeWithText(context.getString(R.string.api_key_label)).performTextInput("test-key")
        compose.onNodeWithTag(SETTINGS_SAVE_PROFILE_TEST_TAG).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 2_000) { saved.get() }
    }
}

private fun generatedResult(
    suffix: String,
    prompt: String,
    favorite: Boolean,
) = GeneratedResult(
    id = GeneratedResultId("result-$suffix"),
    sourceTaskId = TaskId("task-$suffix"),
    request = taskRequestSnapshot(prompt),
    asset = io.github.ayaseminami.gnbp.generation.GeneratedAssetReference(
        id = "asset-$suffix",
        location = "content://gnbp/$suffix",
        displayName = "$suffix.png",
        mimeType = "image/png",
        byteSize = 3,
    ),
    createdAtEpochMillis = 100L,
    isFavorite = favorite,
)

private fun taskRequestSnapshot(prompt: String) =
    io.github.ayaseminami.gnbp.generation.TaskRequestSnapshot(
        profileId = ProfileId("profile-one"),
        profileName = "Gemini",
        providerKind = io.github.ayaseminami.gnbp.generation.GenerationProviderKind.Gemini,
        model = "model",
        prompt = prompt,
        parameters = GenerationParameters.Gemini("3:4", "2K", 0.7),
        references = emptyList(),
    )

private fun taskSelectionFixture(): List<GenerationTask> = listOf(
    uiTask("succeeded", TaskStatus.Succeeded(generatedResult("task", "prompt", false).asset)),
    uiTask("failed", TaskStatus.Failed(io.github.ayaseminami.gnbp.generation.TaskFailureReason.Transport)),
    uiTask("queued", TaskStatus.Queued),
    uiTask("running", TaskStatus.Running),
    uiTask(
        "unknown",
        TaskStatus.OutcomeUnknown(
            io.github.ayaseminami.gnbp.generation.TaskOutcomeUnknownReason.ProviderResponseUnknown,
        ),
    ),
)

private fun uiTask(id: String, status: TaskStatus) = GenerationTask(
    id = TaskId(id),
    request = taskRequestSnapshot("prompt-$id"),
    status = status,
    createdAtEpochMillis = 100L,
    finishedAtEpochMillis = if (status == TaskStatus.Queued || status == TaskStatus.Running) null else 200L,
)

private fun noOpSettingsActions(
    onNewProfile: () -> Unit = {},
    onUpdateProfileText: (ProfileTextField, String) -> Unit = { _, _ -> },
    onSaveProfile: () -> Unit = {},
) = SettingsActions(
    onNewProfile = onNewProfile,
    onEditProfile = {},
    onDeleteProfile = {},
    onCancelProfileEditor = {},
    onUpdateProfileText = onUpdateProfileText,
    onUpdateProviderKind = {},
    onUpdateTransportChoice = {},
    onUpdateAllowHostnameMismatch = {},
    onUpdateAllowLan = {},
    onUpdateUnsafeAcknowledgement = {},
    onImportCertificate = {},
    onClearCertificates = {},
    onSaveProfile = onSaveProfile,
    onNewPrompt = {},
    onEditPrompt = {},
    onDeletePrompt = {},
    onUpdatePromptName = {},
    onUpdatePromptContent = {},
    onCancelPromptEditor = {},
    onSavePrompt = {},
    onUpdateMaxConcurrency = {},
    onUpdateShowPreview = {},
    onUpdateCompletionNotifications = {},
    onUpdateSoundNotification = {},
)

private class InstrumentedTaskRepository : GenerationTaskRepository {
    private val tasks = MutableStateFlow<List<GenerationTask>>(emptyList())
    private val replacementMutex = Mutex()

    override fun observeTasks(): Flow<List<GenerationTask>> = tasks

    override suspend fun loadTasks(): List<GenerationTask> = tasks.value

    override suspend fun findTask(id: TaskId): GenerationTask? =
        tasks.value.firstOrNull { it.id == id }

    override suspend fun commitDirectReplacement(task: GenerationTask): DirectReplacementCommit =
        replacementMutex.withLock {
            val sourceTaskId = requireNotNull(task.sourceTaskId)
            findDirectReplacement(sourceTaskId)?.let { existing ->
                return@withLock DirectReplacementCommit.Existing(existing.id)
            }
            insertTasks(listOf(task))
            DirectReplacementCommit.Inserted(task.id)
        }

    override suspend fun insertTasks(newTasks: List<GenerationTask>) {
        tasks.value = newTasks + tasks.value
    }

    override suspend fun updateTask(task: GenerationTask) {
        tasks.value = tasks.value.map { current -> if (current.id == task.id) task else current }
    }

    override suspend fun deleteTerminalTasks(taskIds: Set<TaskId>): Set<TaskId> {
        val deletable = tasks.value
            .filter { task ->
                task.id in taskIds && when (task.status) {
                    is TaskStatus.Succeeded,
                    is TaskStatus.Failed,
                    is TaskStatus.Cancelled,
                    -> true
                    TaskStatus.Queued,
                    TaskStatus.Running,
                    is TaskStatus.OutcomeUnknown,
                    -> false
                }
            }
            .mapTo(mutableSetOf(), GenerationTask::id)
        tasks.value = tasks.value.filterNot { task -> task.id in deletable }
        return deletable
    }

    fun hasTerminalTask(): Boolean = tasks.value.any { task ->
        task.status !is TaskStatus.Queued && task.status !is TaskStatus.Running
    }

    fun tasksSnapshot(): List<GenerationTask> = tasks.value
}

private class InstrumentedAssetStore : GeneratedAssetStore {
    override suspend fun save(
        image: GeneratedImage,
        metadata: GeneratedAssetMetadata,
    ): AssetSaveResult = AssetSaveResult.Saved(
        AssetRef(
            id = MediaAssetId("fake-result"),
            uri = Uri.parse("content://gnbp/fake-result"),
            displayName = "fake-result.png",
            mimeType = image.mimeType,
            byteSize = image.bytes.size.toLong(),
        ),
    )

    override suspend fun read(asset: AssetRef): AssetReadResult = error("Not used")

    override suspend fun checkReadable(asset: AssetRef): AssetAccessResult = error("Not used")

    override suspend fun delete(asset: AssetRef): AssetDeleteResult = error("Not used")
}

private fun profile(): ProviderProfile {
    val id = ProfileId("fake-profile")
    return ProviderProfile(
        id = id,
        name = "Fake profile",
        providerKind = ProviderKind.Gemini,
        binding = TransportBinding(
            profileId = id,
            endpoint = ProviderEndpoint.parse("https://example.invalid/"),
        ),
        apiKey = ApiKey("fake-key"),
        model = "fake-model",
        sortOrder = 0,
    )
}
