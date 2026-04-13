package com.dreef3.weightlossapp.features.onboarding

import android.app.Activity
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.app.media.ModelDownloadState
import com.dreef3.weightlossapp.app.media.ModelDescriptors
import com.dreef3.weightlossapp.app.sync.DriveAuthorizationOutcome
import com.dreef3.weightlossapp.app.sync.DriveSyncState
import com.dreef3.weightlossapp.chat.CoachModel
import com.dreef3.weightlossapp.chat.requiredModelDescriptor
import com.dreef3.weightlossapp.inference.CalorieEstimationModel
import com.dreef3.weightlossapp.inference.primaryModelDescriptor
import com.dreef3.weightlossapp.inference.requiredModelDescriptors
import com.dreef3.weightlossapp.domain.usecase.SaveUserProfileRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PendingDriveAction {
    Connect,
    SyncNow,
    Restore,
}

@Composable
fun ProfileEditScreen(
    container: AppContainer,
    onBack: () -> Unit = {},
    onResetToOnboarding: () -> Unit = {},
    onRestoreCompleted: (Boolean) -> Unit = {},
) {
    val profile by container.profileRepository.observeProfile().collectAsStateWithLifecycle(initialValue = null)
    val budgetPeriods by container.profileRepository.observeBudgetPeriods().collectAsStateWithLifecycle(initialValue = emptyList())
    val driveSyncState by container.preferences.driveSyncState.collectAsStateWithLifecycle(initialValue = DriveSyncState())
    val coachModel by container.preferences.coachModel.collectAsStateWithLifecycle(initialValue = CoachModel.Gemma)
    val calorieModel by container.preferences.calorieEstimationModel.collectAsStateWithLifecycle(initialValue = CalorieEstimationModel.Gemma)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val selectedCalorieDescriptor = calorieModel.primaryModelDescriptor()
    val selectedCalorieStateFlow = remember(calorieModel, container) {
        combinedDownloadState(
            modelDownloadController = container.modelDownloadRepository,
            models = calorieModel.requiredModelDescriptors(),
        )
    }
    val selectedCalorieDownloadState by selectedCalorieStateFlow
        .collectAsStateWithLifecycle(initialValue = ModelDownloadState())
    val selectedCalorieReady = calorieModel.requiredModelDescriptors().all(container.modelStorage::hasUsableModel)
    val selectedCoachDescriptor = coachModel.requiredModelDescriptor()
    val selectedCoachDownloadState by container.modelDownloadRepository
        .observeState(selectedCoachDescriptor)
        .collectAsStateWithLifecycle(initialValue = ModelDownloadState())
    val selectedCoachReady = container.modelStorage.hasUsableModel(selectedCoachDescriptor)

    var form by remember { mutableStateOf(OnboardingFormState()) }
    var hasLoaded by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var isSaving by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var resetConfirmationName by remember { mutableStateOf("") }
    var isDriveBusy by remember { mutableStateOf(false) }
    var driveStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingDriveAction by remember { mutableStateOf<PendingDriveAction?>(null) }
    val currentBudget = budgetPeriods.maxByOrNull { it.effectiveFromDate }?.caloriesPerDay
    val requiredResetName = profile?.firstName?.trim().orEmpty()

    suspend fun runDriveAction(
        action: PendingDriveAction,
        authorization: DriveAuthorizationOutcome.Authorized,
    ) {
        when (action) {
            PendingDriveAction.Connect -> {
                container.preferences.setDriveSyncEnabled(true)
                container.driveSyncScheduler.enablePeriodicSync()
                container.googleDriveSyncManager.uploadBackup(
                    accessToken = authorization.accessToken,
                    accountEmail = authorization.accountEmail,
                )
                driveStatusMessage = "Google Drive connected. Automatic sync is on."
            }

            PendingDriveAction.SyncNow -> {
                container.googleDriveSyncManager.uploadBackup(
                    accessToken = authorization.accessToken,
                    accountEmail = authorization.accountEmail ?: driveSyncState.accountEmail,
                )
                driveStatusMessage = "Synced latest local data to Google Drive."
            }

            PendingDriveAction.Restore -> {
                val restoreSummary = container.googleDriveSyncManager.restoreBackup(authorization.accessToken)
                driveStatusMessage = "Restored the latest backup from Google Drive."
                onRestoreCompleted(restoreSummary.hasProfile && restoreSummary.hasCompletedOnboarding)
            }
        }
    }

    val driveAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val action = pendingDriveAction ?: return@rememberLauncherForActivityResult
        scope.launch {
            isDriveBusy = true
            val currentActivity = activity ?: run {
                isDriveBusy = false
                pendingDriveAction = null
                driveStatusMessage = "Google Drive actions require an active screen."
                return@launch
            }
            runCatching {
                when (
                    val authorization = container.googleDriveSyncManager.completeAuthorization(
                        currentActivity,
                        result.data,
                    )
                ) {
                    is DriveAuthorizationOutcome.Authorized -> runDriveAction(action, authorization)
                    is DriveAuthorizationOutcome.NeedsResolution ->
                        error("Google Drive authorization still needs confirmation.")
                }
            }.onSuccess {
                pendingDriveAction = null
                isDriveBusy = false
            }.onFailure { error ->
                pendingDriveAction = null
                isDriveBusy = false
                driveStatusMessage = error.message ?: "Google Drive action failed."
            }
        }
    }

    suspend fun startDriveAction(action: PendingDriveAction) {
        val currentActivity = activity
        if (currentActivity == null) {
            driveStatusMessage = "Google Drive actions require an active screen."
            return
        }
        isDriveBusy = true
        pendingDriveAction = action
        runCatching {
            when (val authorization = container.googleDriveSyncManager.authorizeInteractively(currentActivity)) {
                is DriveAuthorizationOutcome.Authorized -> {
                    runDriveAction(action, authorization)
                    pendingDriveAction = null
                    isDriveBusy = false
                }

                is DriveAuthorizationOutcome.NeedsResolution -> {
                    isDriveBusy = false
                    driveAuthorizationLauncher.launch(
                        IntentSenderRequest.Builder(authorization.intentSender).build(),
                    )
                }
            }
        }.onFailure { error ->
            isDriveBusy = false
            pendingDriveAction = null
            driveStatusMessage = error.message ?: "Google Drive action failed."
        }
    }

    LaunchedEffect(profile) {
        if (!hasLoaded && profile != null) {
            form = OnboardingFormState(
                firstName = profile!!.firstName,
                ageYears = profile!!.ageYears.toString(),
                heightCm = profile!!.heightCm.toString(),
                weightKg = profile!!.weightKg.toString(),
                sex = profile!!.sex,
                activityLevel = profile!!.activityLevel,
            )
            hasLoaded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Profile",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Current daily budget",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentBudget?.toString() ?: "Not set",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Changes apply from today onward and do not rewrite history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OnboardingFields(
            state = form,
            onFirstNameChanged = { form = form.copy(firstName = it) },
            onAgeChanged = { form = form.copy(ageYears = it.filter(Char::isDigit)) },
            onHeightChanged = { form = form.copy(heightCm = it.filter(Char::isDigit)) },
            onWeightChanged = { value -> form = form.copy(weightKg = value.filter(Char::isDigit)) },
            onSexChanged = { form = form.copy(sex = it) },
            onActivityLevelChanged = { form = form.copy(activityLevel = it) },
        )
        if (errors.isNotEmpty()) {
            errors.forEach { issue ->
                Text(
                    text = issue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Button(
            onClick = {
                val issues = OnboardingValidator.validate(form)
                if (issues.isNotEmpty()) {
                    errors = issues
                    return@Button
                }
                scope.launch {
                    isSaving = true
                    errors = emptyList()
                    container.saveUserProfileUseCase(
                        SaveUserProfileRequest(
                            firstName = form.firstName,
                            sex = form.sex,
                            ageYears = form.ageYears.toInt(),
                            heightCm = form.heightCm.toInt(),
                            weightKg = form.weightKg.toInt().toDouble(),
                            activityLevel = form.activityLevel,
                        ),
                    )
                    isSaving = false
                }
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isSaving) "Saving..." else "Save profile")
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Photo estimation model",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CalorieEstimationModel.entries.forEach { model ->
                        FilterChip(
                            selected = calorieModel == model,
                            onClick = {
                                scope.launch {
                                    container.preferences.setCalorieEstimationModel(model)
                                    model.requiredModelDescriptors().forEach { descriptor ->
                                        container.modelStorage.cleanupIncompleteModelFiles(descriptor)
                                        if (!container.modelStorage.hasUsableModel(descriptor)) {
                                            container.modelDownloadRepository.enqueueIfNeeded(descriptor)
                                        }
                                    }
                                }
                            },
                            label = { Text(model.displayName) },
                        )
                    }
                }
                Text(
                    text = if (selectedCalorieReady) {
                        "${selectedCalorieDescriptor.displayName} photo model is ready on this device."
                    } else if (selectedCalorieDownloadState.isDownloading) {
                        "Downloading ${selectedCalorieDescriptor.displayName}... ${selectedCalorieDownloadState.progressPercent ?: 0}%"
                    } else {
                        "${selectedCalorieDescriptor.displayName} photo model is not downloaded yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedCalorieDownloadState.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (calorieModel != CalorieEstimationModel.Gemma) {
                    Text(
                        text = "This model uses GGUF via llama.cpp and requires a separate mmproj file for photo estimation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { calorieModel.requiredModelDescriptors().forEach(container.modelDownloadRepository::enqueueIfNeeded) },
                    enabled = !selectedCalorieReady && !selectedCalorieDownloadState.isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (selectedCalorieDownloadState.isDownloading) {
                            "Downloading photo model..."
                        } else {
                            "Download selected photo model"
                        },
                    )
                }
                Text(
                    text = "Coach model",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CoachModel.entries.forEach { model ->
                        FilterChip(
                            selected = coachModel == model,
                            onClick = {
                                scope.launch {
                                    container.preferences.setCoachModel(model)
                                    container.modelStorage.cleanupIncompleteModelFiles(model.requiredModelDescriptor())
                                    if (!container.modelStorage.hasUsableModel(model.requiredModelDescriptor())) {
                                        container.modelDownloadRepository.enqueueIfNeeded(model.requiredModelDescriptor())
                                    }
                                }
                            },
                            label = { Text(model.displayName) },
                        )
                    }
                }
                Text(
                    text = if (selectedCoachReady) {
                        "${selectedCoachDescriptor.displayName} is ready on this device."
                    } else if (selectedCoachDownloadState.isDownloading) {
                        "Downloading ${selectedCoachDescriptor.displayName}... ${selectedCoachDownloadState.progressPercent ?: 0}%"
                    } else {
                        "${selectedCoachDescriptor.displayName} is not downloaded yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedCoachDownloadState.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (coachModel != CoachModel.Gemma) {
                    Text(
                        text = "This model uses GGUF via llama.cpp for on-device chat. Tool-calling style meal edits remain stronger on the Gemma path.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { container.modelDownloadRepository.enqueueIfNeeded(selectedCoachDescriptor) },
                    enabled = !selectedCoachReady && !selectedCoachDownloadState.isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (selectedCoachDownloadState.isDownloading) {
                            "Downloading coach model..."
                        } else {
                            "Download selected coach model"
                        },
                    )
                }
                Text(
                    text = if (driveSyncState.isEnabled) {
                        buildString {
                            append("Google Drive sync is on")
                            driveSyncState.accountEmail?.let { append(" for ").append(it) }
                            driveSyncState.lastSyncedAtEpochMs?.let {
                                append(". Last sync: ").append(formatDriveSyncTime(it))
                            }
                        }
                    } else {
                        "Connect Google Drive to keep your local-first data backed up automatically in the app's hidden Drive storage."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Sync covers profile, budgets, meal history, coach chats, and saved meal photos. Downloaded AI model files stay local and re-download on device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!driveStatusMessage.isNullOrBlank()) {
                    Text(
                        text = driveStatusMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                driveSyncState.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (driveSyncState.isEnabled) {
                    Button(
                        onClick = { scope.launch { startDriveAction(PendingDriveAction.SyncNow) } },
                        enabled = !isDriveBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isDriveBusy && pendingDriveAction == PendingDriveAction.SyncNow) "Syncing..." else "Sync now")
                    }
                    OutlinedButton(
                        onClick = { showRestoreDialog = true },
                        enabled = !isDriveBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Restore latest backup from Drive")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isDriveBusy = true
                                container.driveSyncScheduler.disablePeriodicSync()
                                container.googleDriveSyncManager.disconnect()
                                driveStatusMessage = "Google Drive automatic sync turned off."
                                isDriveBusy = false
                            }
                        },
                        enabled = !isDriveBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Turn off automatic sync")
                    }
                } else {
                    Button(
                        onClick = { scope.launch { startDriveAction(PendingDriveAction.Connect) } },
                        enabled = !isDriveBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isDriveBusy && pendingDriveAction == PendingDriveAction.Connect) "Connecting..." else "Connect Google Drive")
                    }
                }
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    enabled = !isResetting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isResetting) "Resetting..." else "Reset app and restart onboarding")
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isResetting) {
                    showResetDialog = false
                    resetConfirmationName = ""
                }
            },
            title = {
                Text("Irreversible erase")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This will completely and irreversibly erase all your data from this app, including profile, meal history, coach chats, downloaded models, and saved photos.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "There is no undo. Type your name exactly as shown to continue: ${requiredResetName.ifBlank { "(name not set)" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = resetConfirmationName,
                        onValueChange = { resetConfirmationName = it },
                        singleLine = true,
                        enabled = !isResetting,
                        label = { Text("Type your name") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isResetting = true
                            withContext(Dispatchers.IO) {
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.smolVlm.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.smolVlmMmproj.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.smolVlmTflite.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.smolLm.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.smolLm2.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.qwen0_8b.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.qwen0_8bMmproj.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.qwen2b.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.qwen2bMmproj.uniqueWorkName)
                                WorkManager.getInstance(container.appContext)
                                    .cancelUniqueWork(ModelDescriptors.gemma.uniqueWorkName)
                                WorkManager.getInstance(container.appContext).cancelAllWork()
                                container.driveSyncScheduler.disablePeriodicSync()
                                container.database.clearAllTables()
                                container.preferences.reset()
                                container.modelStorage.clearAll()
                                container.photoStorage.clearAll()
                            }
                            isResetting = false
                            showResetDialog = false
                            resetConfirmationName = ""
                            onResetToOnboarding()
                        }
                    },
                    enabled = !isResetting && requiredResetName.isNotBlank() && resetConfirmationName.trim() == requiredResetName,
                ) {
                    Text(if (isResetting) "Erasing..." else "Erase everything")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showResetDialog = false
                        resetConfirmationName = ""
                    },
                    enabled = !isResetting,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isDriveBusy) {
                    showRestoreDialog = false
                }
            },
            title = { Text("Restore from Google Drive") },
            text = {
                Text(
                    "This will replace the current local profile, meal history, chats, preferences, and saved photos with the latest backup from Google Drive.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreDialog = false
                        scope.launch { startDriveAction(PendingDriveAction.Restore) }
                    },
                    enabled = !isDriveBusy,
                ) {
                    Text(if (isDriveBusy && pendingDriveAction == PendingDriveAction.Restore) "Restoring..." else "Restore backup")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRestoreDialog = false },
                    enabled = !isDriveBusy,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun combinedDownloadState(
    modelDownloadController: com.dreef3.weightlossapp.app.media.ModelDownloadController,
    models: List<com.dreef3.weightlossapp.app.media.ModelDescriptor>,
): Flow<ModelDownloadState> {
    val states = models.map(modelDownloadController::observeState)
    return combine(states) { stateArray ->
        val values = stateArray.toList()
        val totalBytes = values.sumOf { it.totalBytes }
        val downloadedBytes = values.sumOf { it.downloadedBytes }
        val progressPercent = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt() else null
        ModelDownloadState(
            isDownloading = values.any { it.isDownloading },
            progressPercent = progressPercent,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            errorMessage = values.firstNotNullOfOrNull { it.errorMessage },
        )
    }
}

private fun formatDriveSyncTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime())
