package com.dreef3.weightlossapp.features.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.TrendWindow
import com.dreef3.weightlossapp.domain.model.TrendWindowType
import java.time.format.DateTimeFormatter

@Composable
fun TrendsScreenRoute(
    container: AppContainer,
    onOpenHistoricalChat: (Long) -> Unit,
    onOpenMealDebug: (Long) -> Unit,
) {
    val viewModel: TrendsViewModel = viewModel(factory = TrendsViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    TrendsScreen(
        state = state,
        onSelectWindow = viewModel::selectWindow,
        onOpenHistoricalChat = onOpenHistoricalChat,
        onOpenMealDebug = onOpenMealDebug,
        onRetryEntry = viewModel::retryEntry,
    )
}

@Composable
fun TrendsScreen(
    state: TrendsUiState,
    onSelectWindow: (TrendWindowType) -> Unit,
    onOpenHistoricalChat: (Long) -> Unit,
    onOpenMealDebug: (Long) -> Unit,
    onRetryEntry: (FoodEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Trends",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            TrendWindowToggle(
                selectedWindow = state.selectedWindow,
                onSelectWindow = onSelectWindow,
            )
        }
        state.window?.let { window ->
            item { TrendOverviewCard(window = window) }
            item { TrendChartCard(window = window, dailyStats = state.dailyStats) }
            item { TrendStatsRow(window = window) }
        }
    }
}

@Composable
private fun TrendOverviewCard(
    window: TrendWindow,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (window.windowType == TrendWindowType.Last7Days) "Last 7 days" else "Last 30 days",
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TrendMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Total eaten",
                    value = window.totalConsumedCalories.toString(),
                )
                TrendMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Avg left",
                    value = window.averageRemainingCalories.toInt().toString(),
                )
            }
            if (window.isPartial) {
                Text(
                    text = "Showing partial history for this window.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrendChartCard(
    window: TrendWindow,
    dailyStats: List<TrendDayStat>,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (window.windowType == TrendWindowType.Last7Days) "Daily calories, 7 days" else "Daily calories, 30 days",
                style = MaterialTheme.typography.titleLarge,
            )
            SimpleTrendBars(dailyStats = dailyStats)
        }
    }
}

@Composable
private fun SimpleTrendBars(
    dailyStats: List<TrendDayStat>,
) {
    if (dailyStats.isEmpty()) {
        Text(
            text = "Not enough history yet for a chart.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val maxValue = dailyStats.maxOf { maxOf(it.consumedCalories, it.budgetCalories) }.coerceAtLeast(1)
    val budgetColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val consumedColor = MaterialTheme.colorScheme.primary
    val labelFormatter = DateTimeFormatter.ofPattern("d")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            val count = dailyStats.size.coerceAtLeast(1)
            val step = size.width / count
            val budgetBarWidth = step * 0.72f
            val consumedBarWidth = step * 0.44f

            dailyStats.forEachIndexed { index, stat ->
                val centerX = step * index + step / 2f
                val budgetHeight = (stat.budgetCalories.toFloat() / maxValue) * size.height
                val consumedHeight = (stat.consumedCalories.toFloat() / maxValue) * size.height

                drawRoundRect(
                    color = budgetColor,
                    topLeft = Offset(centerX - budgetBarWidth / 2f, size.height - budgetHeight),
                    size = Size(budgetBarWidth, budgetHeight),
                    cornerRadius = CornerRadius(14f, 14f),
                )
                drawRoundRect(
                    color = consumedColor,
                    topLeft = Offset(centerX - consumedBarWidth / 2f, size.height - consumedHeight),
                    size = Size(consumedBarWidth, consumedHeight),
                    cornerRadius = CornerRadius(14f, 14f),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            dailyStats
                .let { if (it.size <= 7) it else listOf(it.first(), it[it.size / 4], it[it.size / 2], it[(it.size * 3) / 4], it.last()) }
                .forEach { stat ->
                    Text(
                        text = stat.date.format(labelFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendDot(label = "Budget", color = budgetColor)
            LegendDot(label = "Eaten", color = consumedColor)
        }
    }
}

@Composable
private fun LegendDot(
    label: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = Modifier.height(12.dp).padding(top = 2.dp)) {
            drawCircle(color = color, radius = 6f)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrendStatsRow(
    window: TrendWindow,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrendMetricCard(
            modifier = Modifier.weight(1f),
            label = "Avg eaten",
            value = window.averageConsumedCalories.toInt().toString(),
        )
        TrendMetricCard(
            modifier = Modifier.weight(1f),
            label = "Tracked days",
            value = window.daysIncluded.toString(),
        )
        TrendMetricCard(
            modifier = Modifier.weight(1f),
            label = "Budget total",
            value = window.totalBudgetCalories.toString(),
        )
    }
}

@Composable
private fun TrendMetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
