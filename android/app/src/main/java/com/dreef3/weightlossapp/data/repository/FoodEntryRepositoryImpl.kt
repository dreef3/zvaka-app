package com.dreef3.weightlossapp.data.repository

import android.util.Log
import com.dreef3.weightlossapp.app.health.HealthConnectCaloriesExporter
import com.dreef3.weightlossapp.app.sync.DriveSyncTrigger
import com.dreef3.weightlossapp.app.sync.NoOpDriveSyncTrigger
import com.dreef3.weightlossapp.app.widget.NoOpWidgetRefreshTrigger
import com.dreef3.weightlossapp.app.widget.WidgetRefreshTrigger
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import com.dreef3.weightlossapp.data.local.dao.FoodEntryDao
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.Instant

class FoodEntryRepositoryImpl(
    private val foodEntryDao: FoodEntryDao,
    private val driveSyncTrigger: DriveSyncTrigger = NoOpDriveSyncTrigger,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = NoOpWidgetRefreshTrigger,
    private val preferences: AppPreferences? = null,
    private val healthConnectCaloriesExporter: HealthConnectCaloriesExporter? = null,
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

    override suspend fun getEntriesInRange(startDate: LocalDate, endDate: LocalDate): List<FoodEntry> =
        foodEntryDao.getInRange(startDate.toString(), endDate.toString()).map { it.toDomain() }

    override suspend fun getEntry(entryId: Long): FoodEntry? = foodEntryDao.getById(entryId)?.toDomain()

    override suspend fun getPendingModelImprovementUploads(): List<FoodEntry> =
        foodEntryDao.getPendingModelImprovementUploads().map { it.toDomain() }

    override suspend fun markModelImprovementUploaded(entryId: Long, uploadedAt: Instant) {
        foodEntryDao.markModelImprovementUploaded(entryId, uploadedAt.toEpochMilli())
        requestDriveSync("food_entry:model_improvement_uploaded")
    }

    override suspend fun resetModelImprovementUploadsSince(cutoff: Instant): Int {
        val updatedCount = foodEntryDao.resetModelImprovementUploadsSince(cutoff.toEpochMilli())
        if (updatedCount > 0) {
            requestDriveSync("food_entry:model_improvement_reset")
        }
        return updatedCount
    }

    override suspend fun upsert(entry: FoodEntry): Long {
        val entryId = if (entry.id == 0L) {
            foodEntryDao.insert(entry.toEntity())
        } else {
            val updatedRows = foodEntryDao.update(entry.toEntity())
            if (updatedRows > 0) entry.id else foodEntryDao.insert(entry.toEntity())
        }
        runCatching { publishHealthConnectCopy(entry.copy(id = entryId)) }
            .onFailure { throwable ->
                Log.w(TAG, "Failed publishing Health Connect calories for entryId=$entryId", throwable)
            }
        requestDriveSync("food_entry:upsert")
        widgetRefreshTrigger.requestRefresh("food_entry:upsert")
        return entryId
    }

    override suspend fun delete(entry: FoodEntry) {
        runCatching { deleteHealthConnectCopy(entry.id) }
            .onFailure { throwable ->
                Log.w(TAG, "Failed deleting Health Connect calories for entryId=${entry.id}", throwable)
            }
        foodEntryDao.update(
            entry.copy(
                deletedAt = java.time.Instant.now(),
            ).toEntity(),
        )
        requestDriveSync("food_entry:delete")
        widgetRefreshTrigger.requestRefresh("food_entry:delete")
    }

    private fun requestDriveSync(reason: String) {
        runCatching { driveSyncTrigger.requestSync(reason) }
            .onFailure { throwable ->
                Log.w(TAG, "Failed requesting Drive sync for reason=$reason", throwable)
            }
    }

    private suspend fun publishHealthConnectCopy(entry: FoodEntry) {
        val exporter = healthConnectCaloriesExporter ?: return
        val enabled = preferences?.healthConnectCaloriesEnabled?.first() ?: false
        if (!enabled) {
            exporter.deleteCalories(entry.id)
            return
        }
        exporter.upsertCalories(entry)
    }

    private suspend fun deleteHealthConnectCopy(entryId: Long) {
        if (entryId == 0L) return
        healthConnectCaloriesExporter?.deleteCalories(entryId)
    }

    private companion object {
        const val TAG = "FoodEntryRepository"
    }
}
