package com.routinepilot.domain.repository

import com.routinepilot.domain.planning.PlanRequest
import com.routinepilot.domain.planning.PlanResult
import com.routinepilot.domain.planning.TimeRange
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The working day and breaks. They apply to every planned day on this device. */
data class PlanningSettings(
    val workday: TimeRange,
    val breaks: List<TimeRange>,
)

/** A task the user chose to plan. [dependsOn] lists tasks that must be finished first. */
data class PlanningTaskSetting(
    val taskId: UUID,
    val durationMinutes: Int,
    val priority: Int,
    val deadlineMinute: Int?,
    val dependsOn: Set<UUID>,
)

interface PlanningRepository {
    fun observeSettings(): Flow<PlanningSettings>

    suspend fun saveSettings(settings: PlanningSettings)

    fun observeTaskSettings(): Flow<List<PlanningTaskSetting>>

    suspend fun saveTaskSetting(setting: PlanningTaskSetting)

    suspend fun removeTaskSetting(taskId: UUID)

    /**
     * Builds the request for [date]: the pending items already fixed that day, the planned tasks that are not fixed,
     * the
     * recommendations of the last applied plan, and the given manual [pins]. Nothing is written.
     */
    suspend fun buildRequest(
        date: LocalDate,
        zone: ZoneId,
        pins: Map<UUID, Int>,
    ): PlanRequest

    /** The manual pins saved with the applied plan for [date]. */
    suspend fun storedPins(date: LocalDate): Map<UUID, Int>

    /** Replaces the applied plan for [date] with [result]. Pinned blocks are kept as manual overrides. */
    suspend fun applyPlan(
        date: LocalDate,
        result: PlanResult,
    )
}
