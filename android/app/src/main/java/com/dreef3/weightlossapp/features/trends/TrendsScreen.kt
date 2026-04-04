package com.dreef3.weightlossapp.features.trends

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.TrendWindow
import com.dreef3.weightlossapp.domain.model.TrendWindowType
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TrendsScreenRoute(
    container: AppContainer,
    onOpenHistoricalChat: (Long) -> Unit,
) {
    val viewModel: TrendsViewModel = viewModel(factory = TrendsViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    TrendsScreen(
        state = state,
        onSelectWindow = viewModel::selectWindow,
        onOpenHistoricalChat = onOpenHistoricalChat,
    )
}

@Composable
fun TrendsScreen(
    state: TrendsUiState,
    onSelectWindow: (TrendWindowType) -> Unit,
    onOpenHistoricalChat: (Long) -> Unit,
) {
    val groupedEntries = remember(state.historyItems) {
        state.historyItems.groupBy { it.date }
            .toSortedMap(compareByDescending { it })
            .entries
            .toList()
    }

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
        }
        if (state.historyItems.isNotEmpty()) {
            item {
                Text(
                    text = "Meals, photos, and coach chats",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            groupedEntries.forEach { (date, entries) ->
                item(key = "header-$date") {
                    Text(
                        text = date.toSectionLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(
                    entries,
                    key = {
                        when (it) {
                            is TrendsHistoryItem.Meal -> "meal-${it.entry.id}"
                            is TrendsHistoryItem.CoachSession -> "chat-${it.session.id}"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is TrendsHistoryItem.Meal -> HistoryEntryCard(entry = entry.entry)
                        is TrendsHistoryItem.CoachSession -> CoachHistoryCard(
                            session = entry.session,
                            onClick = { onOpenHistoricalChat(entry.session.id) },
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "No meals in this period yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CoachHistoryCard(
    session: CoachChatSession,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier.size(72.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(20.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Coach",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Coach conversation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = session.summary ?: "Open to read this conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

private fun LocalDate.toSectionLabel(): String {
    val today = LocalDate.now()
    return when (this) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> format(DateTimeFormatter.ofPattern("MMM d"))
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
                    label = "Consumed",
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
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun HistoryEntryCard(
    entry: FoodEntry,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = entry.imagePath) {
        value = withContext(Dispatchers.IO) {
            entry.imagePath.takeIf { File(it).exists() }?.let { path ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val sampleSize = calculateInSampleSize(
                    srcWidth = bounds.outWidth,
                    srcHeight = bounds.outHeight,
                    reqWidth = 144,
                    reqHeight = 144,
                )
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize },
                )
            }
        }
    }
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d") }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val loadedBitmap = bitmap
            if (loadedBitmap != null) {
                Image(
                    bitmap = loadedBitmap.asImageBitmap(),
                    contentDescription = "Meal photo",
                    modifier = Modifier.size(72.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Card(
                    modifier = Modifier.size(72.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(20.dp),
                ) {}
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.detectedFoodLabel ?: "Meal photo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = formatter.format(entry.entryDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.entryStatus == FoodEntryStatus.NeedsManual) {
                    Text(
                        text = "Needs manual calories",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = entry.finalCalories.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "kcal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun calculateInSampleSize(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    var inSampleSize = 1
    if (srcHeight > reqHeight || srcWidth > reqWidth) {
        var halfHeight = srcHeight / 2
        var halfWidth = srcWidth / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
            halfHeight = srcHeight / 2
            halfWidth = srcWidth / 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}
