package com.dreef3.weightlossapp.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.usecase.SaveUserProfileRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val form: OnboardingFormState = OnboardingFormState(),
    val errors: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val isCompleted: Boolean = false,
)

class OnboardingViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun updateForm(transform: (OnboardingFormState) -> OnboardingFormState) {
        _uiState.value = _uiState.value.copy(form = transform(_uiState.value.form))
    }

    fun submit() {
        val issues = OnboardingValidator.validate(_uiState.value.form)
        if (issues.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(errors = issues)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errors = emptyList())
            val form = _uiState.value.form
            container.saveUserProfileUseCase(
                SaveUserProfileRequest(
                    firstName = form.firstName,
                    sex = form.sex,
                    ageYears = form.ageYears.toInt(),
                    heightCm = form.heightCm.toInt(),
                    weightKg = form.weightKg.toDouble(),
                    activityLevel = form.activityLevel,
                ),
            )
            container.preferences.setCompletedOnboarding(true)
            _uiState.value = _uiState.value.copy(isSaving = false, isCompleted = true)
        }
    }
}
