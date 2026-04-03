package com.dreef3.weightlossapp.features.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun ConfidenceConfirmationDialog(
    detectedFoodLabel: String?,
    onAccepted: () -> Unit,
    onRejected: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Confirm food") },
        text = { Text("Is this ${detectedFoodLabel ?: "food"}?") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccepted) { Text("Yes") }
                Button(onClick = onRejected) { Text("No") }
            }
        },
    )
}
