package com.routinepilot.infrastructure.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import java.time.Instant
import java.util.UUID

internal val TEST_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal fun createInMemoryDatabase(): RoutinePilotDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RoutinePilotDatabase::class.java)
        .addCallback(ForeignKeysCallback)
        .build()

internal fun RoutinePilotDatabase.scalar(
    sql: String,
    vararg args: Any,
): Long =
    openHelper.readableDatabase.query(sql, args).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

internal fun newRoutine(
    title: String = "Morning",
    position: Int = 0,
    id: UUID = UUID.randomUUID(),
) = Routine(id, title, position, TEST_TIME)

internal fun newStep(
    routineId: UUID,
    title: String,
    position: Int,
    id: UUID = UUID.randomUUID(),
) = RoutineStep(id, routineId, title, position, TEST_TIME)
