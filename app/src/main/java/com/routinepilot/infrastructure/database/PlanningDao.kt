package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

@Dao
interface PlanningDao {
    @Query("SELECT * FROM planning_task")
    fun observeTasks(): Flow<List<PlanningTaskEntity>>

    @Query("SELECT * FROM planning_task")
    suspend fun tasks(): List<PlanningTaskEntity>

    @Upsert
    suspend fun upsertTask(task: PlanningTaskEntity)

    @Query("DELETE FROM planning_task WHERE taskId = :taskId")
    suspend fun deleteTask(taskId: UUID)

    @Query("SELECT * FROM planning_dependency")
    fun observeDependencies(): Flow<List<PlanningDependencyEntity>>

    @Query("SELECT * FROM planning_dependency")
    suspend fun dependencies(): List<PlanningDependencyEntity>

    @Query("DELETE FROM planning_dependency WHERE taskId = :taskId")
    suspend fun deletePrerequisitesOf(taskId: UUID)

    @Query("DELETE FROM planning_dependency WHERE taskId = :taskId OR dependsOnTaskId = :taskId")
    suspend fun deleteDependenciesInvolving(taskId: UUID)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDependencies(dependencies: List<PlanningDependencyEntity>)

    @Query("SELECT * FROM planned_block WHERE planDate = :date")
    suspend fun blocksOn(date: LocalDate): List<PlannedBlockEntity>

    @Query("DELETE FROM planned_block WHERE planDate = :date")
    suspend fun clearBlocks(date: LocalDate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<PlannedBlockEntity>)
}
