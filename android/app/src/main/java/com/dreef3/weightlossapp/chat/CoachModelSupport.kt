package com.dreef3.weightlossapp.chat

import com.dreef3.weightlossapp.app.media.ModelDescriptor
import com.dreef3.weightlossapp.app.media.ModelDescriptors

fun CoachModel.requiredModelDescriptor(): ModelDescriptor = when (this) {
    CoachModel.Gemma -> ModelDescriptors.gemma
    CoachModel.SmolLm -> ModelDescriptors.smolLm
    CoachModel.SmolLm2 -> ModelDescriptors.smolLm2
    CoachModel.Qwen0_8b -> ModelDescriptors.qwen0_8b
    CoachModel.Qwen2b -> ModelDescriptors.qwen2b
}