package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.data.preferences.GemmaBackend
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.inference.LiteRtFoodEstimationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiteRtDietChatEngineRealModelTest {
    @Test
    fun logsNewTextMealWithRealModel() = runBlocking {
        val engine = createEngine(entries = emptyList())

        val response = engine.sendMessage(
            message = "I had one pancake, around 250 kcal",
            history = emptyList(),
            snapshot = emptySnapshot(),
        )

        if (response.isFailure) fail("Real-model chat failed: ${response.exceptionOrNull()?.stackTraceToString()}")
        val body = response.getOrThrow()
        assertTrue(body.isNotBlank())
        assertFalse(body.contains("Exception", ignoreCase = true))
    }

    @Test
    fun correctsSavedEntryWithRealModel() = runBlocking {
        val entry = FoodEntry(
            id = 5L,
            capturedAt = Instant.parse("2026-04-19T08:00:00Z"),
            entryDate = LocalDate.parse("2026-04-19"),
            imagePath = "",
            estimatedCalories = 300,
            finalCalories = 300,
            confidenceState = ConfidenceState.High,
            detectedFoodLabel = "Pastry",
            confidenceNotes = null,
            confirmationStatus = ConfirmationStatus.NotRequired,
            source = FoodEntrySource.AiEstimate,
            entryStatus = FoodEntryStatus.Ready,
        )
        val engine = createEngine(entries = listOf(entry))

        val response = engine.sendMessage(
            message = "Correct entry id 5 to potato burek, 420 kcal.",
            history = emptyList(),
            snapshot = snapshotFor(listOf(entry)),
        )

        if (response.isFailure) fail("Real-model chat failed: ${response.exceptionOrNull()?.stackTraceToString()}")
        val body = response.getOrThrow()
        assertTrue(body.isNotBlank())
        assertFalse(body.contains("Exception", ignoreCase = true))
    }

    @Test
    fun reestimatesSavedPhotoBackedEntryWithRealModel() = runBlocking {
        assumeRealModelEnabled()
        val photo = createTempImageFile()
        val entry = FoodEntry(
            id = 7L,
            capturedAt = Instant.parse("2026-04-19T10:00:00Z"),
            entryDate = LocalDate.parse("2026-04-19"),
            imagePath = photo.absolutePath,
            estimatedCalories = 1234,
            finalCalories = 1234,
            confidenceState = ConfidenceState.High,
            detectedFoodLabel = "Unknown meal",
            confidenceNotes = null,
            confirmationStatus = ConfirmationStatus.NotRequired,
            source = FoodEntrySource.AiEstimate,
            entryStatus = FoodEntryStatus.Ready,
        )
        val engine = createEngine(entries = listOf(entry))

        val response = engine.sendMessage(
            message = "Use the latest saved meal entry, rename it to risotto with pear and gorgonzola, and re-estimate it from the saved photo.",
            history = emptyList(),
            snapshot = snapshotFor(listOf(entry)),
        )

        if (response.isFailure) fail("Real-model chat failed: ${response.exceptionOrNull()?.stackTraceToString()}")
        val body = response.getOrThrow()
        assertTrue(body.isNotBlank())
        assertFalse(body.contains("Exception", ignoreCase = true))
    }

    @Test
    fun doesNotCreateFutureDatedEntryWithRealModel() = runBlocking {
        val engine = createEngine(entries = emptyList())

        val response = engine.sendMessage(
            message = "Log burger and fries for 2099-01-01, 700 kcal.",
            history = emptyList(),
            snapshot = emptySnapshot(),
        )

        if (response.isFailure) fail("Real-model chat failed: ${response.exceptionOrNull()?.stackTraceToString()}")
        val body = response.getOrThrow()
        assertTrue(body.isNotBlank())
        assertFalse(body.contains("Exception", ignoreCase = true))
    }

    private lateinit var repository: FakeFoodEntryRepository

    private fun createEngine(entries: List<FoodEntry>): LiteRtDietChatEngine {
        val modelPath = assumeRealModelEnabled()

        repository = FakeFoodEntryRepository(entries)
        return LiteRtDietChatEngine(
            modelFile = File(modelPath),
            correctionService = DietEntryCorrectionService(
                foodEntryRepository = repository,
                updateFoodEntryUseCase = com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase(repository),
                localDateProvider = LocalDateProvider(),
            ),
            inspectionService = DietEntryInspectionService(
                foodEntryRepository = repository,
                foodEstimationEngine = LiteRtFoodEstimationEngine(File(modelPath)),
            ),
            backendPreferenceProvider = { GemmaBackend.CPU },
        )
    }

    private fun assumeRealModelEnabled(): String {
        assumeTrue(System.getenv("RUN_REAL_GEMMA_TESTS") == "true")
        val modelPath = System.getenv("GEMMA_MODEL_PATH")
        assumeTrue(!modelPath.isNullOrBlank())
        val resolvedPath = checkNotNull(modelPath)
        assumeTrue(File(resolvedPath).exists())
        return resolvedPath
    }

    private fun emptySnapshot() = DietChatSnapshot(
        todayBudgetCalories = 2000,
        todayConsumedCalories = 0,
        todayRemainingCalories = 2000,
        entries = emptyList(),
    )

    private fun snapshotFor(entries: List<FoodEntry>) = DietChatSnapshot(
        todayBudgetCalories = 2000,
        todayConsumedCalories = entries.sumOf { it.finalCalories },
        todayRemainingCalories = 2000 - entries.sumOf { it.finalCalories },
        entries = entries.map { entry ->
            DietEntryContext(
                entryId = entry.id,
                dateIso = entry.entryDate.toString(),
                description = entry.detectedFoodLabel,
                finalCalories = entry.finalCalories,
                estimatedCalories = entry.estimatedCalories,
                needsManual = entry.entryStatus == FoodEntryStatus.NeedsManual,
                source = entry.source.name,
            )
        },
    )

    private fun createTempImageFile(): File {
        val file = kotlin.io.path.createTempFile(suffix = ".jpg").toFile()
        val tinyJpeg = java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxAQEBUQEBAVFRUVFRUVFRUVFRUVFRUVFRUWFhUVFRUYHSggGBolHRUVITEhJSkrLi4uFx8zODMsNygtLisBCgoKDg0OGhAQGi0mHyUtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAAEAAQMBEQACEQEDEQH/xAAXAAADAQAAAAAAAAAAAAAAAAAAAQID/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEAMQAAAB6A//xAAVEAEBAAAAAAAAAAAAAAAAAAAAAf/aAAgBAQABBQKf/8QAFBEBAAAAAAAAAAAAAAAAAAAAEP/aAAgBAwEBPwEf/8QAFBEBAAAAAAAAAAAAAAAAAAAAEP/aAAgBAgEBPwEf/8QAFBABAAAAAAAAAAAAAAAAAAAAEP/aAAgBAQAGPwJf/8QAFBABAAAAAAAAAAAAAAAAAAAAEP/aAAgBAQABPyFf/9k="
        )
        file.writeBytes(tinyJpeg)
        file.deleteOnExit()
        return file
    }
}

