package com.routinepilot.domain.repository

import kotlinx.coroutines.flow.Flow

interface PreferenceRepository {
    fun observePreference(name: String): Flow<String?>

    suspend fun setPreference(name: String, value: String)

    suspend fun removePreference(name: String)
}
