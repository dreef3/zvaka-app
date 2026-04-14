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
            llamaCppEngines = mapOf(
                CoachModel.SmolLm to FakeDietChatEngine("smollm"),
                CoachModel.SmolLm2 to FakeDietChatEngine("smollm2"),
                CoachModel.Qwen0_8b to FakeDietChatEngine("qwen0.8b"),
                CoachModel.Qwen2b to FakeDietChatEngine("qwen2b"),
            ),
        )

        val result = engine.sendMessage("hello", emptyList(), emptySnapshot()).getOrThrow()

        assertEquals("gemma", result)
    }

    @Test
    fun usesSmolLmEngineWhenSmolLmSelected() = runBlocking {
        val preferences = testPreferences()
        preferences.reset()
        preferences.setCoachModel(CoachModel.SmolLm)
        val engine = SelectableDietChatEngine(
            preferences = preferences,
            gemmaEngine = FakeDietChatEngine("gemma"),
            llamaCppEngines = mapOf(
                CoachModel.SmolLm to FakeDietChatEngine("smollm"),
                CoachModel.SmolLm2 to FakeDietChatEngine("smollm2"),
                CoachModel.Qwen0_8b to FakeDietChatEngine("qwen0.8b"),
                CoachModel.Qwen2b to FakeDietChatEngine("qwen2b"),
            ),
        )

        val result = engine.sendMessage("hello", emptyList(), emptySnapshot()).getOrThrow()

        assertEquals("smollm", result)
    }

    @Test
    fun usesQwen2bEngineWhenQwen2bSelected() = runBlocking {
        val preferences = testPreferences()
        preferences.reset()
        preferences.setCoachModel(CoachModel.Qwen2b)
        val engine = SelectableDietChatEngine(
            preferences = preferences,
            gemmaEngine = FakeDietChatEngine("gemma"),
            llamaCppEngines = mapOf(
                CoachModel.SmolLm to FakeDietChatEngine("smollm"),
                CoachModel.SmolLm2 to FakeDietChatEngine("smollm2"),
                CoachModel.Qwen0_8b to FakeDietChatEngine("qwen0.8b"),
                CoachModel.Qwen2b to FakeDietChatEngine("qwen2b"),
            ),
        )

        val result = engine.sendMessage("hello", emptyList(), emptySnapshot()).getOrThrow()

        assertEquals("qwen2b", result)
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
            preferences.setCoachModel(CoachModel.SmolLm)
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
