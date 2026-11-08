package com.routinepilot.backend.domain

import com.routinepilot.backend.config.SyncConfig
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The kinds of record that sync. The wire name is what clients send. Each kind lists the payload fields the server
 * checks, so a malformed record is rejected before it is stored.
 */
enum class EntityType(val wireName: String, private val fields: List<FieldRule>) {
    ROUTINE("routine", listOf(FieldRule.Text("title", 200))),
    ROUTINE_STEP(
        "routine_step",
        listOf(
            FieldRule.Uuid("routineId"),
            FieldRule.Text("title", 200),
            FieldRule.Whole("durationSeconds", 0, 86_400, optional = true),
        ),
    ),
    TASK("task", listOf(FieldRule.Text("title", 200))),
    SCHEDULE(
        "schedule",
        listOf(
            FieldRule.Uuid("routineId", optional = true),
            FieldRule.Uuid("taskId", optional = true),
            FieldRule.Choice("kind", setOf("DAILY", "WEEKDAYS", "EVERY_N_DAYS")),
            FieldRule.Whole("timeOfDayMinutes", 0, 1_439),
            FieldRule.Day("startDate"),
            FieldRule.Zone("zoneId"),
        ),
    ),
    PREFERENCE("preference", listOf(FieldRule.Text("name", 100), FieldRule.Text("value", 1_000))),
    ;

    fun validatePayload(payload: JsonObject) {
        fields.forEach { it.checkField(payload) }
        if (this == SCHEDULE && payload.present("routineId") == payload.present("taskId")) {
            throw invalid("routineId", "Set exactly one of routineId or taskId.")
        }
    }

    companion object {
        fun fromWire(value: String): EntityType? = entries.firstOrNull { it.wireName == value }
    }
}

private fun JsonObject.present(name: String): Boolean = this[name]?.let { it !is JsonNull } ?: false

sealed interface FieldRule {
    val name: String
    val optional: Boolean

    data class Text(override val name: String, val maxLength: Int, override val optional: Boolean = false) : FieldRule

    data class Uuid(override val name: String, override val optional: Boolean = false) : FieldRule

    data class Whole(
        override val name: String,
        val min: Int,
        val max: Int,
        override val optional: Boolean = false,
    ) : FieldRule

    data class Choice(override val name: String, val allowed: Set<String>, override val optional: Boolean = false) :
        FieldRule

    data class Day(override val name: String, override val optional: Boolean = false) : FieldRule

    data class Zone(override val name: String, override val optional: Boolean = false) : FieldRule
}

internal fun FieldRule.checkField(payload: JsonObject) {
    val element = payload[name]
    if (element == null || element is JsonNull) {
        if (optional) return
        throw invalid(name, "Required.")
    }
    val value = element as? JsonPrimitive ?: throw invalid(name, "Must be a single value.")
    when (this) {
        is FieldRule.Text -> {
            val text = textOf(value)
            if (text.isBlank() || text.length > maxLength) throw invalid(name, "Must be 1 to $maxLength characters.")
        }
        is FieldRule.Uuid -> Validation.uuid(textOf(value), name)
        is FieldRule.Whole -> {
            val number = if (value.isString) null else value.intOrNull
            if (number == null || number !in min..max) throw invalid(name, "Must be a whole number from $min to $max.")
        }
        is FieldRule.Choice -> {
            if (textOf(value) !in allowed) throw invalid(name, "Must be one of ${allowed.sorted().joinToString()}.")
        }
        is FieldRule.Day -> {
            try {
                LocalDate.parse(textOf(value))
            } catch (failure: DateTimeException) {
                throw invalid(name, "Must be a date like 2026-03-10.")
            }
        }
        is FieldRule.Zone -> {
            try {
                ZoneId.of(textOf(value))
            } catch (failure: DateTimeException) {
                throw invalid(name, "Must be an IANA time zone ID.")
            }
        }
    }
}

private fun FieldRule.textOf(value: JsonPrimitive): String {
    if (!value.isString) throw invalid(name, "Must be text.")
    return value.content
}

enum class Operation { UPSERT, DELETE }

/** One change as the client sent it, before validation. */
data class RawChange(
    val changeId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseVersion: Long,
    val payload: JsonObject?,
)

