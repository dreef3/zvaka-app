package com.dreef3.weightlossapp.features.capture

import com.dreef3.weightlossapp.app.media.ModelDownloadController
import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.app.media.ModelDownloadState
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.usecase.ConfirmFoodEstimateUseCase
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase
import com.dreef3.weightlossapp.inference.CalorieEstimationModel
import com.dreef3.weightlossapp.inference.FoodEstimationEngine
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import com.dreef3.weightlossapp.inference.FoodEstimationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FoodCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun highConfidenceEstimateSavesImmediately() = runTest(dispatcher) {
        val repository = FakeFoodEntryRepository()
        val viewModel = FoodCaptureViewModel(
            foodEstimationEngine = FakeEngine(
                FoodEstimationResult(
                    estimatedCalories = 500,
                    confidenceState = ConfidenceState.High,
                    detectedFoodLabel = "salad",
                    confidenceNotes = null,
                ),
            ),
            preferences = unusedPreferences(),
            modelStorage = realModelStorage(available = true),
            modelDownloadRepository = FakeModelDownloadRepository(),
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase(),
            updateFoodEntryUseCase = UpdateFoodEntryUseCase(repository),
            calorieEstimationModelFlow = flowOf(CalorieEstimationModel.SmolVlm),
            readCalorieEstimationModel = { CalorieEstimationModel.SmolVlm },
            backgroundDispatcher = dispatcher,
        )

        viewModel.analyzePhoto("/tmp/photo.jpg")
        advanceUntilIdle()

        assertEquals(1, repository.savedEntries.size)
        assertEquals(ConfirmationStatus.NotRequired, repository.savedEntries.single().confirmationStatus)
        assertFalse(viewModel.uiState.value.awaitingConfirmation)
    }

    @Test
    fun nonHighConfidenceRequiresConfirmationAndRejectRequestsRetake() = runTest(dispatcher) {
        val repository = FakeFoodEntryRepository()
        val viewModel = FoodCaptureViewModel(
            foodEstimationEngine = FakeEngine(
                FoodEstimationResult(
                    estimatedCalories = 700,
                    confidenceState = ConfidenceState.NonHigh,
                    detectedFoodLabel = "mixed meal",
                    confidenceNotes = "uncertain",
                ),
            ),
            preferences = unusedPreferences(),
            modelStorage = realModelStorage(available = true),
            modelDownloadRepository = FakeModelDownloadRepository(),
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase(),
            updateFoodEntryUseCase = UpdateFoodEntryUseCase(repository),
            calorieEstimationModelFlow = flowOf(CalorieEstimationModel.SmolVlm),
            readCalorieEstimationModel = { CalorieEstimationModel.SmolVlm },
            backgroundDispatcher = dispatcher,
        )

        viewModel.analyzePhoto("/tmp/photo.jpg")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.awaitingConfirmation)
        assertEquals(0, repository.savedEntries.size)

        viewModel.confirmDetection(false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.shouldRetake)
        assertEquals(0, repository.savedEntries.size)
    }

    @Test
    fun initRequestsSelectedModelDownloadWhenMissing() = runTest(dispatcher) {
        val modelStorage = realModelStorage(available = false)
        val downloadRepository = FakeModelDownloadRepository()
        val viewModel = FoodCaptureViewModel(
            foodEstimationEngine = FakeEngine(
                FoodEstimationResult(
                    estimatedCalories = 500,
                    confidenceState = ConfidenceState.High,
                    detectedFoodLabel = "spaghetti",
                    confidenceNotes = null,
                ),
            ),
            preferences = unusedPreferences(),
            modelStorage = modelStorage,
            modelDownloadRepository = downloadRepository,
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase(),
            updateFoodEntryUseCase = UpdateFoodEntryUseCase(FakeFoodEntryRepository()),
            calorieEstimationModelFlow = flowOf(CalorieEstimationModel.SmolVlm),
            readCalorieEstimationModel = { CalorieEstimationModel.SmolVlm },
            backgroundDispatcher = dispatcher,
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.modelAvailable)
        assertTrue(viewModel.uiState.value.modelStatusMessage != null)
    }

    @Test
    fun analyzePhotoDoesNotRunInferenceWhenSelectedModelIsMissing() = runTest(dispatcher) {
        val downloadRepository = FakeModelDownloadRepository()
        val engine = CountingEngine()
        val viewModel = FoodCaptureViewModel(
            foodEstimationEngine = engine,
            preferences = unusedPreferences(),
            modelStorage = realModelStorage(available = false),
            modelDownloadRepository = downloadRepository,
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase(),
            updateFoodEntryUseCase = UpdateFoodEntryUseCase(FakeFoodEntryRepository()),
            calorieEstimationModelFlow = flowOf(CalorieEstimationModel.SmolVlm),
            readCalorieEstimationModel = { CalorieEstimationModel.SmolVlm },
            backgroundDispatcher = dispatcher,
        )
        advanceUntilIdle()

        viewModel.analyzePhoto("/tmp/photo.jpg")
        advanceUntilIdle()

        assertEquals(0, engine.estimateCalls)
        assertFalse(viewModel.uiState.value.modelAvailable)
        assertEquals(
            "Selected model is not ready yet. Wait for the download to finish or switch back to Gemma.",
            viewModel.uiState.value.errorMessage,
        )
        assertTrue(downloadRepository.enqueueCalls >= 2)
    }

    private fun realModelStorage(available: Boolean): ModelStorage {
        val tempDir = kotlin.io.path.createTempDirectory().toFile()
        val storage = ModelStorage(modelDirectoryOverride = tempDir)
        storage.modelDirectory.mkdirs()
        if (available) {
            storage.fileFor(ModelDescriptors.gemma).writeText("model")
            storage.fileFor(ModelDescriptors.smolVlm).writeText("model")
            storage.fileFor(ModelDescriptors.smolVlmMmproj).writeText("model")
        } else {
            storage.fileFor(ModelDescriptors.gemma).delete()
            storage.fileFor(ModelDescriptors.smolVlm).delete()
            storage.fileFor(ModelDescriptors.smolVlmMmproj).delete()
        }
        return storage
    }

    private fun unusedPreferences(): AppPreferences = AppPreferences(androidx.test.core.app.ApplicationProvider.getApplicationContext())
}

