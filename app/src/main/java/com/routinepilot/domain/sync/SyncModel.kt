package com.routinepilot.domain.sync

import java.util.UUID

/** A record's values in canonical text form. A null value is an empty field. */
typealias FieldMap = Map<String, String?>

/**
 * The record types that take part in offline tracking. [fields] lists every value tracked for the type, so a change to
 * any of them is a change to the record. Timestamps that only say when a device saved are not tracked.
 */
enum class SyncType(
    val fields: List<String>,
) {
    ROUTINE(listOf("title", "description", "position")),
    ROUTINE_STEP(listOf("routineId", "title", "description", "durationSeconds", "position")),
    TASK(listOf("title", "isCompleted", "routineId", "position")),
    SCHEDULE(
        listOf(
            "routineId",
            "taskId",
            "kind",
            "weekdaysMask",
            "intervalDays",
            "timeOfDayMinutes",
            "startDate",
            "zoneId",
        ),
    ),
}

/** The stable identity of a record. The id never changes, even when the record is renamed or moved. */
data class ObjectKey(
    val type: SyncType,
    val id: UUID,
)

/**
 * What the server last reported for one record. [version] 0 means the server has never reported the record. A tombstone
 * keeps its version and has no fields, so a later write can still be checked against it.
 */
data class BaseState(
    val version: Long,
    val deleted: Boolean,
    val fields: FieldMap,
) {
    companion object {
        val NEVER_SEEN = BaseState(version = 0, deleted = false, fields = emptyMap())
    }
}

data class FieldChange(
    val before: String?,
    val after: String?,
)

enum class MutationKind { UPSERT, DELETE }

/**
 * PRESENT: an edit to a record this device knows to be live, so only the changed fields are sent for merging.
 * ABSENT: a create, a restore, or an edit to a record the server has not reported, which writes the whole record.
 * Only UPSERT uses this.
 */
enum class Expectation { PRESENT, ABSENT }

/**
 * One local change in the outbox. [mutationId] is also the change id sent to the server. [seenVersion] is the server
 * version this device's view was built on when the change was recorded, and it is used only to detect conflicts.
 * [localSequence] orders this device's own changes. [createdAtMillis] is informational. No merge decision reads it,
 * because device clocks disagree.
 */
data class Mutation(
    val mutationId: UUID,
    val key: ObjectKey,
    val localSequence: Long,
    val seenVersion: Long,
    val kind: MutationKind,
    val expectation: Expectation,
    val snapshot: FieldMap,
    val changes: Map<String, FieldChange>,
    val createdAtMillis: Long,
)

/**
 * A change the server accepted. [seq] is its position in the server's log. Only that order decides merges.
 */
data class ServerChange(
    val seq: Long,
    val changeId: UUID,
    val key: ObjectKey,
    val version: Long,
    val deleted: Boolean,
    val fields: FieldMap,
)