data class ChangeInput(
    val changeId: UUID,
    val entityType: EntityType,
    val entityId: UUID,
    val operation: Operation,
    val baseVersion: Long,
    val payload: JsonObject?,
)

object SyncValidator {
    fun validateBatch(changes: List<RawChange>, limits: SyncConfig): List<ChangeInput> {
        if (changes.isEmpty()) throw invalid("changes", "Send at least one change.")
        if (changes.size > limits.maxBatchChanges) {
            throw invalid("changes", "Send at most ${limits.maxBatchChanges} changes at a time.")
        }
        return changes.mapIndexed { index, change -> validateChange(index, change, limits) }
    }

    private fun validateChange(index: Int, change: RawChange, limits: SyncConfig): ChangeInput {
        val path = "changes[$index]"
        val changeId = Validation.uuid(change.changeId, "$path.changeId")
        val entityType = EntityType.fromWire(change.entityType)
            ?: throw invalid("$path.entityType", "Unknown entity type.")
        val entityId = Validation.uuid(change.entityId, "$path.entityId")
        val operation = Operation.entries.firstOrNull { it.name == change.operation }
            ?: throw invalid("$path.operation", "Must be UPSERT or DELETE.")
        if (change.baseVersion < 0) throw invalid("$path.baseVersion", "Must not be negative.")
        val payload = when (operation) {
            Operation.UPSERT -> {
                val body = change.payload ?: throw invalid("$path.payload", "Required for UPSERT.")
                if (body.toString().toByteArray(Charsets.UTF_8).size > limits.maxPayloadBytes) {
                    throw invalid("$path.payload", "Payload is too large.")
                }
                entityType.validatePayload(body)
                body
            }
            Operation.DELETE -> {
                if (change.payload != null) throw invalid("$path.payload", "Must be omitted for DELETE.")
                null
            }
        }
        return ChangeInput(changeId, entityType, entityId, operation, change.baseVersion, payload)
    }
}

/**
 * A write applies only when the client saw the current version. Anything else is a conflict. The server never
 * overwrites a version the client has not seen.
 */
object SyncDecider {
    sealed interface Decision {
        data class Apply(val version: Long) : Decision

        data class Conflict(val serverVersion: Long) : Decision
    }

    fun decide(serverVersion: Long, baseVersion: Long): Decision =
        if (baseVersion == serverVersion) Decision.Apply(serverVersion + 1) else Decision.Conflict(serverVersion)
}

/** The current state of one record. A deleted record keeps its version, so a later write can still conflict with it. */
data class EntityHead(
    val entityType: EntityType,
    val entityId: UUID,
    val version: Long,
    val deleted: Boolean,
    val payload: JsonObject?,
    val lastChangeSeq: Long,
    val updatedAt: Instant,
)

data class NewChange(
    val changeId: UUID,
    val deviceId: UUID,
    val entityType: EntityType,
    val entityId: UUID,
    val operation: Operation,
    val baseVersion: Long,
    val version: Long,
    val payload: JsonObject?,
    val createdAt: Instant,
)

/** One accepted change in the log. [seq] is the download cursor. */
data class ChangeRecord(
    val seq: Long,
    val changeId: UUID,
    val deviceId: UUID?,
    val entityType: EntityType,
    val entityId: UUID,
    val operation: Operation,
    val baseVersion: Long,
    val version: Long,
    val payload: JsonObject?,
    val createdAt: Instant,
)

sealed interface ChangeOutcome {
    val changeId: UUID
    val entityType: EntityType
    val entityId: UUID

    data class Accepted(
        override val changeId: UUID,
        override val entityType: EntityType,
        override val entityId: UUID,
        val version: Long,
        val seq: Long,
        val duplicate: Boolean,
    ) : ChangeOutcome

    data class Conflict(
        override val changeId: UUID,
        override val entityType: EntityType,
        override val entityId: UUID,
        val serverVersion: Long,
        val serverDeleted: Boolean,
        val serverPayload: JsonObject?,
    ) : ChangeOutcome

    data class Rejected(
        override val changeId: UUID,
        override val entityType: EntityType,
        override val entityId: UUID,
        val reason: String,
    ) : ChangeOutcome
}
