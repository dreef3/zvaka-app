package com.dreef3.weightlossapp.features.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dreef3.weightlossapp.domain.model.DailySummary

@Composable
fun SummaryCards(
    summary: DailySummary,
    onOpenTrends: () -> Unit,
) {
    val isOverBudget = summary.remainingCalories < 0
    val progress = when {
        summary.budgetCalories <= 0 -> 0f
        summary.consumedCalories <= 0 -> 0f
        else -> (summary.consumedCalories.toFloat() / summary.budgetCalories.toFloat()).coerceIn(0f, 1f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenTrends),
        // Use a softer, non-blaming accent when over budget (primaryContainer) instead of error
        colors = CardDefaults.cardColors(
            containerColor = if (isOverBudget) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        shape = RoundedCornerShape(32.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalorieRing(
                progress = progress,
                remainingCalories = summary.remainingCalories,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    // neutral title to avoid repetition
                    text = "Today",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isOverBudget) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    // Show a clear, positive number with units and context when over budget
                    text = if (isOverBudget) {
                        "${kotlin.math.abs(summary.remainingCalories)} kcal over"
                    } else {
                        "${summary.remainingCalories} kcal left"
                    },
                    style = MaterialTheme.typography.displayMedium,
                    color = if (isOverBudget) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    // keep consumed summary neutral
                    text = "Consumed ${summary.consumedCalories} kcal",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isOverBudget) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun CalorieRing(
    progress: Float,
    remainingCalories: Int,
) {
    val isOverBudget = remainingCalories < 0
    // Use primary colors for a softer, non-blaming accent when over budget
    val trackColor = if (isOverBudget) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val progressColor = if (isOverBudget) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier.size(128.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 14.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isOverBudget) {
                    kotlin.math.abs(remainingCalories).toString()
                } else {
                    remainingCalories.toString()
                },
                style = MaterialTheme.typography.headlineMedium,
            color = if (isOverBudget) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
                text = if (isOverBudget) "kcal above target" else "kcal left",
                style = MaterialTheme.typography.labelMedium,
                color = if (isOverBudget) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
