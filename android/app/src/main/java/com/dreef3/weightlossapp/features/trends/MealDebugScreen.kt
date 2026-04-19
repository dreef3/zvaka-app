package com.dreef3.weightlossapp.features.trends

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.repository.FoodEntryRepository
import com.dreef3.weightlossapp.domain.usecase.BackgroundPhotoCaptureUseCase
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase
import java.io.File
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MealDebugUiState(
    val entry: FoodEntry? = null,
    val traceSections: List<TraceSection> = emptyList(),
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
)

data class TraceSection(
    val label: String,
    val content: AnnotatedString,
)

class MealDebugViewModel(
    private val entryId: Long,
    private val foodEntryRepository: FoodEntryRepository,
    private val backgroundPhotoCaptureUseCase: BackgroundPhotoCaptureUseCase,
    private val updateFoodEntryUseCase: UpdateFoodEntryUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MealDebugUiState())
    val uiState: StateFlow<MealDebugUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            foodEntryRepository.observeEntry(entryId).collectLatest { entry ->
                _uiState.update {
                    it.copy(
                        entry = entry,
                        traceSections = entry?.debugInteractionLog.toTraceSections(),
                    )
                }
            }
        }
    }

    fun retryEntry() {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = _uiState.value.entry ?: return@launch
            backgroundPhotoCaptureUseCase.retry(entry)
        }
    }

    fun deleteEntry() {
        viewModelScope.launch(Dispatchers.IO) {
            val entry = _uiState.value.entry ?: return@launch
            _uiState.update { it.copy(isDeleting = true) }
            foodEntryRepository.delete(entry)
            _uiState.update { it.copy(isDeleting = false, isDeleted = true) }
        }
    }

    fun saveEdits(entry: FoodEntry, description: String, calories: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            updateFoodEntryUseCase(
                entry.copy(
                    detectedFoodLabel = description.trim().ifBlank { entry.detectedFoodLabel ?: "Meal" },
                    estimatedCalories = entry.estimatedCalories.takeIf { it > 0 } ?: calories,
                    finalCalories = calories,
                    source = FoodEntrySource.UserCorrected,
                    confirmationStatus = ConfirmationStatus.NotRequired,
                    entryStatus = FoodEntryStatus.Ready,
                    confidenceNotes = "Edited manually.",
                ),
            )
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
            backgroundPhotoCaptureUseCase = container.backgroundPhotoCaptureUseCase,
            updateFoodEntryUseCase = container.updateFoodEntryUseCase,
        ) as T
    }
}

