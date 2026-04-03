package com.dreef3.weightlossapp.data.repository

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
) : ProfileRepository {
    override fun observeProfile(): Flow<UserProfile?> = profileDao.observeProfile().map { it?.toDomain() }

    override suspend fun getProfile(): UserProfile? = profileDao.getProfile()?.toDomain()

    override suspend fun upsertProfile(profile: UserProfile) {
        profileDao.upsert(profile.toEntity())
    }

    override suspend fun addBudgetPeriod(period: DailyCalorieBudgetPeriod) {
        budgetDao.insert(period.toEntity())
    }

    override fun observeBudgetPeriods(): Flow<List<DailyCalorieBudgetPeriod>> =
        budgetDao.observeAll().map { items -> items.map { it.toDomain() } }

    override suspend fun findBudgetFor(date: LocalDate): DailyCalorieBudgetPeriod? =
        budgetDao.findForDate(date.toString())?.toDomain()
}
