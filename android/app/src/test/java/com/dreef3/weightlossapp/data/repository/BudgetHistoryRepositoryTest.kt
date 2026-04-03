package com.dreef3.weightlossapp.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreef3.weightlossapp.data.local.AppDatabase
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class BudgetHistoryRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: ProfileRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ProfileRepositoryImpl(db.profileDao(), db.dailyCalorieBudgetPeriodDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun findsMostRecentBudgetPeriodWithoutRewritingHistory() = runTest {
        repository.addBudgetPeriod(
            DailyCalorieBudgetPeriod(
                profileId = 1,
                caloriesPerDay = 2000,
                formulaName = "mifflin-st-jeor",
                activityMultiplier = 1.2,
                effectiveFromDate = LocalDate.parse("2026-04-01"),
                createdAt = Instant.EPOCH,
            ),
        )
        repository.addBudgetPeriod(
            DailyCalorieBudgetPeriod(
                profileId = 1,
                caloriesPerDay = 1800,
                formulaName = "mifflin-st-jeor",
                activityMultiplier = 1.55,
                effectiveFromDate = LocalDate.parse("2026-04-03"),
                createdAt = Instant.EPOCH.plusSeconds(1),
            ),
        )

        assertEquals(2000, repository.findBudgetFor(LocalDate.parse("2026-04-02"))?.caloriesPerDay)
        assertEquals(1800, repository.findBudgetFor(LocalDate.parse("2026-04-03"))?.caloriesPerDay)
    }
}
