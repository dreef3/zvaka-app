package com.dreef3.weightlossapp.app

object AppDestinations {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Chat = "chat"
    const val ChatHistory = "chat-history"
    const val MealDebug = "meal-debug"
    const val Capture = "capture"
    const val Trends = "trends"
    const val Profile = "profile"

    fun historicalChat(sessionId: Long): String = "$ChatHistory/$sessionId"
    fun mealDebug(entryId: Long): String = "$MealDebug/$entryId"
}
