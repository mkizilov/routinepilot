package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface CalendarLinkDao {
    @Query("SELECT * FROM calendar_links")
    fun observeAll(): Flow<List<CalendarLinkEntity>>

    @Query("SELECT * FROM calendar_links")
    suspend fun all(): List<CalendarLinkEntity>

    @Upsert
    suspend fun upsert(links: List<CalendarLinkEntity>)

    @Query("DELETE FROM calendar_links WHERE scheduleId IN (:scheduleIds)")
    suspend fun deleteByScheduleIds(scheduleIds: List<UUID>)
}
