package com.dreef3.weightlossapp.inference

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

data class ToolFoodEstimate(
    val description: String,
    val calories: Int,
)

class FoodEstimationTools(
    private val onEstimateSubmitted: (ToolFoodEstimate) -> Unit,
) : ToolSet {

    @Tool(description = "Submit the final food description and calorie estimate for the photo.")
    fun submitFoodEstimate(
        @ToolParam(description = "A short one-line description of the food shown in the photo.")
        description: String,
        @ToolParam(description = "Estimated calories as a single integer.")
        calories: Int,
    ): Map<String, Any> {
        onEstimateSubmitted(
            ToolFoodEstimate(
                description = description.trim(),
                calories = calories.coerceAtLeast(0),
            ),
        )
        return mapOf(
            "result" to "accepted",
            "description" to description.trim(),
            "calories" to calories.coerceAtLeast(0),
        )
    }
}
