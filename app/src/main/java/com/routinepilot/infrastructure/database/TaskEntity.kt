package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["routineId"], name = "index_tasks_routineId")],
)
data class TaskEntity(
    @PrimaryKey val id: UUID,
    val title: String,
    val isCompleted: Boolean,
    val routineId: UUID?,
    val position: Int,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
)
