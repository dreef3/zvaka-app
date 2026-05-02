package com.dreef3.weightlossapp.data.repository

import com.dreef3.weightlossapp.app.sync.DriveSyncTrigger
import com.dreef3.weightlossapp.app.sync.NoOpDriveSyncTrigger
import com.dreef3.weightlossapp.app.widget.NoOpWidgetRefreshTrigger
import com.dreef3.weightlossapp.app.widget.WidgetRefreshTrigger
import com.dreef3.weightlossapp.data.local.dao.DailyCalorieBudgetPeriodDao
import com.dreef3.weightlossapp.data.local.dao.ProfileDao
import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.UserProfile
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class ProfileRepositoryImpl(
    private val profileDao: ProfileDao,
    private val budgetDao: DailyCalorieBudgetPeriodDao,
    private val driveSyncTrigger: DriveSyncTrigger = NoOpDriveSyncTrigger,
    private val widgetRefreshTrigger: WidgetRefreshTrigger = NoOpWidgetRefreshTrigger,
) : ProfileRepository {
    override fun observeProfile(): Flow<UserProfile?> = profileDao.observeProfile().map { it?.toDomain() }

    override suspend fun getProfile(): UserProfile? = profileDao.getProfile()?.toDomain()

    override suspend fun upsertProfile(profile: UserProfile) {
        profileDao.upsert(profile.toEntity())
        driveSyncTrigger.requestSync("profile:upsert")
        widgetRefreshTrigger.requestRefresh("profile:upsert")
    }

    override suspend fun addBudgetPeriod(period: DailyCalorieBudgetPeriod) {
        budgetDao.insert(period.toEntity())
        driveSyncTrigger.requestSync("profile:budget_period")
        widgetRefreshTrigger.requestRefresh("profile:budget_period")
    }

    override fun observeBudgetPeriods(): Flow<List<DailyCalorieBudgetPeriod>> =
        budgetDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun findBudgetFor(date: LocalDate): DailyCalorieBudgetPeriod? =
        budgetDao.findForDate(date.toString())?.toDomain()
}
