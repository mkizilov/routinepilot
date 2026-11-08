package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

@Entity(
    tableName = "user_preferences",
    indices = [Index(value = ["name"], name = "index_user_preferences_name", unique = true)],
)
data class UserPreferenceEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val value: String,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val deletedAt: Instant? = null,
)
