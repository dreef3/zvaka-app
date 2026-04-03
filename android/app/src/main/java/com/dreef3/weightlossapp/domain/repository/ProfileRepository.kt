package com.dreef3.weightlossapp.domain.repository

import com.dreef3.weightlossapp.domain.model.DailyCalorieBudgetPeriod
import com.dreef3.weightlossapp.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface ProfileRepository {
    fun observeProfile(): Flow<UserProfile?>
    suspend fun getProfile(): UserProfile?
    suspend fun upsertProfile(profile: UserProfile)
    suspend fun addBudgetPeriod(period: DailyCalorieBudgetPeriod)
    fun observeBudgetPeriods(): Flow<List<DailyCalorieBudgetPeriod>>
    suspend fun findBudgetFor(date: LocalDate): DailyCalorieBudgetPeriod?
}
