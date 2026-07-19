package io.github.ayaseminami.gnbp.ui.generation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayaseminami.gnbp.R
import io.github.ayaseminami.gnbp.generation.DefaultGenerationEngine
import io.github.ayaseminami.gnbp.generation.EnqueueResult
import io.github.ayaseminami.gnbp.generation.GenerationBatchRequest
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
import io.github.ayaseminami.gnbp.media.AssetSaveResult
import io.github.ayaseminami.gnbp.media.GeneratedAssetMetadata
import io.github.ayaseminami.gnbp.media.GeneratedAssetStore
import io.github.ayaseminami.gnbp.media.MediaAssetId
import io.github.ayaseminami.gnbp.persistence.profile.ProfileSummary
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
            workflowScope = it
        }
        val createdEngine = DefaultGenerationEngine(
            taskRepository = repository,
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
                settingsActions = noOpSettingsActions(),
                tasks = tasks,
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
                onCancelTask = {},
                onRetryTask = {},
                onOpenResult = {},
                onShareResult = { shareInvoked.set(true) },
                onReuseResult = { reuseInvoked.set(true) },
                onFeedbackShown = {},
                onSettingsFeedbackShown = {},
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
                settingsActions = actions,
                tasks = emptyList(),
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
                onCancelTask = {},
                onRetryTask = {},
                onOpenResult = {},
                onShareResult = {},
                onReuseResult = {},
                onFeedbackShown = {},
                onSettingsFeedbackShown = {},
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

    override fun observeTasks(): Flow<List<GenerationTask>> = tasks

    override suspend fun loadTasks(): List<GenerationTask> = tasks.value

    override suspend fun findTask(id: TaskId): GenerationTask? =
        tasks.value.firstOrNull { it.id == id }

    override suspend fun insertTasks(newTasks: List<GenerationTask>) {
        tasks.value = newTasks + tasks.value
    }

    override suspend fun updateTask(task: GenerationTask) {
        tasks.value = tasks.value.map { current -> if (current.id == task.id) task else current }
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

    override suspend fun delete(asset: AssetRef): Boolean = error("Not used")
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
