package com.routinepilot.data.planning

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.routinepilot.domain.planning.TimeRange
import com.routinepilot.domain.repository.PlanningSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Working hours and breaks, stored on this device. Breaks are kept as "start-end" minutes separated by semicolons. */
@Singleton
class PlanningSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    fun observe(): Flow<PlanningSettings> = dataStore.data.map { prefs ->
        PlanningSettings(
            workday = TimeRange(prefs[START] ?: DEFAULT_START, prefs[END] ?: DEFAULT_END),
            breaks = decodeBreaks(prefs[BREAKS].orEmpty()),
        )
    }

    suspend fun current(): PlanningSettings = observe().first()

    suspend fun save(settings: PlanningSettings) {
        dataStore.edit { prefs ->
            prefs[START] = settings.workday.start
            prefs[END] = settings.workday.end
            prefs[BREAKS] = settings.breaks.joinToString(SEPARATOR) { "${it.start}$RANGE_SEPARATOR${it.end}" }
        }
    }

    private fun decodeBreaks(encoded: String): List<TimeRange> = encoded.split(SEPARATOR)
        .filter { it.isNotBlank() }
        .map { part ->
            val (start, end) = part.split(RANGE_SEPARATOR)
            TimeRange(start.toInt(), end.toInt())
        }

    private companion object {
        val START = intPreferencesKey("planning_workday_start")
        val END = intPreferencesKey("planning_workday_end")
        val BREAKS = stringPreferencesKey("planning_breaks")
        const val DEFAULT_START = 9 * 60
        const val DEFAULT_END = 18 * 60
        const val SEPARATOR = ";"
        const val RANGE_SEPARATOR = "-"
    }
}
