package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.routinepilot.domain.sync.Expectation
import com.routinepilot.domain.sync.MutationKind
import com.routinepilot.domain.sync.SyncType
import java.util.UUID

/**
 * A queued local change. [localSequence] is this device's order for its own changes. [mutationId] is unique, so
 * storing the same change twice is ignored.
 */
@Entity(
    tableName = "mutation_outbox",
    indices = [
        Index(value = ["mutationId"], name = "index_mutation_outbox_mutationId", unique = true),
        Index(value = ["objectType", "objectId"], name = "index_mutation_outbox_objectType_objectId"),
    ],
)
data class MutationOutboxEntity(
    @PrimaryKey(autoGenerate = true) val localSequence: Long = 0,
    val mutationId: UUID,
    val objectType: SyncType,
    val objectId: UUID,
    val seenVersion: Long,
    val kind: MutationKind,
    val expectation: Expectation,
    val createdAtMillis: Long,
)
