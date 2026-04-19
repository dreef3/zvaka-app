package com.dreef3.weightlossapp.app.health

import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import java.time.LocalDate

class HealthConnectBackfillService(
    private val foodEntryRepository: FoodEntryRepository,
    private val exporter: HealthConnectCaloriesPublisher,
    private val localDateProvider: LocalDateProvider,
) {
    suspend fun backfillRecentEntries(days: Long = 7) {
        if (days <= 0 || !exporter.hasWritePermission()) return
        val endDate = localDateProvider.today()
        val startDate = endDate.minusDays(days - 1)
        foodEntryRepository.getEntriesInRange(startDate, endDate)
            .forEach { exporter.upsertCalories(it) }
    }
}