@Composable
fun MealDebugScreenRoute(
    container: AppContainer,
    entryId: Long,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: MealDebugViewModel = viewModel(
        factory = MealDebugViewModelFactory(container, entryId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.isDeleted) {
        onBack()
        return
    }

    MealDebugScreen(
        state = state,
        onRetry = {
            viewModel.retryEntry()
            onRetry()
        },
        onSaveEdits = viewModel::saveEdits,
        onDelete = { viewModel.deleteEntry() },
        onBack = onBack,
    )
}

@Composable
fun MealDebugScreen(
    state: MealDebugUiState,
    onRetry: () -> Unit,
    onSaveEdits: (FoodEntry, String, Int) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPhotoViewer by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete entry?") },
            text = { Text("This will remove this meal entry permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showPhotoViewer && state.entry?.imagePath != null) {
        FullSizePhotoViewer(
            imagePath = state.entry!!.imagePath,
            onDismiss = { showPhotoViewer = false },
        )
    }

    state.entry?.let { entry ->
        if (showEditDialog) {
            EditEntryDialog(
                entry = entry,
                onDismiss = { showEditDialog = false },
                onSave = { description, calories ->
                    showEditDialog = false
                    onSaveEdits(entry, description, calories)
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = state.entry?.detectedFoodLabel ?: "Meal details",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )
        }

        state.entry?.let { entry ->
            EntryDetailCard(
                entry = entry,
                onRetry = onRetry,
                onDelete = { showDeleteDialog = true },
                onEdit = { showEditDialog = true },
                onViewPhoto = { showPhotoViewer = true },
            )
        }

        if (state.traceSections.isEmpty()) {
            Text(
                text = "No debug trace was stored for this entry.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.traceSections.forEach { section ->
                TraceSectionCard(section = section)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun EntryDetailCard(
    entry: FoodEntry,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onViewPhoto: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = entry.imagePath) {
        value = withContext(Dispatchers.IO) {
            entry.imagePath.takeIf { File(it).exists() }?.let {
                BitmapFactory.Options().run {
                    inSampleSize = 2
                    BitmapFactory.decodeFile(it, this)
                }
            }
        }
    }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }
    val timeFormatter = remember { java.time.format.DateTimeFormatter.ofPattern("h:mm a").withZone(java.time.ZoneId.systemDefault()) }

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onViewPhoto),
                    contentScale = ContentScale.Crop,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.detectedFoodLabel ?: "Meal",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = buildString {
                            append(dateFormatter.format(entry.entryDate))
                            entry.capturedAt.let {
                                append(" at ")
                                append(timeFormatter.format(it))
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${entry.finalCalories} kcal",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (entry.entryStatus == FoodEntryStatus.NeedsManual) {
                Text(
                    text = "Needs manual calories",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            entry.confidenceNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedIconButton(
                    onClick = onRetry,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Retry estimation",
                        modifier = Modifier.size(18.dp),
                    )
                }
                OutlinedIconButton(
                    onClick = onEdit,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit entry",
                        modifier = Modifier.size(18.dp),
                    )
                }
                OutlinedIconButton(
                    onClick = onDelete,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete entry",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditEntryDialog(
    entry: FoodEntry,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var description by remember(entry.id) { mutableStateOf(entry.detectedFoodLabel.orEmpty()) }
    var caloriesText by remember(entry.id) { mutableStateOf(entry.finalCalories.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { value -> caloriesText = value.filter(Char::isDigit) },
                    label = { Text("Calories") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    caloriesText.toIntOrNull()?.let { calories ->
                        if (description.isNotBlank()) onSave(description, calories)
                    }
                },
                enabled = description.isNotBlank() && caloriesText.toIntOrNull() != null,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FullSizePhotoViewer(
    imagePath: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = imagePath) {
        value = withContext(Dispatchers.IO) {
            File(imagePath).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }
    var isSaving by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val maxPanX = constraints.maxWidth / 2f
                val maxPanY = constraints.maxHeight / 2f
                bitmap?.let { loadedBitmap ->
                    Image(
                        bitmap = loadedBitmap.asImageBitmap(),
                        contentDescription = "Full size meal photo",
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        offset = Offset(
                                            x = (offset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                            y = (offset.y + pan.y).coerceIn(-maxPanY, maxPanY),
                                        )
                                    } else {
                                        offset = Offset.Zero
                                    }
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (bitmap != null && !isSaving) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                saveBitmapToGallery(context, bitmap!!, imagePath)
                                isSaving = false
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(24.dp),
                            ),
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Save to gallery",
                            tint = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
                if (isSaving) {
                    Text(
                        "Saving...",
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(end = 8.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(24.dp),
                        ),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
    }
}

private suspend fun saveBitmapToGallery(context: Context, bitmap: android.graphics.Bitmap, sourcePath: String) {
    withContext(Dispatchers.IO) {
        val filename = "meal_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeightLossApp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
        }
    }
}

@Composable
private fun TraceSectionCard(
    section: TraceSection,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = section.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = section.content,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun String?.toTraceSections(): List<TraceSection> {
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

    val monospace = SpanStyle(fontFamily = FontFamily.Monospace)
    val bold = SpanStyle(fontWeight = FontWeight.Bold)

    return buildList {
        prompt.toString().trim().takeIf { it.isNotEmpty() }?.let {
            add(TraceSection("Prompt", renderMarkdownish(it)))
        }
        thought.toString().trim().takeIf { it.isNotEmpty() }?.let {
            add(TraceSection("Thought", renderMarkdownish(it.normalizeThoughtText())))
        }
        response.toString().trim().takeIf { it.isNotEmpty() }?.let {
            add(TraceSection("Response", renderMarkdownish(it)))
        }
        tool.toString().trim().takeIf { it.isNotEmpty() }?.let {
            add(TraceSection("Tool result", renderMarkdownish(it)))
        }
        other.toString().trim().takeIf { it.isNotEmpty() }?.let {
            add(TraceSection("Trace", renderMarkdownish(it)))
        }
    }
}

private fun renderMarkdownish(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.lines()
    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("# ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                    append(trimmed.removePrefix("# "))
                }
            }
            trimmed.startsWith("## ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)) {
                    append(trimmed.removePrefix("## "))
                }
            }
            trimmed.startsWith("**") && trimmed.endsWith("**") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(trimmed.removeSurrounding("**"))
                }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append("  ")
                append(trimmed.removePrefix("- ").removePrefix("* "))
            }
            trimmed.startsWith("```") -> {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(trimmed.removeSurrounding("```"))
                }
            }
            else -> append(line)
        }
        if (index < lines.lastIndex) append("\n")
    }
}

private fun String.normalizeThoughtText(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
