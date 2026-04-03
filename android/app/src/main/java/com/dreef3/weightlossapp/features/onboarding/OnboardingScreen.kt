package com.dreef3.weightlossapp.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.Sex

@Composable
fun OnboardingScreenRoute(
    container: AppContainer,
    onCompleted: () -> Unit,
) {
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModelFactory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) onCompleted()
    }

    OnboardingScreen(
        state = state,
        onFirstNameChanged = { value -> vm.updateForm { it.copy(firstName = value) } },
        onAgeChanged = { value -> vm.updateForm { it.copy(ageYears = value) } },
        onHeightChanged = { value -> vm.updateForm { it.copy(heightCm = value) } },
        onWeightChanged = { value -> vm.updateForm { it.copy(weightKg = value) } },
        onSexChanged = { value -> vm.updateForm { it.copy(sex = value) } },
        onActivityLevelChanged = { value -> vm.updateForm { it.copy(activityLevel = value) } },
        onSubmit = vm::submit,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onFirstNameChanged: (String) -> Unit,
    onAgeChanged: (String) -> Unit,
    onHeightChanged: (String) -> Unit,
    onWeightChanged: (String) -> Unit,
    onSexChanged: (Sex) -> Unit,
    onActivityLevelChanged: (ActivityLevel) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up your calorie budget")
        OnboardingFields(
            state = state.form,
            onFirstNameChanged = onFirstNameChanged,
            onAgeChanged = onAgeChanged,
            onHeightChanged = onHeightChanged,
            onWeightChanged = onWeightChanged,
            onSexChanged = onSexChanged,
            onActivityLevelChanged = onActivityLevelChanged,
        )
        state.errors.forEach { error -> Text(error) }
        Button(onClick = onSubmit, enabled = !state.isSaving) {
            Text(if (state.isSaving) "Saving..." else "Continue")
        }
    }
}
