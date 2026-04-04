package com.dreef3.weightlossapp.features.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreef3.weightlossapp.app.di.AppContainer
import com.dreef3.weightlossapp.domain.model.ActivityLevel
import com.dreef3.weightlossapp.domain.model.Sex

@Composable
fun OnboardingScreenRoute(
    container: AppContainer,
    onCompleted: () -> Unit,
) {
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModelFactory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) onCompleted()
    }

    OnboardingScreen(
        state = state,
        onContinueFromIntro = vm::continueFromIntro,
        onFirstNameChanged = { value -> vm.updateForm { it.copy(firstName = value) } },
        onAgeChanged = { value -> vm.updateForm { it.copy(ageYears = value.filter(Char::isDigit)) } },
        onHeightChanged = { value -> vm.updateForm { it.copy(heightCm = value.filter(Char::isDigit)) } },
        onWeightChanged = { value -> vm.updateForm { it.copy(weightKg = value.filter(Char::isDigit)) } },
        onSexChanged = { value -> vm.updateForm { it.copy(sex = value) } },
        onActivityLevelChanged = { value -> vm.updateForm { it.copy(activityLevel = value) } },
        onBackFromProfile = vm::backFromProfile,
        onSubmitProfile = vm::submitProfile,
        onStartModelDownload = vm::requestModelDownload,
        onConfirmCellularDownload = vm::confirmCellularModelDownload,
        onDismissCellularDownload = vm::dismissCellularModelDownloadConfirmation,
        onFinish = vm::completeSetup,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onContinueFromIntro: () -> Unit,
    onFirstNameChanged: (String) -> Unit,
    onAgeChanged: (String) -> Unit,
    onHeightChanged: (String) -> Unit,
    onWeightChanged: (String) -> Unit,
    onSexChanged: (Sex) -> Unit,
    onActivityLevelChanged: (ActivityLevel) -> Unit,
    onBackFromProfile: () -> Unit,
    onSubmitProfile: () -> Unit,
    onStartModelDownload: () -> Unit,
    onConfirmCellularDownload: () -> Unit,
    onDismissCellularDownload: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SetupHeader(step = state.step)
        when (state.step) {
            OnboardingStep.DownloadIntro -> DownloadIntroStep(onContinueFromIntro)
            OnboardingStep.Profile -> ProfileStep(
                state = state,
                onFirstNameChanged = onFirstNameChanged,
                onAgeChanged = onAgeChanged,
                onHeightChanged = onHeightChanged,
                onWeightChanged = onWeightChanged,
                onSexChanged = onSexChanged,
                onActivityLevelChanged = onActivityLevelChanged,
                onBack = onBackFromProfile,
                onContinue = onSubmitProfile,
            )
            OnboardingStep.BudgetPreview -> BudgetPreviewStep(
                firstName = state.form.firstName,
                estimatedBudgetCalories = state.estimatedBudgetCalories ?: 0,
                downloadAlreadyInProgress = state.modelDownloadState.isDownloading,
                onContinue = onStartModelDownload,
            )
            OnboardingStep.Downloading -> DownloadingStep(state)
            OnboardingStep.Ready -> ReadyStep(
                firstName = state.form.firstName,
                onFinish = onFinish,
            )
        }
        if (state.showCellularDownloadConfirmation) {
            CellularDownloadConfirmationDialog(
                onConfirm = onConfirmCellularDownload,
                onDismiss = onDismissCellularDownload,
            )
        }
    }
}

@Composable
private fun SetupHeader(step: OnboardingStep) {
    Text(
        text = when (step) {
            OnboardingStep.DownloadIntro -> "Let’s set up Žvaka"
            OnboardingStep.Profile -> "Your calorie baseline"
            OnboardingStep.BudgetPreview -> "Daily calorie target"
            OnboardingStep.Downloading -> "Preparing the local AI model"
            OnboardingStep.Ready -> "Žvaka is ready"
        },
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun DownloadIntroStep(
    onContinue: () -> Unit,
) {
    StepCard {
        HeroPlate()
        Text(
            text = "Žvaka runs food analysis fully on your phone. To do that, it needs to download a large local AI model once.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "The download is around 2.6 GB. You can keep using your phone while it downloads, and afterward food photos work without a cloud API.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sounds good")
        }
    }
}

@Composable
private fun ProfileStep(
    state: OnboardingUiState,
    onFirstNameChanged: (String) -> Unit,
    onAgeChanged: (String) -> Unit,
    onHeightChanged: (String) -> Unit,
    onWeightChanged: (String) -> Unit,
    onSexChanged: (Sex) -> Unit,
    onActivityLevelChanged: (ActivityLevel) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    StepCard {
        Text(
            text = "Only the details needed for the Mifflin-St Jeor calorie estimate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OnboardingFields(
            state = state.form,
            onFirstNameChanged = onFirstNameChanged,
            onAgeChanged = onAgeChanged,
            onHeightChanged = onHeightChanged,
            onWeightChanged = onWeightChanged,
            onSexChanged = onSexChanged,
            onActivityLevelChanged = onActivityLevelChanged,
        )
        state.errors.forEach { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            Button(
                onClick = onContinue,
                enabled = !state.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isSaving) "Saving..." else "Continue")
            }
        }
    }
}

