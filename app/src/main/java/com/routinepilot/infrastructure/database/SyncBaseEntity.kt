package com.routinepilot.infrastructure.database

import androidx.room.Entity
import com.routinepilot.domain.sync.SyncType
import java.util.UUID

/**
 * The last state the server reported for one record. [deleted] is the server's tombstone. Rows are kept, because the
 * tombstone's version is what a later delete or edit is checked against.
 */
@Entity(tableName = "sync_base", primaryKeys = ["objectType", "objectId"])
data class SyncBaseEntity(
    val objectType: SyncType,
    val objectId: UUID,
    val version: Long,
    val deleted: Boolean,
)
