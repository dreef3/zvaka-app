package com.dreef3.weightlossapp.features.summary

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.chat.CoachChatSession
import com.dreef3.weightlossapp.domain.model.FoodEntry
import java.io.File
import android.graphics.BitmapFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TodaySummaryScreenRoute(
    container: AppContainer,
    onNavigateToTrends: () -> Unit,
    onOpenHistoricalChat: (Long) -> Unit,
    onOpenMealDebug: (Long) -> Unit,
) {
    val viewModel: TodaySummaryViewModel = viewModel(factory = TodaySummaryViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPhotoPath by remember { mutableStateOf<String?>(null) }
    var manualEntryTarget by remember { mutableStateOf<FoodEntry?>(null) }
    var deleteEntryTarget by remember { mutableStateOf<FoodEntry?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPhotoPath
        if (success && path != null) {
            viewModel.queueCapturedPhoto(path)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val file = container.photoStorage.createPhotoFile()
            pendingPhotoPath = file.absolutePath
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            takePictureLauncher.launch(uri)
        }
    }

    TodaySummaryScreen(
        state = state,
        onTakePhoto = {
            val permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (permissionGranted) {
                val file = container.photoStorage.createPhotoFile()
                pendingPhotoPath = file.absolutePath
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                takePictureLauncher.launch(uri)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onOpenTrends = onNavigateToTrends,
        onOpenHistoricalChat = onOpenHistoricalChat,
        onOpenMealDebug = onOpenMealDebug,
        onOpenManualEntry = { manualEntryTarget = it },
        onRetryEntry = viewModel::retryEntry,
        onDeleteEntry = { deleteEntryTarget = it },
    )

    if (manualEntryTarget != null) {
        ManualCaloriesDialog(
            entry = manualEntryTarget!!,
            onDismiss = { manualEntryTarget = null },
            onSave = { calories ->
                viewModel.saveManualCalories(manualEntryTarget!!, calories)
                manualEntryTarget = null
            },
        )
    }

    if (deleteEntryTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteEntryTarget = null },
            title = { Text("Delete meal entry?") },
            text = { Text("This will remove this meal from your history and calorie totals.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteEntry(deleteEntryTarget!!)
                        deleteEntryTarget = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteEntryTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun TodaySummaryScreen(
    state: TodaySummaryUiState,
    onTakePhoto: () -> Unit,
    onOpenTrends: () -> Unit,
    onOpenHistoricalChat: (Long) -> Unit,
    onOpenMealDebug: (Long) -> Unit,
    onOpenManualEntry: (FoodEntry) -> Unit,
    onRetryEntry: (FoodEntry) -> Unit,
    onDeleteEntry: (FoodEntry) -> Unit,
) {
    val groupedHistory = remember(state.historyItems) {
        state.historyItems.groupBy { it.date }
            .toSortedMap(compareByDescending { it })
            .entries
            .toList()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.summary?.let { summary ->
                SummaryCards(
                    summary = summary,
                    onOpenTrends = onOpenTrends,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                item {
                    state.summary?.let { summary ->
                        OverBudgetNotice(summary.remainingCalories < 0)
                    }
                }
                item {
                    state.errorMessage?.let { Text(it) }
                }
                if (state.processingCount > 0) {
                    item {
                        StatusCard(
                            title = "Processing in background",
                            body = "${state.processingCount} photo(s) are still being estimated. You can close the app and come back later.",
                        )
                    }
                }
                if (state.manualEntries.isNotEmpty()) {
                    item {
                        Text("Needs manual calories", style = MaterialTheme.typography.titleLarge)
                    }
                    items(state.manualEntries, key = { it.id }) { entry ->
                        ManualEntryCard(
                            entry = entry,
                            onOpenManualEntry = { onOpenManualEntry(entry) },
                            onRetryEntry = { onRetryEntry(entry) },
                            onDeleteEntry = { onDeleteEntry(entry) },
                        )
                    }
                }
                if (state.historyItems.isNotEmpty()) {
                    groupedHistory.forEach { (date, itemsForDate) ->
                        item(key = "today-history-$date") {
                            Text(
                                text = date.toSectionLabel(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(
                            itemsForDate,
                            key = {
                                when (it) {
                                    is TodayHistoryItem.Meal -> "today-meal-${it.entry.id}"
                                    is TodayHistoryItem.CoachSession -> "today-chat-${it.session.id}"
                                }
                            },
                        ) { historyItem ->
                            when (historyItem) {
                                is TodayHistoryItem.Meal -> HistoryEntryCard(
                                    entry = historyItem.entry,
                                    onClick = { onOpenMealDebug(historyItem.entry.id) },
                                    onRetryEntry = { onRetryEntry(historyItem.entry) },
                                    onDeleteEntry = { onDeleteEntry(historyItem.entry) },
                                )
                                is TodayHistoryItem.CoachSession -> CoachHistoryCard(
                                    session = historyItem.session,
                                    onClick = { onOpenHistoricalChat(historyItem.session.id) },
                                )
                            }
                        }
                    }
                }
                if (state.isEmpty && state.processingCount == 0 && state.manualEntries.isEmpty()) {
                    item {
                        SummaryEmptyState()
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onTakePhoto,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 12.dp),
            icon = {
                Icon(
                    imageVector = Icons.Filled.AddAPhoto,
                    contentDescription = null,
                )
            },
            text = { Text("Take food photo") },
        )
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

@Composable
private fun HistoryEntryCard(
    entry: FoodEntry,
    onClick: () -> Unit,
    onRetryEntry: () -> Unit,
    onDeleteEntry: () -> Unit,
) {
    val bitmap = remember(entry.imagePath) {
        entry.imagePath.takeIf { it.isNotBlank() && File(it).exists() }?.let(BitmapFactory::decodeFile)
    }
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d") }
    Card(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onDeleteEntry,
        ),
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
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Meal photo",
                    modifier = Modifier.size(72.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Card(
                    modifier = Modifier.size(72.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = entry.detectedFoodLabel?.take(1)?.uppercase() ?: "M",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.detectedFoodLabel ?: "Meal",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (entry.entryStatus == com.dreef3.weightlossapp.domain.model.FoodEntryStatus.NeedsManual) {
                        IconButton(
                            onClick = onRetryEntry,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Retry estimation",
                            )
                        }
                    }
                }
                Text(
                    text = formatter.format(entry.entryDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.entryStatus == com.dreef3.weightlossapp.domain.model.FoodEntryStatus.NeedsManual) {
                    Text(
                        text = "Needs manual calories",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = "${entry.finalCalories}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ManualEntryCard(
    entry: FoodEntry,
    onOpenManualEntry: () -> Unit,
    onRetryEntry: () -> Unit,
    onDeleteEntry: () -> Unit,
) {
    val bitmap = remember(entry.imagePath) {
        entry.imagePath.takeIf { File(it).exists() }?.let(BitmapFactory::decodeFile)
    }
    Card(
        modifier = Modifier.combinedClickable(
            onClick = onOpenManualEntry,
            onLongClick = onDeleteEntry,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Needs manual calories", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRetryEntry, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Retry estimation",
                    )
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Retained food photo",
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(entry.confidenceNotes ?: "This photo was kept. Enter calories manually.")
            Button(onClick = onOpenManualEntry, modifier = Modifier.fillMaxWidth()) {
                Text("Enter calories manually")
            }
        }
    }
}

@Composable
private fun ManualCaloriesDialog(
    entry: FoodEntry,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var caloriesText by remember(entry.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual calories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The photo was kept. Enter the calories for this meal manually.")
                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { value -> caloriesText = value.filter(Char::isDigit) },
                    singleLine = true,
                    label = { Text("Calories") },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    caloriesText.toIntOrNull()?.let(onSave)
                },
                enabled = caloriesText.toIntOrNull() != null,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
