package com.dreef3.weightlossapp.features.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun SummaryEmptyState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyMealIllustration()
            Text(
                text = "Nothing logged yet",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Take a food photo and Zvaka will track today's calories for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyMealIllustration() {
    val outline = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val soft = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier
            .size(132.dp)
            .padding(bottom = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            drawCircle(
                color = soft,
                radius = size.minDimension * 0.48f,
            )
            drawCircle(
                color = outline,
                radius = size.minDimension * 0.27f,
                style = Stroke(width = size.minDimension * 0.055f),
            )
            drawCircle(
                color = outline.copy(alpha = 0.18f),
                radius = size.minDimension * 0.15f,
            )
            drawLine(
                color = outline,
                start = androidx.compose.ui.geometry.Offset(centerX - size.width * 0.22f, centerY - size.height * 0.34f),
                end = androidx.compose.ui.geometry.Offset(centerX - size.width * 0.22f, centerY + size.height * 0.26f),
                strokeWidth = size.minDimension * 0.04f,
            )
            drawLine(
                color = outline,
                start = androidx.compose.ui.geometry.Offset(centerX + size.width * 0.25f, centerY - size.height * 0.30f),
                end = androidx.compose.ui.geometry.Offset(centerX + size.width * 0.12f, centerY + size.height * 0.24f),
                strokeWidth = size.minDimension * 0.04f,
            )
            drawCircle(
                color = accent,
                radius = size.minDimension * 0.05f,
                center = androidx.compose.ui.geometry.Offset(centerX - size.width * 0.06f, centerY - size.height * 0.06f),
            )
            drawCircle(
                color = accent,
                radius = size.minDimension * 0.04f,
                center = androidx.compose.ui.geometry.Offset(centerX + size.width * 0.08f, centerY + size.height * 0.02f),
            )
            drawCircle(
                color = accent,
                radius = size.minDimension * 0.03f,
                center = androidx.compose.ui.geometry.Offset(centerX + size.width * 0.02f, centerY - size.height * 0.12f),
            )
        }
    }
}
