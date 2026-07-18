package io.github.ayaseminami.gnbp.ui.generation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import io.github.ayaseminami.gnbp.generation.ReferencePreparer
import io.github.ayaseminami.gnbp.generation.TaskId
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    @After
    fun tearDown() {
        engine?.close()
    }

    @Test
    fun fakeProviderCompletesTheGenerateAndTasksWorkflow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = profile()
        val repository = InstrumentedTaskRepository()
        val fakeImage = GeneratedImage(byteArrayOf(1, 2, 3), "image/png")
        var accepted = false

        compose.setContent {
            val scope = rememberCoroutineScope()
            val createdEngine = remember {
                DefaultGenerationEngine(
                    taskRepository = repository,
                    providerFactory = GenerationProviderFactory {
                        OfflineFakeImageGenerationProvider(fakeImage)
                    },
                    generatedAssetStore = InstrumentedAssetStore(),
                    referencePreparer = ReferencePreparer { error("No references expected") },
                    profileLoader = { profile },
                    maxConcurrency = 1,
                    externalScope = scope,
                    idGenerator = { "fake-workflow-task" },
                ).also { engine = it }
            }
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
                tasks = tasks,
                references = emptyList(),
                failedReferenceCount = 0,
                onSelectProfile = { state = state.copy(selectedProfileId = it) },
                onPromptChange = { state = state.copy(prompt = it) },
                onBatchCountChange = { state = state.copy(batchCount = it) },
                onGeminiAspectRatioChange = { state = state.copy(geminiAspectRatio = it) },
                onGeminiImageSizeChange = { state = state.copy(geminiImageSize = it) },
                onGeminiTemperatureChange = { state = state.copy(geminiTemperature = it) },
                onOpenAiSizeChange = { state = state.copy(openAiSize = it) },
                onOpenAiQualityChange = { state = state.copy(openAiQuality = it) },
                onPickReferences = {},
                onRemoveReference = {},
                onSubmit = {
                    scope.launch {
                        val result = createdEngine.enqueue(
                            GenerationBatchRequest(
                                profileId = profile.id,
                                prompt = state.prompt,
                                parameters = GenerationParameters.Gemini("3:4", "2K", 0.9),
                            ),
                        )
                        accepted = result is EnqueueResult.Accepted
                    }
                },
                onCancelTask = {},
                onRetryTask = {},
                onOpenResult = {},
                onFeedbackShown = {},
            )
        }

        compose.onNodeWithText(context.getString(R.string.prompt_label)).performTextInput("lighthouse")
        compose.onNodeWithText(context.getString(R.string.enqueue_generation))
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.tab_tasks)).performClick()
        val completed = context.getString(R.string.task_status_succeeded)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(completed).fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(accepted)
        compose.onNodeWithText("lighthouse").assertExists()
    }
}

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
