package com.dreef3.weightlossapp.features.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.dreef3.weightlossapp.domain.model.TrendWindowType

@Composable
fun TrendWindowToggle(
    selectedWindow: TrendWindowType,
    onSelectWindow: (TrendWindowType) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(
            selected = selectedWindow == TrendWindowType.Last7Days,
            onClick = { onSelectWindow(TrendWindowType.Last7Days) },
            label = { Text("7 days", style = MaterialTheme.typography.labelLarge) },
        )
        FilterChip(
            selected = selectedWindow == TrendWindowType.Last30Days,
            onClick = { onSelectWindow(TrendWindowType.Last30Days) },
            label = { Text("30 days", style = MaterialTheme.typography.labelLarge) },
        )
    }
}
