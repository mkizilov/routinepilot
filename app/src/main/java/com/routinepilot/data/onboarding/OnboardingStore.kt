package com.routinepilot.data.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the first-run introduction was finished or skipped on this device. Device-local, like the other settings. */
@Singleton
class OnboardingStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val completed: Flow<Boolean> = dataStore.data.map { it[COMPLETED] ?: false }

    suspend fun markCompleted() {
        dataStore.edit { it[COMPLETED] = true }
    }

    private companion object {
        val COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
