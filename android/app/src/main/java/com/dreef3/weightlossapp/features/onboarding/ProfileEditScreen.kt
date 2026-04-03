package com.dreef3.weightlossapp.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dreef3.weightlossapp.app.di.AppContainer

@Composable
fun ProfileEditScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Profile settings")
        Text("Profile editing will reuse the onboarding form and create a new budget period effective today.")
        Button(onClick = onBack) {
            Text("Back")
        }
    }
}
