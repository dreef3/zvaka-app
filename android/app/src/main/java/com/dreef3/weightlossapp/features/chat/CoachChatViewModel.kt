package com.dreef3.weightlossapp.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.chat.ChatRole
import com.dreef3.weightlossapp.chat.DietChatMessage
import com.dreef3.weightlossapp.chat.DietChatSnapshot
import com.dreef3.weightlossapp.chat.DietEntryContext
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CoachChatUiState(
    val messages: List<DietChatMessage> = listOf(
        DietChatMessage(
            role = ChatRole.Assistant,
            text = "Ask about your meals, calories, or how to improve today's diet.",
        ),
    ),
    val input: String = "",
    val isSending: Boolean = false,
    val showOverviewSuggestion: Boolean = true,
)

class CoachChatViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val snapshotState: StateFlow<DietChatSnapshot> = combine(
        container.profileRepository.observeBudgetPeriods(),
        container.foodEntryRepository.observeAllEntries(),
    ) { periods, entries ->
        val today = container.localDateProvider.today()
        val budget = periods
            .filter { it.effectiveFromDate <= today }
            .maxByOrNull { it.effectiveFromDate }
            ?.caloriesPerDay
        val todayEntries = entries.filter { entry ->
            entry.entryDate == today &&
                entry.deletedAt == null &&
                entry.confirmationStatus != ConfirmationStatus.Rejected &&
                entry.entryStatus == FoodEntryStatus.Ready
        }
        DietChatSnapshot(
            todayBudgetCalories = budget,
            todayConsumedCalories = todayEntries.sumOf { it.finalCalories },
            todayRemainingCalories = budget?.minus(todayEntries.sumOf { it.finalCalories }),
            entries = entries
                .filter { it.deletedAt == null && it.confirmationStatus != ConfirmationStatus.Rejected }
                .sortedByDescending { it.capturedAt }
                .map { entry ->
                    DietEntryContext(
                        entryId = entry.id,
                        dateIso = entry.entryDate.toString(),
                        description = entry.detectedFoodLabel,
                        finalCalories = entry.finalCalories,
                        estimatedCalories = entry.estimatedCalories,
                        needsManual = entry.entryStatus == FoodEntryStatus.NeedsManual,
                        source = entry.source.name,
                    )
                },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DietChatSnapshot(
            todayBudgetCalories = null,
            todayConsumedCalories = 0,
            todayRemainingCalories = null,
            entries = emptyList(),
        ),
    )

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(CoachChatUiState())
    val uiState: StateFlow<CoachChatUiState> = _uiState

    fun updateInput(value: String) {
        _uiState.value = _uiState.value.copy(input = value)
    }

    fun insertCorrectionExample() {
        _uiState.value = _uiState.value.copy(
            input = "That pastry entry was actually potato burek, around 420 kcal.",
        )
    }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isBlank() || _uiState.value.isSending) return
        sendMessage(
            userVisibleText = text,
            actualPrompt = text,
            clearInput = true,
        )
    }

    fun requestOverview() {
        if (_uiState.value.isSending) return
        val snapshot = snapshotState.value
        val todayIso = container.localDateProvider.today().toString()
        val hasTodayEntries = snapshot.entries.any { it.dateIso == todayIso }
        val actualPrompt = if (hasTodayEntries) {
            "Analyze today's tracked meals for weight loss and healthy eating habits. Start with a short overview of what was eaten, then give concise practical advice focused on calories, protein, satiety, meal balance, and one or two concrete next steps."
        } else {
            "Today's meals are empty. Analyze yesterday's tracked meals instead, if available, for weight loss and healthy eating habits. Start with a short overview of what was eaten, then give concise practical advice focused on calories, protein, satiety, meal balance, and one or two concrete next steps. If yesterday is also empty, say briefly that there is not enough recent meal data yet."
        }
        sendMessage(
            userVisibleText = "Give me overview for today",
            actualPrompt = actualPrompt,
            clearInput = false,
        )
    }

    private fun sendMessage(
        userVisibleText: String,
        actualPrompt: String,
        clearInput: Boolean,
    ) {
        val userMessage = DietChatMessage(role = ChatRole.User, text = userVisibleText)
        val history = _uiState.value.messages + userMessage
        _uiState.value = _uiState.value.copy(
            messages = history,
            input = if (clearInput) "" else _uiState.value.input,
            isSending = true,
            showOverviewSuggestion = false,
        )

        viewModelScope.launch(Dispatchers.IO) {
            val response = container.dietChatEngine.sendMessage(
                message = actualPrompt,
                history = history,
                snapshot = snapshotState.value,
            ).getOrElse {
                "I couldn't answer that yet. Try again in a moment."
            }
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + DietChatMessage(ChatRole.Assistant, response),
                isSending = false,
                showOverviewSuggestion = false,
            )
        }
    }
}

class CoachChatViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return CoachChatViewModel(container) as T
    }
}
