package com.routinepilot.data.repository

import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.schedule.Recurrence
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.createInMemoryDatabase
import com.routinepilot.infrastructure.database.scalar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class ScheduleRepositoryTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var routines: RoutineRepositoryImpl
    private lateinit var schedules: ScheduleRepositoryImpl
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val today = LocalDate.of(2026, 3, 10)
    private val routine = Routine(UUID.randomUUID(), "Morning", 0, now)

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        routines = RoutineRepositoryImpl(db)
        schedules = ScheduleRepositoryImpl(db)
        runBlocking { routines.saveRoutine(routine, emptyList()) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveSchedule_generatesAHorizonOfPendingOccurrences() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)

            val rows = schedules.observeOccurrences(routine.id).first()

            // Today's 07:00 is already past at 12:00, so the horizon is tomorrow through today plus 14 days.
            assertEquals(ScheduleGenerator.HORIZON_DAYS.toInt(), rows.size)
            assertTrue(rows.all { it.status == OccurrenceStatus.PLANNED })
        }

    @Test
    fun ensureOccurrences_calledRepeatedly_neverDuplicatesASlot() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)
            val before = schedules.observeOccurrences(routine.id).first().size

            repeat(3) { schedules.ensureOccurrences(now, utc) }

            assertEquals(before, schedules.observeOccurrences(routine.id).first().size)
            assertEquals(
                0L,
                db.scalar(
                    "SELECT COUNT(*) - COUNT(DISTINCT scheduleId || '|' || originalScheduledAt) " +
                        "FROM scheduled_occurrences",
                ),
            )
        }

    @Test
    fun uniqueIndex_rejectsADuplicateSlotEvenIfTheSameRowIsInsertedDirectly() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)
            val row = schedules.observeOccurrences(routine.id).first().first()

            val inserted =
                db.openHelper.writableDatabase
                    .compileStatement(
                        "INSERT OR IGNORE INTO scheduled_occurrences " +
                            "(id, scheduleId, routineId, taskId, originalScheduledAt, scheduledAt, " +
                            "localDate, status, createdAt, modifiedAt) " +
                            "VALUES (?, ?, ?, NULL, ?, ?, ?, 'PLANNED', 0, 0)",
                    ).run {
                        bindString(1, UUID.randomUUID().toString())
                        bindString(2, row.scheduleId.toString())
                        bindString(3, routine.id.toString())
                        bindLong(4, row.originalScheduledAt.toEpochMilli())
                        bindLong(5, row.originalScheduledAt.toEpochMilli())
                        bindString(6, row.localDate.toString())
                        executeUpdateDelete()
                    }

            assertEquals(0, inserted)
            assertEquals(
                1L,
                db.scalar(
                    "SELECT COUNT(*) FROM scheduled_occurrences WHERE scheduleId = ? AND originalScheduledAt = ?",
                    row.scheduleId.toString(),
                    row.originalScheduledAt.toEpochMilli(),
                ),
            )
        }

    @Test
    fun deletedSlot_staysDeletedAcrossRestartLikeRegeneration() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)
            val target = schedules.observeOccurrences(routine.id).first().first()
            schedules.deleteOccurrence(target.id, now)

            schedules.ensureOccurrences(now, utc)

            assertTrue(schedules.observeOccurrences(routine.id).first().none { it.id == target.id })
            assertEquals(
                1L,
                db.scalar(
                    "SELECT COUNT(*) FROM scheduled_occurrences " +
                        "WHERE originalScheduledAt = ? AND deletedAt IS NOT NULL",
                    target.originalScheduledAt.toEpochMilli(),
                ),
            )
        }

    @Test
    fun completeSkipAndReschedule_changeStatusAndTime() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)
            val rows = schedules.observeOccurrences(routine.id).first()
            val (first, second, third) = rows

            schedules.completeOccurrence(first.id, now)
            schedules.skipOccurrence(second.id, now)
            val newTime = third.scheduledAt.plusSeconds(3_600)
            schedules.rescheduleOccurrence(third.id, newTime, utc, now)

            val byId = schedules.observeOccurrences(routine.id).first().associateBy { it.id }
            assertEquals(OccurrenceStatus.COMPLETED, byId.getValue(first.id).status)
            assertEquals(OccurrenceStatus.SKIPPED, byId.getValue(second.id).status)
            assertEquals(OccurrenceStatus.RESCHEDULED, byId.getValue(third.id).status)
            assertEquals(newTime, byId.getValue(third.id).scheduledAt)
            assertEquals(third.originalScheduledAt, byId.getValue(third.id).originalScheduledAt)
        }

    @Test
    fun completedOccurrence_isNotRemovedWhenTheRuleChanges() =
        runBlocking {
            val schedule = dailySchedule()
            schedules.saveSchedule(schedule, now)
            val done = schedules.observeOccurrences(routine.id).first().first()
            schedules.completeOccurrence(done.id, now)

            schedules.saveSchedule(
                schedule.copy(recurrence = Recurrence(RecurrencePattern.Daily, LocalTime.of(9, 0), today)),
                now.plusSeconds(60),
            )

            assertTrue(schedules.observeOccurrences(routine.id).first().any { it.id == done.id })
        }

    @Test
    fun changingWeekdays_replacesPendingSlotsWithTheNewPattern() =
        runBlocking {
            val weekdaySchedule =
                dailySchedule().copy(
                    recurrence =
                        Recurrence(
                            RecurrencePattern.Weekdays(setOf(DayOfWeek.MONDAY)),
                            LocalTime.of(7, 0),
                            today,
                        ),
                )
            schedules.saveSchedule(weekdaySchedule, now)

            val fridays = Recurrence(RecurrencePattern.Weekdays(setOf(DayOfWeek.FRIDAY)), LocalTime.of(7, 0), today)
            schedules.saveSchedule(weekdaySchedule.copy(recurrence = fridays), now.plusSeconds(60))

            val pending = schedules.observeOccurrences(routine.id).first().filter { it.status.isPending }
            assertTrue(pending.isNotEmpty())
            assertTrue(pending.all { it.localDate.dayOfWeek == DayOfWeek.FRIDAY })
        }

    @Test
    fun deleteSchedule_removesPendingButKeepsCompletedHistory() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)
            val done = schedules.observeOccurrences(routine.id).first().first()
            schedules.completeOccurrence(done.id, now)

            schedules.deleteSchedule(
                schedules
                    .observeSchedules()
                    .first()
                    .single()
                    .id,
                now,
            )

            val remaining = schedules.observeOccurrences(routine.id).first()
            assertEquals(listOf(done.id), remaining.map { it.id })
            assertTrue(schedules.observeSchedules().first().isEmpty())
        }

    @Test
    fun agenda_resolvesTitlesAndHidesItemsOfDeletedRoutines() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)
            // Today's slot is already past, so use tomorrow, which has a generated occurrence.
            val from = today.plusDays(1).atStartOfDay(utc).toInstant()
            val to = today.plusDays(2).atStartOfDay(utc).toInstant()

            assertEquals(
                listOf("Morning"),
                schedules
                    .observeAgenda(from, to)
                    .first()
                    .map { it.title }
                    .distinct(),
            )

            routines.deleteRoutine(routine.id)

            assertTrue(schedules.observeAgenda(from, to).first().isEmpty())
        }

    @Test
    fun deletingARoutine_cascadesToItsSchedules() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)

            db.routineDao().purge(routine.id)

            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM schedules"))
            assertEquals(0L, db.scalar("SELECT COUNT(*) FROM scheduled_occurrences"))
        }

    @Test
    fun statusCannotBeChangedAfterSoftDelete() =
        runBlocking {
            schedules.saveSchedule(dailySchedule(), now)
            val row = schedules.observeOccurrences(routine.id).first().first()
            schedules.deleteOccurrence(row.id, now)

            schedules.completeOccurrence(row.id, now)

            assertEquals(
                "PLANNED",
                db.openHelper.readableDatabase
                    .query("SELECT status FROM scheduled_occurrences WHERE id = ?", arrayOf(row.id.toString()))
                    .use {
                        it.moveToFirst()
                        it.getString(0)
                    },
            )
        }

    private fun dailySchedule() =
        Schedule(
            id = UUID.randomUUID(),
            target = ScheduleTarget.RoutineTarget(routine.id),
            recurrence = Recurrence(RecurrencePattern.Daily, LocalTime.of(7, 0), today),
            zone = utc,
            createdAt = now,
            modifiedAt = now,
        )
}
