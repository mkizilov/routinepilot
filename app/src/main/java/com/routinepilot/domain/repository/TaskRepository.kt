package com.routinepilot.domain.repository

import com.routinepilot.domain.model.Task
import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface TaskRepository {
    fun observeTasks(): Flow<List<Task>>

    suspend fun saveTask(task: Task)

    suspend fun deleteTask(id: UUID)
}
