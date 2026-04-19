package com.dreef3.weightlossapp.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.data.preferences.GemmaBackend
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtDietChatEngineInstrumentedSmokeTest {
    private val targetContext: Context
        get() = ApplicationProvider.getApplicationContext()

    private val container: AppContainer
        get() = AppContainer.instance

    @Before
    fun setUp() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_RUN_REAL_GEMMA_TESTS) == "true")

        AppContainer.initialize(targetContext)
        container.database.clearAllTables()
        container.preferences.reset()
        container.photoStorage.clearAll()
        container.modelStorage.modelDirectory.mkdirs()
        container.modelStorage.cleanupIncompleteModelFiles(ModelDescriptors.gemma)

        val modelPath = arguments.getString(ARG_GEMMA_MODEL_PATH)
            ?: container.modelStorage.defaultModelFile.absolutePath
        val modelFile = File(modelPath)
        require(modelFile.exists() && modelFile.length() > 0L) {
            "Gemma model is missing at ${modelFile.absolutePath}"
        }
        if (modelFile.absolutePath != container.modelStorage.defaultModelFile.absolutePath) {
            modelFile.copyTo(container.modelStorage.defaultModelFile, overwrite = true)
        }

        container.preferences.setGemmaBackend(GemmaBackend.CPU)
    }

    @Test
    fun logsNewTextMealWithRealModel() = runBlocking {
        val response = sendMessage(
            message = "I had one pancake, around 250 kcal",
            snapshot = emptySnapshot(),
        )

        assertTrue(response.isNotBlank())
        assertFalse(response.contains("Exception", ignoreCase = true))

        val today = LocalDate.now()
        val entries = container.foodEntryRepository.getEntriesInRange(today, today)
        assertEquals(1, entries.size)
        assertEquals(250, entries.single().finalCalories)
        assertTrue(entries.single().detectedFoodLabel.orEmpty().contains("pancake", ignoreCase = true))
    }

    @Test
    fun correctsSavedEntryWithRealModel() = runBlocking {
        val entry = savedEntry(
            id = 5L,
            description = "Pastry",
            calories = 300,
        )
        container.foodEntryRepository.upsert(entry)

        val response = sendMessage(
            message = "Correct entry id 5 to potato burek, 420 kcal.",
            snapshot = snapshotFor(listOf(entry)),
        )

        assertTrue(response.isNotBlank())
        val updatedEntry = container.foodEntryRepository.getEntry(5L)
        assertNotNull(updatedEntry)
        assertEquals(420, updatedEntry?.finalCalories)
        assertTrue(updatedEntry?.detectedFoodLabel.orEmpty().contains("potato burek", ignoreCase = true))
    }

    @Test
    fun reestimatesSavedPhotoBackedEntryWithRealModel() = runBlocking {
        val photoFile = File(container.photoStorage.createPhotoFile().absolutePath)
        photoFile.writeBytes(TINY_JPEG)
        val entry = savedEntry(
            id = 7L,
            description = "Unknown meal",
            calories = 99999,
            imagePath = photoFile.absolutePath,
        )
        container.foodEntryRepository.upsert(entry)

        val response = sendMessage(
            message = "Use the latest saved meal entry, rename it to risotto with pear and gorgonzola, and re-estimate it from the saved photo.",
            snapshot = snapshotFor(listOf(entry)),
        )

        assertTrue(response.isNotBlank())
        val updatedEntry = container.foodEntryRepository.getEntry(7L)
        assertNotNull(updatedEntry)
        assertTrue(updatedEntry?.detectedFoodLabel.orEmpty().contains("risotto", ignoreCase = true))
        assertTrue((updatedEntry?.finalCalories ?: 0) in 1..5000)
        assertTrue(updatedEntry?.finalCalories != 99999)
        assertNotNull(updatedEntry?.debugInteractionLog)
    }

    @Test
    fun doesNotCreateFutureDatedEntryWithRealModel() = runBlocking {
        val response = sendMessage(
            message = "Log burger and fries for 2099-01-01, 700 kcal.",
            snapshot = emptySnapshot(),
        )

        assertTrue(response.isNotBlank())
        val futureEntries = container.foodEntryRepository.getEntriesInRange(
            LocalDate.parse("2099-01-01"),
            LocalDate.parse("2099-01-01"),
        )
        assertTrue(futureEntries.isEmpty())
    }

    private suspend fun sendMessage(message: String, snapshot: DietChatSnapshot): String {
        val result = container.gemmaDietChatEngine.sendMessage(
            message = message,
            history = emptyList(),
            snapshot = snapshot,
        )
        return result.getOrElse { throwable ->
            throw AssertionError("Real-model chat failed", throwable)
        }
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

    private fun savedEntry(
        id: Long,
        description: String,
        calories: Int,
        imagePath: String = "",
    ) = FoodEntry(
        id = id,
        capturedAt = Instant.parse("2026-04-19T10:00:00Z"),
        entryDate = LocalDate.now(),
        imagePath = imagePath,
        estimatedCalories = calories,
        finalCalories = calories,
        confidenceState = ConfidenceState.High,
        detectedFoodLabel = description,
        confidenceNotes = null,
        confirmationStatus = ConfirmationStatus.NotRequired,
        source = FoodEntrySource.AiEstimate,
        entryStatus = FoodEntryStatus.Ready,
    )

    companion object {
        private const val ARG_RUN_REAL_GEMMA_TESTS = "runRealGemmaTests"
        private const val ARG_GEMMA_MODEL_PATH = "gemmaModelPath"
        private val TINY_JPEG = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxAQEBUQEBAVFRUVFRUVFRUVFRUVFRUVFRUWFhUVFRUYHSggGBolHRUVITEhJSkrLi4uFx8zODMsNygtLisBCgoKDg0OGhAQGi0mHyUtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAAEAAQMBEQACEQEDEQH/xAAXAAADAQAAAAAAAAAAAAAAAAAAAQID/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEAMQAAAB6A//xAAVEAEBAAAAAAAAAAAAAAAAAAAAAf/aAAgBAQABBQKf/8QAFBEBAAAAAAAAAAAAAAAAAAAAEP/aAAgBAwEBPwEf/8QAFBEBAAAAAAAAAAAAAAAAAAAAEP/aAAgBAgEBPwEf/8QAFBABAAAAAAAAAAAAAAAAAAAAEP/aAAgBAQAGPwJf/8QAFBABAAAAAAAAAAAAAAAAAAAAEP/aAAgBAQABPyFf/9k=",
        )
    }
}
