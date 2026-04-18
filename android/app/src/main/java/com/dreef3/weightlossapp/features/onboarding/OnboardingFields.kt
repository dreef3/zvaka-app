package com.dreef3.weightlossapp.features.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.Sex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFields(
    state: OnboardingFormState,
    onFirstNameChanged: (String) -> Unit,
    onAgeChanged: (String) -> Unit,
    onHeightChanged: (String) -> Unit,
    onWeightChanged: (String) -> Unit,
    onSexChanged: (Sex) -> Unit,
    onActivityLevelChanged: (ActivityLevel) -> Unit,
    healthConnectAvailable: Boolean = false,
    healthConnectNeedsProviderSetup: Boolean = false,
    onHealthConnectCaloriesChanged: ((Boolean) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var sexExpanded by remember { mutableStateOf(false) }
        var activityExpanded by remember { mutableStateOf(false) }

        OutlinedTextField(
            value = state.firstName,
            onValueChange = onFirstNameChanged,
            label = { Text("First name") },
        )
        OutlinedTextField(
            value = state.ageYears,
            onValueChange = onAgeChanged,
            label = { Text("Age") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = state.heightCm,
            onValueChange = onHeightChanged,
            label = { Text("Height (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = state.weightKg,
            onValueChange = onWeightChanged,
            label = { Text("Weight (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        ExposedDropdownMenuBox(
            expanded = sexExpanded,
            onExpandedChange = { sexExpanded = !sexExpanded },
        ) {
            OutlinedTextField(
                modifier = Modifier.menuAnchor(),
                value = state.sex.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Sex") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sexExpanded) },
            )
            ExposedDropdownMenu(
                expanded = sexExpanded,
                onDismissRequest = { sexExpanded = false },
            ) {
                Sex.entries.forEach { sex ->
                    DropdownMenuItem(
                        text = { Text(sex.name) },
                        onClick = {
                            onSexChanged(sex)
                            sexExpanded = false
                        },
                    )
                }
            }
        }
        ExposedDropdownMenuBox(
            expanded = activityExpanded,
            onExpandedChange = { activityExpanded = !activityExpanded },
        ) {
            OutlinedTextField(
                modifier = Modifier.menuAnchor(),
                value = state.activityLevel.toLabel(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Lifestyle") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = activityExpanded)
                },
            )
            ExposedDropdownMenu(
                expanded = activityExpanded,
                onDismissRequest = { activityExpanded = false },
            ) {
                ActivityLevel.entries.forEach { activityLevel ->
                    DropdownMenuItem(
                        text = { Text(activityLevel.toLabel()) },
                        onClick = {
                            onActivityLevelChanged(activityLevel)
                            activityExpanded = false
                        },
                    )
                }
            }
        }
        if (onHealthConnectCaloriesChanged != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Sync calories to Health Connect")
                    Text(
                        text = if (healthConnectAvailable) {
                            "Opt in to publish each saved meal's calories to Health Connect. You can change this later in Settings."
                        } else if (healthConnectNeedsProviderSetup) {
                            "Health Connect needs setup or an update before this app can request access."
                        } else {
                            "Health Connect is not available on this device."
                        },
                    )
                }
                Switch(
                    checked = state.healthConnectCaloriesEnabled,
                    enabled = healthConnectAvailable || healthConnectNeedsProviderSetup,
                    onCheckedChange = onHealthConnectCaloriesChanged,
                )
            }
        }
    }
}

private fun ActivityLevel.toLabel(): String = when (this) {
    ActivityLevel.Sedentary -> "Sedentary"
    ActivityLevel.Light -> "Light activity"
    ActivityLevel.Moderate -> "Moderate activity"
    ActivityLevel.Active -> "Active"
    ActivityLevel.VeryActive -> "Very active"
}
