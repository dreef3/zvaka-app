package com.dreef3.weightlossapp.features.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.calculation.TrendAggregator
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.TrendWindow
import com.dreef3.weightlossapp.domain.model.TrendWindowType
import com.dreef3.weightlossapp.domain.repository.CoachChatRepository
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.BackgroundPhotoCaptureUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface TrendsHistoryItem {
    val date: LocalDate

    data class Meal(
        val entry: FoodEntry,
    ) : TrendsHistoryItem {
        override val date: LocalDate = entry.entryDate
    }

    data class CoachSession(
        val session: CoachChatSession,
    ) : TrendsHistoryItem {
        override val date: LocalDate = LocalDate.parse(session.sessionDateIso)
    }
}

data class TrendsUiState(
    val selectedWindow: TrendWindowType = TrendWindowType.Last7Days,
    val window: TrendWindow? = null,
    val historyEntries: List<FoodEntry> = emptyList(),
    val historyItems: List<TrendsHistoryItem> = emptyList(),
    val dailyStats: List<TrendDayStat> = emptyList(),
)

data class TrendDayStat(
    val date: LocalDate,
    val consumedCalories: Int,
    val budgetCalories: Int,
)

class TrendsViewModel(
    localDateProvider: LocalDateProvider,
    profileRepository: ProfileRepository,
    foodEntryRepository: FoodEntryRepository,
    coachChatRepository: CoachChatRepository,
    trendAggregator: TrendAggregator,
    private val backgroundPhotoCaptureUseCase: BackgroundPhotoCaptureUseCase,
) : ViewModel() {
    private val today = localDateProvider.today()
    private val selectedWindow = MutableStateFlow(TrendWindowType.Last7Days)

    val uiState: StateFlow<TrendsUiState> = combine(
        selectedWindow,
        profileRepository.observeBudgetPeriods(),
        foodEntryRepository.observeEntriesInRange(today.minusDays(29), today),
        coachChatRepository.observeSessionsInRange(today.minusDays(29), today),
    ) { windowType, periods, entries, chatSessions ->
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

        val windowStart = when (windowType) {
            TrendWindowType.Last7Days -> today.minusDays(6)
            TrendWindowType.Last30Days -> today.minusDays(29)
        }
        val filteredEntries = entries
            .filter { entry ->
                entry.deletedAt == null &&
                    entry.entryDate >= windowStart &&
                    entry.entryDate <= today &&
                    entry.entryStatus != FoodEntryStatus.Processing
            }
            .sortedWith(compareByDescending<FoodEntry> { it.entryDate }.thenByDescending { it.capturedAt })
        val filteredSessions = chatSessions
            .filter { session ->
                val date = LocalDate.parse(session.sessionDateIso)
                date >= windowStart && date <= today
            }
        val dailyStats = generateSequence(windowStart) { current ->
            if (current >= today) null else current.plusDays(1)
        }.map { date ->
            TrendDayStat(
                date = date,
                consumedCalories = consumedByDate[date] ?: 0,
                budgetCalories = budgetsByDate[date] ?: 0,
            )
        }.toList()
        TrendsUiState(
            selectedWindow = windowType,
            window = trendAggregator.buildTrendWindow(
                type = windowType,
                endDate = today,
                dailyBudgets = budgetsByDate,
                consumedByDate = consumedByDate,
            ),
            historyEntries = filteredEntries,
            historyItems = buildList {
                addAll(filteredEntries.map(TrendsHistoryItem::Meal))
                addAll(filteredSessions.map(TrendsHistoryItem::CoachSession))
            }.sortedWith(
                compareByDescending<TrendsHistoryItem> { it.date }.thenByDescending {
                    when (it) {
                        is TrendsHistoryItem.Meal -> it.entry.capturedAt.toEpochMilli()
                        is TrendsHistoryItem.CoachSession -> it.session.updatedAtEpochMs
                    }
                },
            ),
            dailyStats = dailyStats,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TrendsUiState(),
    )

    fun selectWindow(windowType: TrendWindowType) {
        selectedWindow.update { windowType }
    }

    fun retryEntry(entry: FoodEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            backgroundPhotoCaptureUseCase.retry(entry)
        }
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
            coachChatRepository = container.coachChatRepository,
            trendAggregator = container.trendAggregator,
            backgroundPhotoCaptureUseCase = container.backgroundPhotoCaptureUseCase,
        ) as T
    }
}
