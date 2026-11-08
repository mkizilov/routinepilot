package com.routinepilot.infrastructure.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One field of a queued change. Each change keeps its whole snapshot as well as the fields that differ, so a merge can
 * tell what changed without parsing text.
 */
@Entity(
    tableName = "mutation_outbox_field",
    foreignKeys = [
        ForeignKey(
            entity = MutationOutboxEntity::class,
            parentColumns = ["localSequence"],
            childColumns = ["localSequence"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["localSequence"], name = "index_mutation_outbox_field_localSequence")],
)
data class MutationFieldEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localSequence: Long,
    val fieldName: String,
    val beforeValue: String?,
    val afterValue: String?,
    val changed: Boolean,
)
