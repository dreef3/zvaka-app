package com.dreef3.weightlossapp.inference

import com.dreef3.weightlossapp.domain.model.ConfidenceState

internal object FoodEstimationTextParser {
    private val genericLabels = setOf(
        "meal",
        "food",
        "dish",
        "plate",
        "lunch",
        "dinner",
        "breakfast",
        "snack",
    )

    fun parse(raw: String): FoodEstimationResult {
        val descriptionMatch = Regex("""(?im)^description\s*:\s*(.+)$""").findAll(raw).lastOrNull()
        val caloriesMatch = Regex("""(?im)^calories\s*:\s*(\d{1,5})$""").findAll(raw).lastOrNull()
        if (caloriesMatch != null) {
            val description = normalizeDescription(descriptionMatch?.groupValues?.get(1))
            return FoodEstimationResult(
                estimatedCalories = caloriesMatch.groupValues[1].toInt().coerceAtLeast(0),
                confidenceState = ConfidenceState.High,
                detectedFoodLabel = description,
                confidenceNotes = null,
                detectedItems = description?.let(::listOf) ?: emptyList(),
                debugInteractionLog = raw,
            )
        }

        val directCalories = Regex("""\b\d{1,5}\b""").find(raw)?.value?.toIntOrNull()
        if (directCalories != null) {
            return FoodEstimationResult(
                estimatedCalories = directCalories.coerceAtLeast(0),
                confidenceState = ConfidenceState.High,
                detectedFoodLabel = null,
                confidenceNotes = null,
                detectedItems = emptyList(),
                debugInteractionLog = raw,
            )
        }

        val values = raw.lineSequence()
            .mapNotNull { line ->
                val splitAt = line.indexOf('=')
                if (splitAt <= 0) {
                    null
                } else {
                    line.substring(0, splitAt).trim() to line.substring(splitAt + 1).trim()
                }
            }
            .toMap()

        val parsedCalories = values["calories"]?.toIntOrNull()?.coerceAtLeast(0)
        if (parsedCalories == null) {
            throw FoodEstimationException(
                error = FoodEstimationError.EstimationFailed,
                debugInteractionLog = raw,
            )
        }

        val label = normalizeDescription(values["food"]) ?: "unknown meal"
        val confidence = when (values["confidence"]?.lowercase()) {
            "high" -> ConfidenceState.High
            else -> ConfidenceState.NonHigh
        }

        return FoodEstimationResult(
            estimatedCalories = parsedCalories,
            confidenceState = confidence,
            detectedFoodLabel = label,
            confidenceNotes = values["notes"],
            detectedItems = listOf(label),
            debugInteractionLog = raw,
        )
    }

    private fun normalizeDescription(value: String?): String? {
        val trimmed = value?.trim()?.ifBlank { null } ?: return null
        val normalized = trimmed
            .removePrefix("\"")
            .removeSuffix("\"")
            .trim()
            .ifBlank { return null }
        return normalized
    }
}
