package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

/** A task the user chose to plan on a day. Planning settings are device-local and are not synced. */
@Entity(tableName = "planning_task")
data class PlanningTaskEntity(
    @PrimaryKey val taskId: UUID,
    val durationMinutes: Int,
    val priority: Int,
    val deadlineMinute: Int?,
)

/** [taskId] must finish before [dependsOnTaskId] starts. */
@Entity(tableName = "planning_dependency", primaryKeys = ["taskId", "dependsOnTaskId"])
data class PlanningDependencyEntity(
    val taskId: UUID,
    val dependsOnTaskId: UUID,
)

/**
 * A block in the applied plan for one day. Pinned blocks are the user's manual overrides.
 * The others are
 * recommendations
 * that a later plan may move.
 */
@Entity(tableName = "planned_block", primaryKeys = ["planDate", "taskId"])
data class PlannedBlockEntity(
    val planDate: LocalDate,
    val taskId: UUID,
    val startMinute: Int,
    val endMinute: Int,
    val pinned: Boolean,
)
