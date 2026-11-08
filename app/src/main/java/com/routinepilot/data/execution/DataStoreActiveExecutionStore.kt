package com.routinepilot.data.execution

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.execution.ExecutionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class DataStoreActiveExecutionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ActiveExecutionStore {
    override fun observe(): Flow<ActiveExecution?> = dataStore.data.map { it.toActiveExecution() }

    override suspend fun save(execution: ActiveExecution) {
        dataStore.edit { prefs ->
            prefs[SESSION_ID] = execution.sessionId.toString()
            prefs[ROUTINE_ID] = execution.routineId.toString()
            prefs[STARTED_AT_MILLIS] = execution.startedAt.toEpochMilli()
            prefs[STEP_IDS] = execution.stepIds.joinToString(SEPARATOR) { it.toString() }
            prefs[STEP_INDEX] = execution.stepIndex
            prefs[ELAPSED_MILLIS] = execution.elapsedMillis
            prefs[STATUS] = execution.status.name
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(SESSION_ID)
            prefs.remove(ROUTINE_ID)
            prefs.remove(STARTED_AT_MILLIS)
            prefs.remove(STEP_IDS)
            prefs.remove(STEP_INDEX)
            prefs.remove(ELAPSED_MILLIS)
            prefs.remove(STATUS)
        }
    }

    /** A snapshot with any missing or unreadable field is treated as no active run. */
    private fun Preferences.toActiveExecution(): ActiveExecution? {
        val sessionId = this[SESSION_ID]?.toUuidOrNull() ?: return null
        val routineId = this[ROUTINE_ID]?.toUuidOrNull() ?: return null
        val startedAt = this[STARTED_AT_MILLIS] ?: return null
        val stepIds = this[STEP_IDS]?.split(SEPARATOR)?.map { it.toUuidOrNull() ?: return null } ?: return null
        val stepIndex = this[STEP_INDEX] ?: return null
        val elapsedMillis = this[ELAPSED_MILLIS] ?: return null
        val status = this[STATUS]?.let { name -> ExecutionStatus.entries.firstOrNull { it.name == name } }
            ?: return null
        return ActiveExecution(
            sessionId = sessionId,
            routineId = routineId,
            startedAt = Instant.ofEpochMilli(startedAt),
            stepIds = stepIds,
            stepIndex = stepIndex,
            elapsedMillis = elapsedMillis,
            status = status,
        )
    }

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private companion object {
        const val SEPARATOR = ","
        val SESSION_ID = stringPreferencesKey("active_execution_session_id")
        val ROUTINE_ID = stringPreferencesKey("active_execution_routine_id")
        val STARTED_AT_MILLIS = longPreferencesKey("active_execution_started_at_millis")
        val STEP_IDS = stringPreferencesKey("active_execution_step_ids")
        val STEP_INDEX = intPreferencesKey("active_execution_step_index")
        val ELAPSED_MILLIS = longPreferencesKey("active_execution_elapsed_millis")
        val STATUS = stringPreferencesKey("active_execution_status")
    }
}
