package com.dreef3.weightlossapp.features.summary

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun OverBudgetNotice(isOverBudget: Boolean) {
    if (isOverBudget) Text("You are over budget.")
}
