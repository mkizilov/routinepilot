package com.routinepilot.infrastructure.database

import androidx.room.Entity
import com.routinepilot.domain.sync.SyncType
import java.util.UUID

/** One field of the server's last state for a record. */
@Entity(tableName = "sync_base_field", primaryKeys = ["objectType", "objectId", "fieldName"])
data class SyncBaseFieldEntity(
    val objectType: SyncType,
    val objectId: UUID,
    val fieldName: String,
    val fieldValue: String?,
)
