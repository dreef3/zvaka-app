package com.dreef3.weightlossapp.features.trends

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import java.io.File
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MealDebugUiState(
    val entry: FoodEntry? = null,
    val traceSegments: List<DebugTraceSegment> = emptyList(),
)

data class DebugTraceSegment(
    val label: String,
    val text: String,
)

class MealDebugViewModel(
    private val entryId: Long,
    private val foodEntryRepository: FoodEntryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MealDebugUiState())
    val uiState: StateFlow<MealDebugUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = foodEntryRepository.getEntry(entryId)
            _uiState.update {
                MealDebugUiState(
                    entry = entry,
                    traceSegments = entry?.debugInteractionLog.toTraceSegments(),
                )
            }
        }
    }
}

class MealDebugViewModelFactory(
    private val container: AppContainer,
    private val entryId: Long,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MealDebugViewModel(
            entryId = entryId,
            foodEntryRepository = container.foodEntryRepository,
        ) as T
    }
}

@Composable
fun MealDebugScreenRoute(
    container: AppContainer,
    entryId: Long,
) {
    val viewModel: MealDebugViewModel = viewModel(
        factory = MealDebugViewModelFactory(container, entryId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MealDebugScreen(state = state)
}

@Composable
fun MealDebugScreen(
    state: MealDebugUiState,
) {
    val formatter = DateTimeFormatter.ofPattern("MMM d")
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Model trace",
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        state.entry?.let { entry ->
            item {
                EntrySummaryCard(
                    entry = entry,
                    formattedDate = formatter.format(entry.entryDate),
                )
            }
        }

        if (state.traceSegments.isEmpty()) {
            item {
                Text(
                    text = "No debug trace was stored for this entry.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.traceSegments) { segment ->
                TraceSegmentCard(segment = segment)
            }
        }
    }
}

@Composable
private fun EntrySummaryCard(
    entry: FoodEntry,
    formattedDate: String,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = entry.imagePath) {
        value = withContext(Dispatchers.IO) {
            entry.imagePath.takeIf { File(it).exists() }?.let(BitmapFactory::decodeFile)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            bitmap?.let { loadedBitmap ->
                Image(
                    bitmap = loadedBitmap.asImageBitmap(),
                    contentDescription = "Meal photo",
                    modifier = Modifier.size(120.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(
                text = entry.detectedFoodLabel ?: "Meal photo",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "$formattedDate • ${entry.finalCalories} kcal",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.confidenceNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TraceSegmentCard(
    segment: DebugTraceSegment,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = segment.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun String?.toTraceSegments(): List<DebugTraceSegment> {
    if (this.isNullOrBlank()) return emptyList()

    val prompt = StringBuilder()
    val thought = StringBuilder()
    val response = StringBuilder()
    val tool = StringBuilder()
    val other = StringBuilder()
    var section = "other"

    lineSequence().forEach { rawLine ->
        when (rawLine.trim()) {
            "Prompt:" -> section = "prompt"
            "[thought]" -> section = "thought"
            "[text]" -> section = "response"
            "[tool]" -> section = "tool"
            else -> {
                val target = when (section) {
                    "prompt" -> prompt
                    "thought" -> thought
                    "response" -> response
                    "tool" -> tool
                    else -> other
                }
                target.appendLine(rawLine)
            }
        }
    }

    return buildList {
        prompt.toString().trim().takeIf { it.isNotEmpty() }?.let { add(DebugTraceSegment("Prompt", it)) }
        thought.toString().trim().takeIf { it.isNotEmpty() }?.let {
            add(DebugTraceSegment("Thought", it.normalizeThoughtText()))
        }
        response.toString().trim().takeIf { it.isNotEmpty() }?.let { add(DebugTraceSegment("Response", it)) }
        tool.toString().trim().takeIf { it.isNotEmpty() }?.let { add(DebugTraceSegment("Tool result", it)) }
        other.toString().trim().takeIf { it.isNotEmpty() }?.let { add(DebugTraceSegment("Trace", it)) }
    }
}

private fun String.normalizeThoughtText(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
