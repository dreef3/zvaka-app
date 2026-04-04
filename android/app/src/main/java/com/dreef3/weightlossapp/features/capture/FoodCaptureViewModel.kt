package com.dreef3.weightlossapp.features.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDownloadController
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class FoodCaptureViewModel(
    private val foodEstimationEngine: FoodEstimationEngine,
    private val modelStorage: ModelStorage,
    private val modelDownloadRepository: ModelDownloadController,
    private val localDateProvider: LocalDateProvider,
    private val confirmFoodEstimateUseCase: ConfirmFoodEstimateUseCase,
    private val updateFoodEntryUseCase: UpdateFoodEntryUseCase,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CaptureUiState(
            modelAvailable = modelStorage.hasUsableModel(),
            modelStatusMessage = if (modelStorage.hasUsableModel()) "Model ready on device." else "Model not installed yet.",
        ),
    )
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var pendingImagePath: String? = null
    private var pendingResult: FoodEstimationResult? = null

    init {
        modelStorage.cleanupIncompleteModelFiles()
        if (!modelStorage.hasUsableModel()) {
            modelDownloadRepository.enqueueIfNeeded()
        }
        viewModelScope.launch {
            modelDownloadRepository.observeState().collect { state ->
                val modelAvailable = modelStorage.hasUsableModel()
                _uiState.update { current ->
                    current.copy(
                        isDownloadingModel = state.isDownloading,
                        modelAvailable = modelAvailable,
                        modelDownloadProgressPercent = state.progressPercent,
                        modelStatusMessage = modelStatusMessage(
                            modelAvailable = modelAvailable,
                            isDownloading = state.isDownloading,
                            progressPercent = state.progressPercent,
                            errorMessage = state.errorMessage,
                        ),
                        errorMessage = if (current.errorMessage == null) state.errorMessage else current.errorMessage,
                    )
                }
            }
        }
    }

    fun downloadModel() {
        modelDownloadRepository.enqueueIfNeeded()
    }

    fun analyzePhoto(imagePath: String) {
        val modelAvailable = modelStorage.hasUsableModel()
        val capturedAt = Instant.now()
        pendingImagePath = imagePath
        _uiState.value = CaptureUiState(
            imagePath = imagePath,
            isLoading = true,
            modelAvailable = modelAvailable,
            modelStatusMessage = modelStatusMessage(modelAvailable, _uiState.value.isDownloadingModel, _uiState.value.modelDownloadProgressPercent, null),
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
                            modelStatusMessage = modelStatusMessage(true, false, 100, null),
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
                        modelStatusMessage = modelStatusMessage(
                            modelAvailable = modelStorage.hasUsableModel(),
                            isDownloading = _uiState.value.isDownloadingModel,
                            progressPercent = _uiState.value.modelDownloadProgressPercent,
                            errorMessage = null,
                        ),
                        modelDownloadProgressPercent = _uiState.value.modelDownloadProgressPercent,
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
                modelStatusMessage = modelStatusMessage(
                    modelAvailable = modelStorage.hasUsableModel(),
                    isDownloading = _uiState.value.isDownloadingModel,
                    progressPercent = _uiState.value.modelDownloadProgressPercent,
                    errorMessage = null,
                ),
                modelDownloadProgressPercent = _uiState.value.modelDownloadProgressPercent,
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
            modelStatusMessage = modelStatusMessage(true, false, 100, null),
            modelDownloadProgressPercent = 100,
        )
    }

    private fun modelStatusMessage(
        modelAvailable: Boolean,
        isDownloading: Boolean,
        progressPercent: Int?,
        errorMessage: String?,
    ): String = when {
        modelAvailable -> "Model ready on device."
        isDownloading && progressPercent != null -> "Downloading model from Hugging Face... $progressPercent%"
        isDownloading -> "Downloading model from Hugging Face..."
        errorMessage != null -> "Model download failed."
        else -> "Model not installed yet."
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
            modelDownloadRepository = container.modelDownloadRepository,
            localDateProvider = container.localDateProvider,
            confirmFoodEstimateUseCase = container.confirmFoodEstimateUseCase,
            updateFoodEntryUseCase = container.updateFoodEntryUseCase,
            backgroundDispatcher = Dispatchers.Default,
        ) as T
    }
}
