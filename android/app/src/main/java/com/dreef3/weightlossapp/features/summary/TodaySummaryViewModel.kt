package com.dreef3.weightlossapp.features.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.domain.calculation.SummaryAggregator
import com.dreef3.weightlossapp.domain.model.DailySummary
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.CoachChatRepository
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.repository.ProfileRepository
import com.dreef3.weightlossapp.domain.usecase.BackgroundPhotoCaptureUseCase
import com.dreef3.weightlossapp.domain.usecase.SaveManualCaloriesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

sealed interface TodayHistoryItem {
    val date: LocalDate

    data class Meal(
        val entry: FoodEntry,
    ) : TodayHistoryItem {
        override val date: LocalDate = entry.entryDate
    }

    data class CoachSession(
        val session: CoachChatSession,
    ) : TodayHistoryItem {
        override val date: LocalDate = LocalDate.parse(session.sessionDateIso)
    }
}

data class TodaySummaryUiState(
    val summary: DailySummary? = null,
    val isEmpty: Boolean = true,
    val errorMessage: String? = null,
    val processingCount: Int = 0,
    val manualEntries: List<FoodEntry> = emptyList(),
    val historyItems: List<TodayHistoryItem> = emptyList(),
    val lastActionMessage: String? = null,
)

class TodaySummaryViewModel(
    localDateProvider: LocalDateProvider,
    profileRepository: ProfileRepository,
    private val foodEntryRepository: FoodEntryRepository,
    coachChatRepository: CoachChatRepository,
    summaryAggregator: SummaryAggregator,
    private val backgroundPhotoCaptureUseCase: BackgroundPhotoCaptureUseCase,
    private val saveManualCaloriesUseCase: SaveManualCaloriesUseCase,
) : ViewModel() {
    private val today = localDateProvider.today()

    val uiState: StateFlow<TodaySummaryUiState> = combine(
        profileRepository.observeBudgetPeriods(),
        foodEntryRepository.observeEntriesInRange(today.minusDays(29), today),
        coachChatRepository.observeSessionsInRange(today.minusDays(29), today),
    ) { periods, entries, chatSessions ->
        val budget = periods
            .filter { it.effectiveFromDate <= today }
            .maxByOrNull { it.effectiveFromDate }
            ?.caloriesPerDay

        val todayEntries = entries.filter { it.entryDate == today }

        if (budget == null) {
            TodaySummaryUiState(
                errorMessage = "Finish onboarding to see today's calories.",
            )
        } else {
            val summary = summaryAggregator.buildSummary(
                date = today,
                budgetCalories = budget,
                entries = todayEntries,
            )
            TodaySummaryUiState(
                summary = summary,
                isEmpty = summary.entryCount == 0,
                processingCount = todayEntries.count { it.entryStatus == FoodEntryStatus.Processing && it.deletedAt == null },
                manualEntries = todayEntries.filter { it.entryStatus == FoodEntryStatus.NeedsManual && it.deletedAt == null },
                historyItems = buildList {
                    addAll(
                        entries
                            .filter {
                                it.deletedAt == null &&
                                    it.confirmationStatus != ConfirmationStatus.Rejected &&
                                    it.entryStatus != FoodEntryStatus.Processing
                            }
                            .sortedByDescending { it.capturedAt }
                            .map(TodayHistoryItem::Meal),
                    )
                    addAll(chatSessions.map(TodayHistoryItem::CoachSession))
                }.sortedWith(
                    compareByDescending<TodayHistoryItem> { it.date }.thenByDescending {
                        when (it) {
                            is TodayHistoryItem.Meal -> it.entry.capturedAt.toEpochMilli()
                            is TodayHistoryItem.CoachSession -> it.session.updatedAtEpochMs
                        }
                    },
                ),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TodaySummaryUiState(),
    )

    fun queueCapturedPhoto(imagePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            backgroundPhotoCaptureUseCase.enqueue(
                imagePath = imagePath,
                capturedAt = Instant.now(),
            )
        }
    }

    fun saveManualCalories(entry: FoodEntry, calories: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            saveManualCaloriesUseCase.save(entry, calories)
        }
    }

    fun retryEntry(entry: FoodEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            backgroundPhotoCaptureUseCase.retry(entry)
        }
    }

    fun deleteEntry(entry: FoodEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            foodEntryRepository.delete(entry)
        }
    }
}

class TodaySummaryViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TodaySummaryViewModel(
            localDateProvider = container.localDateProvider,
            profileRepository = container.profileRepository,
            foodEntryRepository = container.foodEntryRepository,
            coachChatRepository = container.coachChatRepository,
            summaryAggregator = container.summaryAggregator,
            backgroundPhotoCaptureUseCase = container.backgroundPhotoCaptureUseCase,
            saveManualCaloriesUseCase = container.saveManualCaloriesUseCase,
        ) as T
    }
}