@Composable
private fun BudgetPreviewStep(
    firstName: String,
    estimatedBudgetCalories: Int,
    downloadAlreadyInProgress: Boolean,
    onContinue: () -> Unit,
) {
    StepCard {
        Text(
            text = "Based on your current details, Žvaka estimates this as your daily calorie budget.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BudgetRing(calories = estimatedBudgetCalories)
        Text(
            text = "${firstName.ifBlank { "Your" }} daily target is about $estimatedBudgetCalories kcal.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This gives the app a daily budget to track meals against. You can still adjust your profile later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (downloadAlreadyInProgress) "See download progress" else "Start model download")
        }
    }
}

@Composable
private fun DownloadingStep(
    state: OnboardingUiState,
) {
    StepCard {
        DownloadFeast()
        Text(
            text = "Downloading the local food-analysis model.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This may take a while because the model is large. Once it is ready, food photos will be processed directly on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        LinearProgressIndicator(
            progress = { (state.modelDownloadState.progressPercent ?: 0) / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
        )
        Text(
            text = when {
                state.modelDownloadState.progressPercent != null ->
                    "${state.modelDownloadState.progressPercent}% downloaded"
                state.modelDownloadState.isDownloading -> "Starting download..."
                else -> "Preparing download..."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.modelDownloadState.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReadyStep(
    firstName: String,
    onFinish: () -> Unit,
) {
    StepCard {
        HeroPlate()
        Text(
            text = "${firstName.ifBlank { "Everything" }} is ready. The local model is installed and Žvaka can start tracking meals.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Take your first food photo and the app will estimate calories in the background.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Everything is ready, let’s go")
        }
    }
}

@Composable
private fun CellularDownloadConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download over cellular?") },
        text = {
            Text(
                "The local model is large and can use a lot of mobile data. Continue only if you want to download it over cellular now.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Download anyway")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Wait for Wi-Fi")
            }
        },
    )
}

@Composable
private fun StepCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun HeroPlate() {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.tertiary
    val transition = rememberInfiniteTransition(label = "hero_orb")
    val bob by transition.animateFloat(
        initialValue = -4f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hero_bob",
    )
    val leafWiggle by transition.animateFloat(
        initialValue = -10f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "leaf_wiggle",
    )

    Box(
        modifier = Modifier.size(136.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f + bob)
            drawCircle(
                color = primary.copy(alpha = 0.10f),
                radius = size.minDimension * 0.46f,
                center = center,
            )
            drawCircle(
                color = surfaceVariant,
                radius = size.minDimension * 0.30f,
                center = center,
            )
            drawCircle(
                color = surface,
                radius = size.minDimension * 0.24f,
                center = center,
            )
            drawCircle(
                color = primary.copy(alpha = 0.18f),
                radius = size.minDimension * 0.12f,
                center = Offset(center.x - 12.dp.toPx(), center.y - 6.dp.toPx()),
            )
            drawCircle(
                color = accent.copy(alpha = 0.28f),
                radius = size.minDimension * 0.09f,
                center = Offset(center.x + 13.dp.toPx(), center.y + 10.dp.toPx()),
            )
            rotate(
                degrees = leafWiggle,
                pivot = Offset(center.x + 20.dp.toPx(), center.y - 18.dp.toPx()),
            ) {
                drawOval(
                    color = accent,
                    topLeft = Offset(center.x + 12.dp.toPx(), center.y - 28.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(18.dp.toPx(), 10.dp.toPx()),
                )
            }
            val eyeOffsetX = 8.dp.toPx()
            val eyeY = center.y - 5.dp.toPx()
            drawCircle(color = primary, radius = 2.4.dp.toPx(), center = Offset(center.x - eyeOffsetX, eyeY))
            drawCircle(color = primary, radius = 2.4.dp.toPx(), center = Offset(center.x + eyeOffsetX, eyeY))
            drawArc(
                color = primary,
                startAngle = 15f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(center.x - 15.dp.toPx(), center.y - 2.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(30.dp.toPx(), 20.dp.toPx()),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun BudgetRing(calories: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier.size(172.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = primaryContainer,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = 280f,
                useCenter = false,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = calories.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "kcal per day",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadFeast() {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiary
    val transition = rememberInfiniteTransition(label = "download_spinner")
    val steamOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = -16f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "steam_offset",
    )
    val noodleSwing by transition.animateFloat(
        initialValue = -12f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "noodle_swing",
    )

    Canvas(modifier = Modifier.size(132.dp)) {
        val bowlTop = size.height * 0.50f
        val bowlLeft = size.width * 0.18f
        val bowlWidth = size.width * 0.64f
        val bowlHeight = size.height * 0.26f

        drawArc(
            color = primaryContainer,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(bowlLeft, bowlTop),
            size = androidx.compose.ui.geometry.Size(bowlWidth, bowlHeight),
        )
        drawLine(
            color = primary,
            start = Offset(bowlLeft + 8.dp.toPx(), bowlTop + 5.dp.toPx()),
            end = Offset(bowlLeft + bowlWidth - 8.dp.toPx(), bowlTop + 5.dp.toPx()),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawArc(
            color = tertiary,
            startAngle = 210f + noodleSwing,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(size.width * 0.28f, size.height * 0.30f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.26f, size.height * 0.18f),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = tertiary,
            startAngle = 180f - noodleSwing,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(size.width * 0.42f, size.height * 0.28f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.22f, size.height * 0.20f),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = primary,
            radius = 7.dp.toPx(),
            center = Offset(size.width * 0.66f, size.height * 0.43f),
        )
        repeat(3) { index ->
            val steamX = size.width * (0.38f + index * 0.11f)
            drawArc(
                color = primary.copy(alpha = 0.45f - index * 0.08f),
                startAngle = 180f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(steamX, size.height * 0.12f + steamOffset - index * 3.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(14.dp.toPx(), 28.dp.toPx()),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        drawCircle(
            color = tertiary.copy(alpha = 0.14f),
            radius = size.minDimension * 0.42f,
            center = Offset(size.width / 2f, size.height / 2f),
        )
    }
}
