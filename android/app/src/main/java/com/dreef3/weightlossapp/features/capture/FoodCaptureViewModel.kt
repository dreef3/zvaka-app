package com.dreef3.weightlossapp.features.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDownloader
import com.dreef3.weightlossapp.app.media.ModelStorage
import com.dreef3.weightlossapp.app.time.LocalDateProvider
import com.dreef3.weightlossapp.domain.model.ConfirmationStatus
import com.dreef3.weightlossapp.domain.model.FoodEntry
import com.dreef3.weightlossapp.domain.model.FoodEntryStatus
import com.dreef3.weightlossapp.domain.model.FoodEntrySource
import com.dreef3.weightlossapp.domain.usecase.ConfirmFoodEstimateUseCase
import com.dreef3.weightlossapp.domain.usecase.UpdateFoodEntryUseCase
import com.dreef3.weightlossapp.inference.FoodEstimationEngine
import com.dreef3.weightlossapp.inference.FoodEstimationException
import com.dreef3.weightlossapp.inference.FoodEstimationRequest
import com.dreef3.weightlossapp.inference.FoodEstimationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

class FoodCaptureViewModel(
    private val foodEstimationEngine: FoodEstimationEngine,
    private val modelStorage: ModelStorage,
    private val modelDownloader: ModelDownloader,
    private val localDateProvider: LocalDateProvider,
    private val confirmFoodEstimateUseCase: ConfirmFoodEstimateUseCase,
    private val updateFoodEntryUseCase: UpdateFoodEntryUseCase,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CaptureUiState(
            modelAvailable = modelStorage.hasUsableModel(),
            modelStatusMessage = modelAvailabilityMessage(modelStorage.hasUsableModel()),
        ),
    )
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var pendingImagePath: String? = null
    private var pendingResult: FoodEstimationResult? = null

    init {
        modelStorage.cleanupIncompleteModelFiles()
        if (!modelStorage.hasUsableModel()) {
            downloadModelFromLocalServer()
        }
    }

    fun downloadModelFromLocalServer() {
        val current = _uiState.value
        if (current.isDownloadingModel || modelStorage.hasUsableModel()) {
            return
        }
        _uiState.value = current.copy(
            isDownloadingModel = true,
            modelStatusMessage = "Downloading model from 192.168.0.168...",
            errorMessage = null,
        )

        viewModelScope.launch(backgroundDispatcher) {
            var attempt = 0
            while (isActive && !modelStorage.hasUsableModel()) {
                attempt += 1
                _uiState.value = _uiState.value.copy(
                    isDownloadingModel = true,
                    modelAvailable = false,
                    modelStatusMessage = "Downloading model from 192.168.0.168... attempt $attempt",
                    errorMessage = null,
                )

                val result = modelDownloader.downloadFrom(DEBUG_MODEL_URL)
                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        isDownloadingModel = false,
                        modelAvailable = true,
                        modelStatusMessage = "Model ready on device.",
                        errorMessage = null,
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    isDownloadingModel = true,
                    modelAvailable = false,
                    modelStatusMessage = "Model download failed. Retrying in ${MODEL_DOWNLOAD_RETRY_SECONDS}s...",
                    errorMessage = result.exceptionOrNull()?.message,
                )
                delay(MODEL_DOWNLOAD_RETRY_DELAY_MS)
            }
        }
    }

    fun analyzePhoto(imagePath: String) {
        val modelAvailable = modelStorage.hasUsableModel()
        val capturedAt = Instant.now()
        pendingImagePath = imagePath
        _uiState.value = CaptureUiState(
            imagePath = imagePath,
            isLoading = true,
            modelAvailable = modelAvailable,
            modelStatusMessage = modelAvailabilityMessage(modelAvailable),
        )

        viewModelScope.launch(backgroundDispatcher) {
            val result = foodEstimationEngine.estimate(
                FoodEstimationRequest(
                    imagePath = imagePath,
                    capturedAtEpochMs = capturedAt.toEpochMilli(),
                ),
            )

            result.fold(
                onSuccess = { estimation ->
                    pendingResult = estimation
                    if (estimation.confidenceState.name == "High") {
                        saveEstimation(
                            capturedAt = capturedAt,
                            imagePath = imagePath,
                            estimation = estimation,
                            confirmationStatus = ConfirmationStatus.NotRequired,
                        )
                    } else {
                        _uiState.value = CaptureUiState(
                            imagePath = imagePath,
                            estimatedCalories = estimation.estimatedCalories,
                            detectedFoodLabel = estimation.detectedFoodLabel,
                            confidenceNotes = estimation.confidenceNotes,
                            awaitingConfirmation = true,
                            modelAvailable = true,
                            modelStatusMessage = modelAvailabilityMessage(true),
                        )
                    }
                },
                onFailure = { throwable ->
                    val message = when ((throwable as? FoodEstimationException)?.error?.toString()) {
                        "ModelUnavailable" -> "Model unavailable on device."
                        "ModelLoadFailed" -> "Model could not be loaded."
                        "UnreadableImage" -> "Photo could not be read. Take another one."
                        "InferenceTimeout" -> "Estimation timed out. Try again."
                        else -> "Calories could not be estimated from this photo."
                    }
                    _uiState.value = CaptureUiState(
                        imagePath = imagePath,
                        errorMessage = message,
                        modelAvailable = modelStorage.hasUsableModel(),
                        modelStatusMessage = modelAvailabilityMessage(modelStorage.hasUsableModel()),
                    )
                },
            )
        }
    }

    fun confirmDetection(accepted: Boolean) {
        val imagePath = pendingImagePath ?: return
        val estimation = pendingResult ?: return
        if (!confirmFoodEstimateUseCase.shouldSave(accepted)) {
            pendingImagePath = null
            pendingResult = null
            _uiState.value = CaptureUiState(
                shouldRetake = true,
                errorMessage = "Take another photo.",
                modelAvailable = modelStorage.hasUsableModel(),
                modelStatusMessage = modelAvailabilityMessage(modelStorage.hasUsableModel()),
            )
            return
        }

        viewModelScope.launch(backgroundDispatcher) {
            saveEstimation(
                capturedAt = Instant.now(),
                imagePath = imagePath,
                estimation = estimation,
                confirmationStatus = ConfirmationStatus.Accepted,
            )
        }
    }

    fun consumeRetakeRequest() {
        _uiState.value = _uiState.value.copy(shouldRetake = false, errorMessage = null)
    }

    private suspend fun saveEstimation(
        capturedAt: Instant,
        imagePath: String,
        estimation: FoodEstimationResult,
        confirmationStatus: ConfirmationStatus,
    ) {
        val entry = FoodEntry(
            capturedAt = capturedAt,
            entryDate = localDateProvider.dateFor(capturedAt),
            imagePath = imagePath,
            estimatedCalories = estimation.estimatedCalories,
            finalCalories = estimation.estimatedCalories,
            confidenceState = estimation.confidenceState,
            detectedFoodLabel = estimation.detectedFoodLabel,
            confidenceNotes = estimation.confidenceNotes,
            confirmationStatus = confirmationStatus,
            source = FoodEntrySource.AiEstimate,
            entryStatus = FoodEntryStatus.Ready,
        )
        val entryId = updateFoodEntryUseCase(entry)
        pendingImagePath = null
        pendingResult = null
        _uiState.value = CaptureUiState(
            imagePath = imagePath,
            estimatedCalories = estimation.estimatedCalories,
            detectedFoodLabel = estimation.detectedFoodLabel,
            confidenceNotes = estimation.confidenceNotes,
            savedEntryId = entryId,
            modelAvailable = true,
            modelStatusMessage = modelAvailabilityMessage(true),
        )
    }

    private fun modelAvailabilityMessage(modelAvailable: Boolean): String =
        if (modelAvailable) "Model ready on device." else "Model not installed yet."

    companion object {
        private const val DEBUG_MODEL_URL = "http://192.168.0.168:18080/gemma-4-E2B-it.litertlm"
        private const val MODEL_DOWNLOAD_RETRY_DELAY_MS = 5_000L
        private const val MODEL_DOWNLOAD_RETRY_SECONDS = 5
    }
}

class FoodCaptureViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return FoodCaptureViewModel(
            foodEstimationEngine = container.foodEstimationEngine,
            modelStorage = container.modelStorage,
            modelDownloader = container.modelDownloader,
            localDateProvider = container.localDateProvider,
            confirmFoodEstimateUseCase = container.confirmFoodEstimateUseCase,
            updateFoodEntryUseCase = container.updateFoodEntryUseCase,
            backgroundDispatcher = Dispatchers.Default,
        ) as T
    }
}