private class FakeEngine(
    private val result: FoodEstimationResult,
) : FoodEstimationEngine {
    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> = Result.success(result)
}

private class CountingEngine : FoodEstimationEngine {
    var estimateCalls = 0

    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> {
        estimateCalls += 1
        error("estimate should not be called when the selected model is missing")
    }
}

private class FakeFoodEntryRepository : FoodEntryRepository {
    val savedEntries = mutableListOf<FoodEntry>()

    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> = MutableStateFlow(savedEntries.toList())

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> = emptyFlow()

    override fun observeAllEntries(): Flow<List<FoodEntry>> = MutableStateFlow(savedEntries.toList())

    override suspend fun getEntry(entryId: Long): FoodEntry? = savedEntries.firstOrNull { it.id == entryId }

    override suspend fun upsert(entry: FoodEntry): Long {
        val id = if (entry.id == 0L) (savedEntries.size + 1).toLong() else entry.id
        savedEntries.removeAll { it.id == id }
        savedEntries += entry.copy(id = id)
        return id
    }

    override suspend fun delete(entry: FoodEntry) = Unit
}

private class FakeModelDownloadRepository(
    private val onEnqueue: () -> Unit = {},
) : ModelDownloadController {
    private val state = MutableStateFlow(ModelDownloadState())
    var enqueueCalls = 0
    var lastRequestedModel: ModelDescriptor? = null

    override fun enqueueIfNeeded(model: ModelDescriptor) {
        enqueueCalls += 1
        lastRequestedModel = model
        onEnqueue()
    }

    override fun observeState(model: ModelDescriptor) = state

    fun updateState(value: ModelDownloadState) {
        state.value = value
    }
}
