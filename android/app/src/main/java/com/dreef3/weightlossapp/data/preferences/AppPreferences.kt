package com.dreef3.weightlossapp.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "app_prefs")

class AppPreferences(
    private val context: Context,
) {
    val hasCompletedOnboarding: Flow<Boolean> = context.dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs -> prefs[Keys.HasCompletedOnboarding] ?: false }

    val coachAutoAdviceEnabled: Flow<Boolean> = context.dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs -> prefs[Keys.CoachAutoAdviceEnabled] ?: true }

    suspend fun setCompletedOnboarding(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HasCompletedOnboarding] = value
        }
    }

    suspend fun setCoachAutoAdviceEnabled(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CoachAutoAdviceEnabled] = value
        }
    }

    suspend fun reset() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private object Keys {
        val HasCompletedOnboarding = booleanPreferencesKey("has_completed_onboarding")
        val CoachAutoAdviceEnabled = booleanPreferencesKey("coach_auto_advice_enabled")
    }
}
