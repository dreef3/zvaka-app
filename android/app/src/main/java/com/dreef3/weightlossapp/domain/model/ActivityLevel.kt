package com.dreef3.weightlossapp.domain.model

enum class ActivityLevel(val multiplier: Double) {
    Sedentary(1.2),
    Light(1.375),
    Moderate(1.55),
    Active(1.725),
    VeryActive(1.9),
}
