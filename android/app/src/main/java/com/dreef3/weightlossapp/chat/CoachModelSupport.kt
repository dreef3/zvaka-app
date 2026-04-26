package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDescriptors

fun CoachModel.requiredModelDescriptor(): ModelDescriptor = when (this) {
    CoachModel.Gemma -> ModelDescriptors.gemma
    CoachModel.GemmaGguf -> ModelDescriptors.gemmaGgufCoach
    CoachModel.Qwen -> ModelDescriptors.qwenCoach
    CoachModel.Gemma3Mt6989 -> ModelDescriptors.gemma3Mt6989Coach
}
