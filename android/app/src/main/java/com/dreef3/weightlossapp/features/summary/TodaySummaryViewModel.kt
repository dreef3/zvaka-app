package com.dreef3.weightlossapp.features.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.calculation.SummaryAggregator
import com.dreef3.weightlossapp.domain.model.DailySummary
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
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

data class TodaySummaryUiState(
    val summary: DailySummary? = null,
    val isEmpty: Boolean = true,
    val errorMessage: String? = null,
    val processingCount: Int = 0,
    val manualEntries: List<FoodEntry> = emptyList(),
    val lastActionMessage: String? = null,
)

class TodaySummaryViewModel(
    localDateProvider: LocalDateProvider,
    profileRepository: ProfileRepository,
    foodEntryRepository: FoodEntryRepository,
    summaryAggregator: SummaryAggregator,
    private val backgroundPhotoCaptureUseCase: BackgroundPhotoCaptureUseCase,
    private val saveManualCaloriesUseCase: SaveManualCaloriesUseCase,
) : ViewModel() {
    private val today = localDateProvider.today()

    val uiState: StateFlow<TodaySummaryUiState> = combine(
        profileRepository.observeBudgetPeriods(),
        foodEntryRepository.observeEntriesFor(today),
    ) { periods, entries ->
        val budget = periods
            .filter { it.effectiveFromDate <= today }
            .maxByOrNull { it.effectiveFromDate }
            ?.caloriesPerDay

        if (budget == null) {
            TodaySummaryUiState(
                errorMessage = "Finish onboarding to see today's calories.",
            )
        } else {
            val summary = summaryAggregator.buildSummary(
                date = today,
                budgetCalories = budget,
                entries = entries,
            )
            TodaySummaryUiState(
                summary = summary,
                isEmpty = summary.entryCount == 0,
                processingCount = entries.count { it.entryStatus == FoodEntryStatus.Processing && it.deletedAt == null },
                manualEntries = entries.filter { it.entryStatus == FoodEntryStatus.NeedsManual && it.deletedAt == null },
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
            summaryAggregator = container.summaryAggregator,
            backgroundPhotoCaptureUseCase = container.backgroundPhotoCaptureUseCase,
            saveManualCaloriesUseCase = container.saveManualCaloriesUseCase,
        ) as T
    }
}
