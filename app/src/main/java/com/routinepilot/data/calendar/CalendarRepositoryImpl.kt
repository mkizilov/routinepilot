package com.routinepilot.data.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.room.withTransaction
import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.calendar.CalendarGateway
import com.routinepilot.domain.calendar.CalendarInfo
import com.routinepilot.domain.calendar.CalendarSettings
import com.routinepilot.domain.calendar.CalendarSource
import com.routinepilot.domain.calendar.CalendarState
import com.routinepilot.domain.calendar.CalendarStep
import com.routinepilot.domain.calendar.CalendarSyncPlan
import com.routinepilot.domain.calendar.CalendarSyncReport
import com.routinepilot.domain.calendar.CalendarSynchronizer
import com.routinepilot.domain.calendar.LinkedEvent
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.repository.CalendarRepository
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendar export. Settings live in DataStore because calendar ids belong to this device. The links live in Room, which
 * says which event belongs to which schedule. Syncs run one at a time, so the screen and the background observer never
 * write the same event twice.
 */
@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val database: RoutinePilotDatabase,
    private val dataStore: DataStore<Preferences>,
    private val gateway: CalendarGateway,
) : CalendarRepository {
    private val linkDao = database.calendarLinkDao()
    private val scheduleDao = database.scheduleDao()
    private val routineDao = database.routineDao()
    private val routineStepDao = database.routineStepDao()
    private val taskDao = database.taskDao()
    private val synchronizer = CalendarSynchronizer(gateway)
    private val mutex = Mutex()

    override fun observeSettings(): Flow<CalendarSettings> =
        dataStore.data.map { it.readSettings() }.distinctUntilChanged()

    override suspend fun updateSettings(change: (CalendarSettings) -> CalendarSettings) {
        dataStore.edit { prefs -> prefs.writeSettings(change(prefs.readSettings())) }
    }

    override fun observeLinkedEvents(): Flow<List<LinkedEvent>> = combine(
        linkDao.observeAll(),
        scheduleDao.observeActive(),
        routineDao.observeActive(),
        taskDao.observeActive(),
    ) { links, schedules, routines, tasks ->
        val routineTitles = routines.associate { it.id to it.title }
        val taskTitles = tasks.associate { it.id to it.title }
        val titles = schedules.mapNotNull { schedule ->
            val title = schedule.routineId?.let { routineTitles[it] } ?: schedule.taskId?.let { taskTitles[it] }
            title?.let { schedule.id to it }
        }.toMap()
        links.map { LinkedEvent(it.scheduleId, titles[it.scheduleId], it.status, it.eventId) }
            .sortedBy { it.title.orEmpty() }
    }

    override fun hasAccess(): Boolean = gateway.hasAccess()

    override suspend fun writableCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!gateway.hasAccess()) return@withContext emptyList()
        try {
            gateway.writableCalendars()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    override suspend fun sync(now: Instant): CalendarSyncReport = mutex.withLock { syncLocked(now) }

    override suspend fun removeAllEvents(): CalendarSyncReport = mutex.withLock {
        val links = linkDao.all().map { it.toDomain() }
        val plan = withContext(Dispatchers.IO) { synchronizer.removeAll(links) }
        if (plan.report.state == CalendarState.SYNCED) {
            applyPlan(plan)
            updateSettings { it.copy(enabled = false) }
        }
        plan.report
    }

    override suspend fun exportAgain(scheduleId: UUID, now: Instant): CalendarSyncReport = mutex.withLock {
        linkDao.deleteByScheduleIds(listOf(scheduleId))
        syncLocked(now)
    }

    private suspend fun syncLocked(now: Instant): CalendarSyncReport {
        val settings = dataStore.data.first().readSettings()
        val sources = activeSources()
        val links = linkDao.all().map { it.toDomain() }
        val plan = withContext(Dispatchers.IO) { synchronizer.sync(settings, sources, links, now) }
        applyPlan(plan)
        if (plan.report.state == CalendarState.SYNCED) {
            updateSettings { it.copy(lastSyncMillis = now.toEpochMilli()) }
        }
        return plan.report
    }

    /** Stored after the provider accepted the change, so a link never points at an event that was not written. */
    private suspend fun applyPlan(plan: CalendarSyncPlan) {
        if (plan.deletes.isEmpty() && plan.upserts.isEmpty()) return
        database.withTransaction {
            if (plan.deletes.isNotEmpty()) linkDao.deleteByScheduleIds(plan.deletes)
            linkDao.upsert(plan.upserts.map { it.toEntity() })
        }
    }

    /** Active schedules whose routine or task still exists, with the title and steps each event shows. */
    private suspend fun activeSources(): List<CalendarSource> {
        val routines = routineDao.observeActive().first().associateBy { it.id }
        val tasks = taskDao.observeActive().first().associateBy { it.id }
        return scheduleDao.activeForGeneration().mapNotNull { entity ->
            val schedule = entity.toDomain()
            when (val target = schedule.target) {
                is ScheduleTarget.RoutineTarget -> routines[target.routineId]?.let { routine ->
                    val steps = routineStepDao.activeSnapshot(routine.id)
                        .map { CalendarStep(it.title, it.durationSeconds) }
                    CalendarSource(schedule, routine.title, isTask = false, steps = steps)
                }
                is ScheduleTarget.TaskTarget -> tasks[target.taskId]?.let { task ->
                    CalendarSource(schedule, task.title, isTask = true, steps = emptyList())
                }
            }
        }
    }

    private fun Preferences.readSettings() = CalendarSettings(
        enabled = this[ENABLED] ?: false,
        calendarId = this[CALENDAR_ID],
        allDay = this[ALL_DAY] ?: false,
        lastSyncMillis = this[LAST_SYNC],
    )

    private fun MutablePreferences.writeSettings(settings: CalendarSettings) {
        this[ENABLED] = settings.enabled
        setOrRemove(CALENDAR_ID, settings.calendarId)
        this[ALL_DAY] = settings.allDay
        setOrRemove(LAST_SYNC, settings.lastSyncMillis)
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("calendar_export_enabled")
        val CALENDAR_ID = longPreferencesKey("calendar_export_calendar_id")
        val ALL_DAY = booleanPreferencesKey("calendar_export_all_day")
        val LAST_SYNC = longPreferencesKey("calendar_export_last_sync_millis")
    }
}
