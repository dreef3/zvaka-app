package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDescriptors

fun CoachModel.requiredModelDescriptor(): ModelDescriptor = when (this) {
    CoachModel.Gemma -> ModelDescriptors.gemma
    CoachModel.GemmaGguf -> ModelDescriptors.gemmaGgufCoach
    CoachModel.Qwen -> ModelDescriptors.qwenCoach
    CoachModel.Gemma3Mt6989 -> ModelDescriptors.gemma3Mt6989Coach
}

fun CoachModel.usesLlamaBackend(): Boolean = this == CoachModel.GemmaGguf

fun CoachModel.requiredPhotoModelDescriptors(): List<ModelDescriptor> = when (this) {
    CoachModel.GemmaGguf -> listOf(ModelDescriptors.smolVlm, ModelDescriptors.smolVlmMmproj)
    CoachModel.Gemma,
    CoachModel.Qwen,
    CoachModel.Gemma3Mt6989,
    -> listOf(ModelDescriptors.gemma)
}

fun CoachModel.primaryPhotoModelDescriptor(): ModelDescriptor = requiredPhotoModelDescriptors().first()
