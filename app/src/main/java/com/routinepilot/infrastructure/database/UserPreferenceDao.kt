package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface UserPreferenceDao {
    @Query("SELECT * FROM user_preferences WHERE name = :name AND deletedAt IS NULL")
    fun observeActiveByName(name: String): Flow<UserPreferenceEntity?>

    /** Includes tombstoned rows so a re-set reuses the row that owns the unique name. */
    @Query("SELECT * FROM user_preferences WHERE name = :name")
    suspend fun findByName(name: String): UserPreferenceEntity?

    @Upsert
    suspend fun upsert(preference: UserPreferenceEntity)

    @Query(
        "UPDATE user_preferences SET deletedAt = :at, modifiedAt = :at " +
            "WHERE name = :name AND deletedAt IS NULL",
    )
    suspend fun markDeletedByName(name: String, at: Instant)
}
