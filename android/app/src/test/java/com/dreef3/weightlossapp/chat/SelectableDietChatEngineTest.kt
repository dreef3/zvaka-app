package com.dreef3.weightlossapp.chat

import androidx.test.core.app.ApplicationProvider
import com.dreef3.weightlossapp.data.preferences.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectableDietChatEngineTest {
    @Test
    fun usesGemmaEngineWhenGemmaSelected() = runBlocking {
        val preferences = testPreferences()
        preferences.reset()
        preferences.setCoachModel(CoachModel.Gemma)
        val engine = SelectableDietChatEngine(
            preferences = preferences,
            gemmaEngine = FakeDietChatEngine("gemma"),
        )

        val result = engine.sendMessage("hello", emptyList(), emptySnapshot()).getOrThrow()

        assertEquals("gemma", result)
    }

    @Test
    fun usesGemmaEngineByDefault() = runBlocking {
        val preferences = testPreferences()
        preferences.reset()
        val engine = SelectableDietChatEngine(
            preferences = preferences,
            gemmaEngine = FakeDietChatEngine("gemma"),
        )

        val result = engine.sendMessage("hello", emptyList(), emptySnapshot()).getOrThrow()

        assertEquals("gemma", result)
    }

    private fun emptySnapshot() = DietChatSnapshot(
        todayBudgetCalories = null,
        todayConsumedCalories = 0,
        todayRemainingCalories = null,
        entries = emptyList(),
    )

    private fun testPreferences(): AppPreferences = AppPreferences(
        context = ApplicationProvider.getApplicationContext(),
        dataStoreName = "test-chat-prefs-${System.nanoTime()}",
    ).also { preferences ->
        runBlocking {
            preferences.reset()
            preferences.setCoachModel(CoachModel.Gemma)
            preferences.readCoachModel()
        }
    }

    private class FakeDietChatEngine(
        private val response: String,
    ) : DietChatEngine {
        override suspend fun sendMessage(
            message: String,
            history: List<DietChatMessage>,
            snapshot: DietChatSnapshot,
        ): Result<String> = Result.success(response)
    }
}
