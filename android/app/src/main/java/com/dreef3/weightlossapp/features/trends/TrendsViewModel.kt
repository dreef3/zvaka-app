package com.dreef3.weightlossapp.features.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.calculation.TrendAggregator
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.TrendWindow
import com.dreef3.weightlossapp.domain.model.TrendWindowType
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

data class TrendsUiState(
    val selectedWindow: TrendWindowType = TrendWindowType.Last7Days,
    val window: TrendWindow? = null,
    val historyEntries: List<FoodEntry> = emptyList(),
)

class TrendsViewModel(
    localDateProvider: LocalDateProvider,
    profileRepository: ProfileRepository,
    foodEntryRepository: FoodEntryRepository,
    trendAggregator: TrendAggregator,
) : ViewModel() {
    private val today = localDateProvider.today()
    private val selectedWindow = MutableStateFlow(TrendWindowType.Last7Days)

    val uiState: StateFlow<TrendsUiState> = combine(
        selectedWindow,
        profileRepository.observeBudgetPeriods(),
        foodEntryRepository.observeEntriesInRange(today.minusDays(29), today),
    ) { windowType, periods, entries ->
        val budgetsByDate = buildMap<LocalDate, Int> {
            var cursor = today.minusDays(29)
            while (!cursor.isAfter(today)) {
                periods.filter { it.effectiveFromDate <= cursor }
                    .maxByOrNull { it.effectiveFromDate }
                    ?.let { put(cursor, it.caloriesPerDay) }
                cursor = cursor.plusDays(1)
            }
        }
        val consumedByDate = entries
            .filter { it.deletedAt == null && it.confirmationStatus != ConfirmationStatus.Rejected }
            .groupBy { it.entryDate }
            .mapValues { (_, dayEntries) -> dayEntries.sumOf { it.finalCalories } }

        TrendsUiState(
            selectedWindow = windowType,
            window = trendAggregator.buildTrendWindow(
                type = windowType,
                endDate = today,
                dailyBudgets = budgetsByDate,
                consumedByDate = consumedByDate,
            ),
            historyEntries = entries
                .filter { entry ->
                    entry.deletedAt == null &&
                        entry.entryDate >= when (windowType) {
                            TrendWindowType.Last7Days -> today.minusDays(6)
                            TrendWindowType.Last30Days -> today.minusDays(29)
                        } &&
                        entry.entryDate <= today &&
                        entry.entryStatus != FoodEntryStatus.Processing
                }
                .sortedWith(compareByDescending<FoodEntry> { it.entryDate }.thenByDescending { it.capturedAt }),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TrendsUiState(),
    )

    fun selectWindow(windowType: TrendWindowType) {
        selectedWindow.update { windowType }
    }
}

class TrendsViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TrendsViewModel(
            localDateProvider = container.localDateProvider,
            profileRepository = container.profileRepository,
            foodEntryRepository = container.foodEntryRepository,
            trendAggregator = container.trendAggregator,
        ) as T
    }
}
