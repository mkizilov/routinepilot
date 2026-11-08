package com.routinepilot.data.repository

import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.model.Task
import com.routinepilot.domain.repository.TaskRepository
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class TaskRepositoryImpl @Inject constructor(
    database: RoutinePilotDatabase,
) : TaskRepository {
    private val taskDao = database.taskDao()

    override fun observeTasks(): Flow<List<Task>> =
        taskDao.observeActive().map { tasks -> tasks.map { it.toDomain() } }

    override suspend fun saveTask(task: Task) {
        taskDao.upsert(task.toEntity(Instant.now()))
    }

    override suspend fun deleteTask(id: UUID) {
        taskDao.markDeleted(id, Instant.now())
    }
}
