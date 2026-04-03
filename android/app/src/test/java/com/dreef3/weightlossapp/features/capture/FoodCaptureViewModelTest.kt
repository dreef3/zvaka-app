package com.dreef3.weightlossapp.features.capture

import com.dreef3.weightlossapp.app.media.ModelDownloader
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.usecase.ConfirmFoodEstimateUseCase
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase
import com.dreef3.weightlossapp.inference.FoodEstimationEngine
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import com.dreef3.weightlossapp.inference.FoodEstimationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
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
            modelStorage = realModelStorage(available = true),
            modelDownloader = FakeModelDownloader(),
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase(),
            updateFoodEntryUseCase = UpdateFoodEntryUseCase(repository),
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
            modelStorage = realModelStorage(available = true),
            modelDownloader = FakeModelDownloader(),
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase(),
            updateFoodEntryUseCase = UpdateFoodEntryUseCase(repository),
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
    fun downloadModelUpdatesAvailability() = runTest(dispatcher) {
        val modelStorage = realModelStorage(available = false)
        val downloader = FakeModelDownloader(modelStorage)
        val viewModel = FoodCaptureViewModel(
            foodEstimationEngine = FakeEngine(
                FoodEstimationResult(
                    estimatedCalories = 500,
                    confidenceState = ConfidenceState.High,
                    detectedFoodLabel = "spaghetti",
                    confidenceNotes = null,
                ),
            ),
            modelStorage = modelStorage,
            modelDownloader = downloader,
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
            confirmFoodEstimateUseCase = ConfirmFoodEstimateUseCase(),
            updateFoodEntryUseCase = UpdateFoodEntryUseCase(FakeFoodEntryRepository()),
            backgroundDispatcher = dispatcher,
        )

        viewModel.downloadModelFromLocalServer()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.modelAvailable)
        assertEquals("Model ready on device.", viewModel.uiState.value.modelStatusMessage)
    }

    private fun realModelStorage(available: Boolean): ModelStorage {
        val tempDir = kotlin.io.path.createTempDirectory().toFile()
        val storage = ModelStorage(modelDirectoryOverride = tempDir)
        storage.modelDirectory.mkdirs()
        if (available) {
            storage.defaultModelFile.writeText("model")
        } else {
            storage.defaultModelFile.delete()
        }
        return storage
    }
}

private class FakeEngine(
    private val result: FoodEstimationResult,
) : FoodEstimationEngine {
    override suspend fun estimate(request: FoodEstimationRequest): Result<FoodEstimationResult> = Result.success(result)
}

private class FakeFoodEntryRepository : FoodEntryRepository {
    val savedEntries = mutableListOf<FoodEntry>()

    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> = MutableStateFlow(savedEntries.toList())

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> = emptyFlow()

    override fun observeAllEntries(): Flow<List<FoodEntry>> = MutableStateFlow(savedEntries.toList())

    override suspend fun upsert(entry: FoodEntry): Long {
        val id = if (entry.id == 0L) (savedEntries.size + 1).toLong() else entry.id
        savedEntries.removeAll { it.id == id }
        savedEntries += entry.copy(id = id)
        return id
    }
}

private class FakeModelDownloader(
    private val targetStorage: ModelStorage = ModelStorage(modelDirectoryOverride = kotlin.io.path.createTempDirectory().toFile()),
) : ModelDownloader(targetStorage) {
    override suspend fun downloadFrom(url: String): Result<File> {
        targetStorage.modelDirectory.mkdirs()
        targetStorage.defaultModelFile.writeText("model")
        return Result.success(targetStorage.defaultModelFile)
    }
}
