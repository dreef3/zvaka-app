package com.dreef3.weightlossapp.domain.usecase

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BackgroundPhotoCaptureUseCaseTest {
    @Test
    fun createsProcessingEntryAndSchedulesBackgroundWork() = runTest {
        val repository = FakeRepository()
        val scheduler = FakeScheduler()
        val useCase = BackgroundPhotoCaptureUseCase(
            repository = repository,
            scheduler = scheduler,
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
        )

        val capturedAt = Instant.parse("2026-04-03T10:00:00Z")
        val entryId = useCase.enqueue("/tmp/meal.jpg", capturedAt)

        assertEquals(1L, entryId)
        assertEquals(1, repository.savedEntries.size)
        assertEquals(FoodEntryStatus.Processing, repository.savedEntries.single().entryStatus)
        assertEquals("Processing photo in background.", repository.savedEntries.single().confidenceNotes)
        assertTrue(scheduler.enqueued)
        assertEquals(entryId, scheduler.entryId)
        assertEquals("/tmp/meal.jpg", scheduler.imagePath)
    }
}

private class FakeRepository : FoodEntryRepository {
    val savedEntries = mutableListOf<FoodEntry>()

    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> = MutableStateFlow(savedEntries)

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> = emptyFlow()

    override fun observeAllEntries(): Flow<List<FoodEntry>> = MutableStateFlow(savedEntries)

    override fun observeEntry(entryId: Long): Flow<FoodEntry?> = MutableStateFlow(savedEntries.firstOrNull { it.id == entryId })

    override suspend fun getEntriesInRange(startDate: LocalDate, endDate: LocalDate): List<FoodEntry> = savedEntries

    override suspend fun getEntry(entryId: Long): FoodEntry? = savedEntries.firstOrNull { it.id == entryId }

    override suspend fun getPendingModelImprovementUploads(): List<FoodEntry> = emptyList()

    override suspend fun markModelImprovementUploaded(entryId: Long, uploadedAt: Instant) = Unit

    override suspend fun upsert(entry: FoodEntry): Long {
        val id = if (entry.id == 0L) 1L else entry.id
        savedEntries.removeAll { it.id == id }
        savedEntries += entry.copy(id = id)
        return id
    }

    override suspend fun delete(entry: FoodEntry) = Unit
}

private class FakeScheduler : PhotoProcessingScheduler {
    var enqueued = false
    var entryId: Long = 0
    var imagePath: String? = null

    override fun enqueue(entryId: Long, imagePath: String, capturedAtEpochMs: Long) {
        enqueued = true
        this.entryId = entryId
        this.imagePath = imagePath
    }
}
