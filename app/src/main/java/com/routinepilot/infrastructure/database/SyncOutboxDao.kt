package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.routinepilot.domain.sync.SyncType
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A record that has at least one queued change. */
data class PendingKeyRow(
    val objectType: SyncType,
    val objectId: UUID,
)

@Dao
interface SyncOutboxDao {
    @Query("SELECT DISTINCT objectType, objectId FROM mutation_outbox")
    suspend fun pendingKeys(): List<PendingKeyRow>

    @Query("SELECT COUNT(*) FROM mutation_outbox")
    suspend fun pendingTotal(): Int

    @Query("SELECT COUNT(*) FROM mutation_outbox")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMutation(mutation: MutationOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFields(fields: List<MutationFieldEntity>)

    @Query("SELECT * FROM mutation_outbox WHERE mutationId = :mutationId")
    suspend fun findMutation(mutationId: UUID): MutationOutboxEntity?

    @Query("SELECT * FROM mutation_outbox WHERE objectType = :type AND objectId = :id ORDER BY localSequence")
    suspend fun pendingFor(
        type: SyncType,
        id: UUID,
    ): List<MutationOutboxEntity>

    @Query("SELECT * FROM mutation_outbox_field WHERE localSequence = :localSequence ORDER BY fieldName")
    suspend fun fieldsOf(localSequence: Long): List<MutationFieldEntity>

    @Query("UPDATE mutation_outbox SET seenVersion = :seenVersion WHERE localSequence = :localSequence")
    suspend fun updateSeenVersion(
        localSequence: Long,
        seenVersion: Long,
    )

    @Query("DELETE FROM mutation_outbox WHERE mutationId = :mutationId")
    suspend fun deleteMutation(mutationId: UUID)

    @Query("DELETE FROM mutation_outbox WHERE objectType = :type AND objectId = :id")
    suspend fun deleteAllFor(
        type: SyncType,
        id: UUID,
    )

    @Query("SELECT * FROM sync_base WHERE objectType = :type AND objectId = :id")
    suspend fun findBase(
        type: SyncType,
        id: UUID,
    ): SyncBaseEntity?

    @Query("SELECT * FROM sync_base_field WHERE objectType = :type AND objectId = :id")
    suspend fun baseFieldsOf(
        type: SyncType,
        id: UUID,
    ): List<SyncBaseFieldEntity>

    @Upsert
    suspend fun upsertBase(base: SyncBaseEntity)

    @Query("DELETE FROM sync_base_field WHERE objectType = :type AND objectId = :id")
    suspend fun deleteBaseFields(
        type: SyncType,
        id: UUID,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBaseFields(fields: List<SyncBaseFieldEntity>)
}
