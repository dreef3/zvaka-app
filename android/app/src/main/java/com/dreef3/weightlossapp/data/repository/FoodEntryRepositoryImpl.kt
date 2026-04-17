package com.dreef3.weightlossapp.data.repository

import com.dreef3.weightlossapp.app.sync.DriveSyncTrigger
import com.dreef3.weightlossapp.app.sync.NoOpDriveSyncTrigger
import com.dreef3.weightlossapp.data.local.dao.FoodEntryDao
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.Instant

class FoodEntryRepositoryImpl(
    private val foodEntryDao: FoodEntryDao,
    private val driveSyncTrigger: DriveSyncTrigger = NoOpDriveSyncTrigger,
) : FoodEntryRepository {
    override fun observeEntriesFor(date: LocalDate): Flow<List<FoodEntry>> =
        foodEntryDao.observeForDate(date.toString()).map { items -> items.map { it.toDomain() } }

    override fun observeEntriesInRange(startDate: LocalDate, endDate: LocalDate): Flow<List<FoodEntry>> =
        foodEntryDao.observeInRange(startDate.toString(), endDate.toString())
            .map { items -> items.map { it.toDomain() } }

    override fun observeAllEntries(): Flow<List<FoodEntry>> =
        foodEntryDao.observeAll().map { items -> items.map { it.toDomain() } }

    override fun observeEntry(entryId: Long): Flow<FoodEntry?> =
        foodEntryDao.observeById(entryId).map { it?.toDomain() }

    override suspend fun getEntry(entryId: Long): FoodEntry? = foodEntryDao.getById(entryId)?.toDomain()

    override suspend fun getPendingModelImprovementUploads(): List<FoodEntry> =
        foodEntryDao.getPendingModelImprovementUploads().map { it.toDomain() }

    override suspend fun markModelImprovementUploaded(entryId: Long, uploadedAt: Instant) {
        foodEntryDao.markModelImprovementUploaded(entryId, uploadedAt.toEpochMilli())
        driveSyncTrigger.requestSync("food_entry:model_improvement_uploaded")
    }

    override suspend fun upsert(entry: FoodEntry): Long {
        val entryId = if (entry.id == 0L) {
            foodEntryDao.insert(entry.toEntity())
        } else {
            val updatedRows = foodEntryDao.update(entry.toEntity())
            if (updatedRows > 0) entry.id else foodEntryDao.insert(entry.toEntity())
        }
        driveSyncTrigger.requestSync("food_entry:upsert")
        return entryId
    }

    override suspend fun delete(entry: FoodEntry) {
        foodEntryDao.update(
            entry.copy(
                deletedAt = java.time.Instant.now(),
            ).toEntity(),
        )
        driveSyncTrigger.requestSync("food_entry:delete")
    }
}