private class FakeFoodEntryRepository(
    initialEntries: List<FoodEntry>,
) : FoodEntryRepository {
    val savedEntries = initialEntries.toMutableList()
    private val flow = MutableStateFlow(initialEntries)

    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> = flow
    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> = flow
    override fun observeAllEntries(): Flow<List<FoodEntry>> = flow
    override fun observeEntry(entryId: Long): Flow<FoodEntry?> = MutableStateFlow(savedEntries.firstOrNull { it.id == entryId })
    override suspend fun getEntriesInRange(startDate: LocalDate, endDate: LocalDate): List<FoodEntry> = savedEntries
    override suspend fun getEntry(entryId: Long): FoodEntry? = savedEntries.firstOrNull { it.id == entryId }
    override suspend fun getPendingModelImprovementUploads(): List<FoodEntry> = emptyList()
    override suspend fun markModelImprovementUploaded(entryId: Long, uploadedAt: Instant) = Unit
    override suspend fun upsert(entry: FoodEntry): Long {
        val id = if (entry.id == 0L) (savedEntries.maxOfOrNull { it.id } ?: 0L) + 1L else entry.id
        savedEntries.removeAll { it.id == id }
        savedEntries += entry.copy(id = id)
        flow.value = savedEntries.toList()
        return id
    }
    override suspend fun delete(entry: FoodEntry) = Unit
}
