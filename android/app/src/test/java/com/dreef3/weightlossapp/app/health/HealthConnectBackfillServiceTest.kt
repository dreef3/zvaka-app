package com.dreef3.weightlossapp.app.health

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.model.ConfidenceState
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthConnectBackfillServiceTest {
    @Test
    fun backfillRecentEntriesRepublishesLastSevenDays() = runTest {
        val repository = FakeFoodEntryRepository()
        val exporter = FakeHealthConnectCaloriesExporter(hasPermission = true)
        val dateProvider = LocalDateProvider(ZoneId.of("UTC"))
        val service = HealthConnectBackfillService(
            foodEntryRepository = repository,
            exporter = exporter,
            localDateProvider = dateProvider,
        )

        service.backfillRecentEntries()

        val endDate = dateProvider.today()
        assertEquals(endDate.minusDays(6), repository.requestedStartDate)
        assertEquals(endDate, repository.requestedEndDate)
        assertEquals(listOf(1L, 2L), exporter.upsertedIds)
    }

    @Test
    fun backfillSkipsWhenPermissionMissing() = runTest {
        val repository = FakeFoodEntryRepository()
        val exporter = FakeHealthConnectCaloriesExporter(hasPermission = false)
        val service = HealthConnectBackfillService(
            foodEntryRepository = repository,
            exporter = exporter,
            localDateProvider = LocalDateProvider(ZoneId.of("UTC")),
        )

        service.backfillRecentEntries()

        assertEquals(null, repository.requestedStartDate)
        assertEquals(emptyList<Long>(), exporter.upsertedIds)
    }
}

private class FakeFoodEntryRepository : FoodEntryRepository {
    var requestedStartDate: LocalDate? = null
    var requestedEndDate: LocalDate? = null

    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> = emptyFlow()

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> = emptyFlow()

    override fun observeAllEntries(): Flow<List<FoodEntry>> = emptyFlow()

    override fun observeEntry(entryId: Long): Flow<FoodEntry?> = emptyFlow()

    override suspend fun getEntriesInRange(startDate: LocalDate, endDate: LocalDate): List<FoodEntry> {
        requestedStartDate = startDate
        requestedEndDate = endDate
        return listOf(foodEntry(1L), foodEntry(2L))
    }

    override suspend fun getEntry(entryId: Long): FoodEntry? = null

    override suspend fun getPendingModelImprovementUploads(): List<FoodEntry> = emptyList()

    override suspend fun markModelImprovementUploaded(entryId: Long, uploadedAt: Instant) = Unit

    override suspend fun upsert(entry: FoodEntry): Long = entry.id

    override suspend fun delete(entry: FoodEntry) = Unit
}

private class FakeHealthConnectCaloriesExporter(
    private val hasPermission: Boolean,
) : HealthConnectCaloriesPublisher {
    val upsertedIds = mutableListOf<Long>()

    override suspend fun hasWritePermission(): Boolean = hasPermission

    override suspend fun upsertCalories(entry: FoodEntry) {
        upsertedIds += entry.id
    }
}

private fun foodEntry(id: Long): FoodEntry = FoodEntry(
    id = id,
    capturedAt = Instant.EPOCH,
    entryDate = LocalDate.of(1970, 1, 1),
    imagePath = "meal-$id.jpg",
    estimatedCalories = 100,
    finalCalories = 100,
    confidenceState = ConfidenceState.High,
    detectedFoodLabel = "Meal $id",
    confidenceNotes = null,
    confirmationStatus = ConfirmationStatus.Accepted,
    source = FoodEntrySource.AiEstimate,
    entryStatus = FoodEntryStatus.Ready,
)
