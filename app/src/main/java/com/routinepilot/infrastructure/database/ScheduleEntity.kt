package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.routinepilot.domain.schedule.RecurrenceKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["routineId"], name = "index_schedules_routineId"),
        Index(value = ["taskId"], name = "index_schedules_taskId"),
    ],
)
data class ScheduleEntity(
    @PrimaryKey val id: UUID,
    val routineId: UUID?,
    val taskId: UUID?,
    val kind: RecurrenceKind,
    val weekdaysMask: Int?,
    val intervalDays: Int?,
    val timeOfDayMinutes: Int,
    val startDate: LocalDate,
    val zoneId: ZoneId,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
)
