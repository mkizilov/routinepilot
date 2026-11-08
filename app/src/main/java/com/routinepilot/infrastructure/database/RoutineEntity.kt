package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: UUID,
    val title: String,
    val position: Int,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
)
