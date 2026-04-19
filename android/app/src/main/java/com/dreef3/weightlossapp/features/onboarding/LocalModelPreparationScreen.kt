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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dreef3.weightlossapp.app.media.ModelDownloadState

@Composable
fun LocalModelPreparationScreen(
    state: ModelDownloadState,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = false,
    showRetry: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compactLayout) 20.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactLayout) 12.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PreparationFeast(compactLayout = compactLayout)
            Text(
                text = "Preparing the local AI.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "This may take a while the first time. Once it is ready, food analysis and coaching run directly on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            LinearProgressIndicator(
                progress = { (state.progressPercent ?: 0) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            Text(
                text = when {
                    state.progressPercent != null -> "${state.progressPercent}% downloaded"
                    state.isDownloading -> "Starting download..."
                    else -> "Waiting to start..."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (showRetry && onRetry != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Retry")
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Start download")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreparationFeast(compactLayout: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.tertiary
    val transition = rememberInfiniteTransition(label = "download_orb")
    val bob by transition.animateFloat(
        initialValue = -4f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "download_bob",
    )
    val leafWiggle by transition.animateFloat(
        initialValue = -10f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "download_leaf_wiggle",
    )

    Box(
        modifier = Modifier.size(if (compactLayout) 152.dp else 180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawCircle(color = surfaceVariant.copy(alpha = 0.55f), radius = size.minDimension * 0.47f)
            drawCircle(color = primary.copy(alpha = 0.16f), radius = size.minDimension * 0.39f)
        }
        Canvas(
            modifier = Modifier
                .size(if (compactLayout) 118.dp else 140.dp)
                .padding(top = 6.dp),
        ) {
            val center = Offset(size.width / 2f, size.height / 2f + bob)
            drawCircle(color = surface, radius = size.minDimension * 0.28f, center = center)
            drawCircle(
                color = primary,
                radius = size.minDimension * 0.28f,
                center = center,
                style = Stroke(width = 10f),
            )
            rotate(leafWiggle, pivot = Offset(center.x + 20f, center.y - 28f)) {
                drawOval(
                    color = accent,
                    topLeft = Offset(center.x + 2f, center.y - 58f),
                    size = androidx.compose.ui.geometry.Size(34f, 18f),
                )
            }
            drawLine(
                color = accent,
                start = Offset(center.x + 3f, center.y - 12f),
                end = Offset(center.x + 20f, center.y - 38f),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
            drawArc(
                color = primary,
                startAngle = 210f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(center.x - 26f, center.y - 22f),
                size = androidx.compose.ui.geometry.Size(52f, 52f),
                style = Stroke(width = 8f, cap = StrokeCap.Round),
            )
        }
    }
}
