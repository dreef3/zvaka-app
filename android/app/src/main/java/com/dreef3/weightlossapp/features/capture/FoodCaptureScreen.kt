package com.dreef3.weightlossapp.features.capture

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import java.io.File

@Composable
fun FoodCaptureScreenRoute(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: FoodCaptureViewModel = viewModel(factory = FoodCaptureViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPhotoPath by remember { mutableStateOf<String?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = pendingPhotoPath
        if (success && path != null) {
            viewModel.analyzePhoto(path)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val file = container.photoStorage.createPhotoFile()
            pendingPhotoPath = file.absolutePath
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            takePictureLauncher.launch(uri)
        }
    }

    FoodCaptureScreen(
        state = state,
        onAnalyzePhoto = {
            val permissionGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {
                val file = container.photoStorage.createPhotoFile()
                pendingPhotoPath = file.absolutePath
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                takePictureLauncher.launch(uri)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onDownloadModel = viewModel::downloadModel,
        onUseSamplePhoto = {
            val file = container.photoStorage.createPhotoFile()
            runCatching {
                context.assets.open(SAMPLE_ASSET_NAME).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                pendingPhotoPath = file.absolutePath
                viewModel.analyzePhoto(file.absolutePath)
            }.onFailure { error ->
                Log.e("FoodCaptureScreen", "Failed to load sample photo", error)
            }
        },
        onAccepted = { viewModel.confirmDetection(true) },
        onRejected = { viewModel.confirmDetection(false) },
        onRetakeAcknowledged = viewModel::consumeRetakeRequest,
        onBack = onBack,
    )
}

@Composable
fun FoodCaptureScreen(
    state: CaptureUiState,
    onAnalyzePhoto: () -> Unit,
    onDownloadModel: () -> Unit,
    onUseSamplePhoto: () -> Unit,
    onAccepted: () -> Unit,
    onRejected: () -> Unit,
    onRetakeAcknowledged: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Log food",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Take one clear photo. High-confidence estimates save immediately. Uncertain ones ask you to confirm.",
            style = MaterialTheme.typography.bodyMedium,
        )

        CameraPreview(imagePath = state.imagePath)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Capture status",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        state.isLoading -> "Analyzing your photo..."
                        state.savedEntryId != null -> "Saved as entry #${state.savedEntryId}"
                        state.awaitingConfirmation -> "Needs confirmation"
                        state.shouldRetake -> "Retake requested"
                        state.imagePath != null -> "Photo captured"
                        else -> "Ready for your first photo"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.detectedFoodLabel != null) {
                    Text("Detected food: ${state.detectedFoodLabel}")
                }
                if (state.estimatedCalories != null) {
                    Text("Estimated calories: ${state.estimatedCalories}")
                }
                if (state.confidenceNotes != null) {
                    Text(state.confidenceNotes)
                }
                Text(
                    text = state.modelStatusMessage ?: "Model status unknown",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.isDownloadingModel && state.modelDownloadProgressPercent != null) {
                    Text(
                        text = "Progress: ${state.modelDownloadProgressPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        CaptureErrorState(message = state.errorMessage)
        if (state.awaitingConfirmation) {
            ConfidenceConfirmationDialog(
                detectedFoodLabel = state.detectedFoodLabel,
                onAccepted = onAccepted,
                onRejected = onRejected,
            )
        }

        if (state.shouldRetake) {
            OutlinedButton(onClick = onRetakeAcknowledged) {
                Text("Dismiss retake prompt")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onDownloadModel,
                enabled = !state.isDownloadingModel && !state.modelAvailable,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isDownloadingModel) "Downloading..." else "Download model")
            }
            OutlinedButton(
                onClick = onUseSamplePhoto,
                enabled = !state.isLoading && state.modelAvailable,
                modifier = Modifier.weight(1f),
            ) {
                Text("Use sample photo")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onAnalyzePhoto,
                enabled = !state.isLoading && state.modelAvailable,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (state.imagePath == null) {
                        if (state.isLoading) "Analyzing..." else "Take photo"
                    } else {
                        if (state.isLoading) "Analyzing..." else "Retake photo"
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private const val SAMPLE_ASSET_NAME = "sample_spaghetti.jpg"
