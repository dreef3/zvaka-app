package com.dreef3.weightlossapp.features.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.domain.usecase.SaveUserProfileRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Composable
fun ProfileEditScreen(
    container: AppContainer,
    onBack: () -> Unit = {},
    onResetToOnboarding: () -> Unit = {},
) {
    val profile by container.profileRepository.observeProfile().collectAsStateWithLifecycle(initialValue = null)
    val budgetPeriods by container.profileRepository.observeBudgetPeriods().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var form by remember { mutableStateOf(OnboardingFormState()) }
    var hasLoaded by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var isSaving by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    val currentBudget = budgetPeriods.maxByOrNull { it.effectiveFromDate }?.caloriesPerDay

    LaunchedEffect(profile) {
        if (!hasLoaded && profile != null) {
            form = OnboardingFormState(
                firstName = profile!!.firstName,
                ageYears = profile!!.ageYears.toString(),
                heightCm = profile!!.heightCm.toString(),
                weightKg = profile!!.weightKg.toString(),
                sex = profile!!.sex,
                activityLevel = profile!!.activityLevel,
            )
            hasLoaded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Profile",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Current daily budget",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentBudget?.toString() ?: "Not set",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Changes apply from today onward and do not rewrite history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OnboardingFields(
            state = form,
            onFirstNameChanged = { form = form.copy(firstName = it) },
            onAgeChanged = { form = form.copy(ageYears = it.filter(Char::isDigit)) },
            onHeightChanged = { form = form.copy(heightCm = it.filter(Char::isDigit)) },
            onWeightChanged = { value -> form = form.copy(weightKg = value.filter(Char::isDigit)) },
            onSexChanged = { form = form.copy(sex = it) },
            onActivityLevelChanged = { form = form.copy(activityLevel = it) },
        )
        if (errors.isNotEmpty()) {
            errors.forEach { issue ->
                Text(
                    text = issue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Button(
            onClick = {
                val issues = OnboardingValidator.validate(form)
                if (issues.isNotEmpty()) {
                    errors = issues
                    return@Button
                }
                scope.launch {
                    isSaving = true
                    errors = emptyList()
                    container.saveUserProfileUseCase(
                        SaveUserProfileRequest(
                            firstName = form.firstName,
                            sex = form.sex,
                            ageYears = form.ageYears.toInt(),
                            heightCm = form.heightCm.toInt(),
                            weightKg = form.weightKg.toInt().toDouble(),
                            activityLevel = form.activityLevel,
                        ),
                    )
                    isSaving = false
                }
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isSaving) "Saving..." else "Save profile")
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Calorie estimation currently uses the local Gemma model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isResetting = true
                            withContext(Dispatchers.IO) {
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.smolVlm.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.gemma.uniqueWorkName)
                                WorkManager.getInstance(container.appContext).cancelAllWork()
                                container.database.clearAllTables()
                                container.preferences.reset()
                                container.modelStorage.clearAll()
                                container.photoStorage.clearAll()
                            }
                            isResetting = false
                            onResetToOnboarding()
                        }
                    },
                    enabled = !isResetting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isResetting) "Resetting..." else "Reset app and restart onboarding")
                }
            }
        }
    }
}
