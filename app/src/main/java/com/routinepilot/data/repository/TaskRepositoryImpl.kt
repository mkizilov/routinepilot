package com.routinepilot.data.repository

import androidx.room.withTransaction
import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.data.sync.RoomOutboxStore
import com.routinepilot.data.sync.SyncFieldMapping
import com.routinepilot.domain.model.Task
import com.routinepilot.domain.repository.TaskRepository
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.OfflineReplica
import com.routinepilot.domain.sync.SyncType
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class TaskRepositoryImpl
    @Inject
    constructor(
        private val database: RoutinePilotDatabase,
    ) : TaskRepository {
        private val taskDao = database.taskDao()
        private val replica = OfflineReplica(RoomOutboxStore(database.syncOutboxDao()))

        override fun observeTasks(): Flow<List<Task>> =
            taskDao.observeActive().map { tasks -> tasks.map { it.toDomain() } }

        override suspend fun saveTask(task: Task) {
            val now = Instant.now()
            database.withTransaction {
                val before = taskDao.findActive(task.id)?.toDomain()
                taskDao.upsert(task.toEntity(now))
                replica.record(
                    ObjectKey(SyncType.TASK, task.id),
                    before?.let { SyncFieldMapping.task(it) },
                    SyncFieldMapping.task(task),
                    now.toEpochMilli(),
                )
            }
        }

        override suspend fun deleteTask(id: UUID) {
            val now = Instant.now()
            database.withTransaction {
                val before = taskDao.findActive(id)?.toDomain()
                taskDao.markDeleted(id, now)
                replica.record(
                    ObjectKey(SyncType.TASK, id),
                    before?.let { SyncFieldMapping.task(it) },
                    null,
                    now.toEpochMilli(),
                )
            }
        }
    }
