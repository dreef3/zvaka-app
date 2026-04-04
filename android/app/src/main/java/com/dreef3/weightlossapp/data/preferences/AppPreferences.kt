package com.dreef3.weightlossapp.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.dreef3.weightlossapp.inference.CalorieEstimationModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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

    val calorieEstimationModel: Flow<CalorieEstimationModel> = context.dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs ->
            prefs[Keys.CalorieEstimationModel]?.let(CalorieEstimationModel::fromStorageKey)
                ?: CalorieEstimationModel.Gemma
        }

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

    suspend fun setCalorieEstimationModel(value: CalorieEstimationModel) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CalorieEstimationModel] = value.storageKey
        }
    }

    suspend fun readCalorieEstimationModel(): CalorieEstimationModel =
        calorieEstimationModel.map { it }.catch {
            if (it is IOException) emit(CalorieEstimationModel.Gemma) else throw it
        }.first()

    suspend fun reset() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private object Keys {
        val HasCompletedOnboarding = booleanPreferencesKey("has_completed_onboarding")
        val CoachAutoAdviceEnabled = booleanPreferencesKey("coach_auto_advice_enabled")
        val CalorieEstimationModel = stringPreferencesKey("calorie_estimation_model")
    }
}
