package com.routinepilot.domain.repository

import com.routinepilot.domain.model.Task
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun observeTasks(): Flow<List<Task>>

    suspend fun saveTask(task: Task)

    suspend fun deleteTask(id: UUID)
}
