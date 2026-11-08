package com.routinepilot.data.sync

import com.routinepilot.domain.sync.BaseState
import com.routinepilot.domain.sync.FieldMap
import com.routinepilot.domain.sync.MutationKind
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.PlannedChange
import com.routinepilot.domain.sync.ServerChange
import com.routinepilot.domain.sync.SubmitResult
import com.routinepilot.domain.sync.SyncType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * The JSON wire format of the backend (backend/src/main/kotlin/.../api/Dto.kt). Field names match the
 * server's names. The
 * app keeps every field as text, so [toPayload] and [toFieldMap] convert the few fields the server stores as numbers or
 * booleans.
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

private val NUMERIC_FIELDS = setOf("position", "durationSeconds", "weekdaysMask", "intervalDays", "timeOfDayMinutes")
private val BOOLEAN_FIELDS = setOf("isCompleted")

@Serializable
internal data class LoginRequest(val email: String, val password: String)

@Serializable
internal data class TokenDto(val token: String, val expiresAt: String)

@Serializable
internal data class MeDto(val id: String, val email: String)

@Serializable
internal data class DeviceRequest(val name: String, val platform: String)

@Serializable
internal data class DeviceDto(val id: String)

@Serializable
internal data class DeviceRegisteredDto(val device: DeviceDto, val token: String, val expiresAt: String)

@Serializable
internal data class ErrorDetailDto(val code: String, val message: String)

@Serializable
internal data class ErrorBodyDto(val error: ErrorDetailDto)

@Serializable
internal data class ChangeInputDto(
    val changeId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseVersion: Long,
    val payload: JsonObject? = null,
)

@Serializable
internal data class UploadDto(val changes: List<ChangeInputDto>)

@Serializable
internal data class ResultDto(
    val changeId: String,
    val status: String,
    val version: Long? = null,
    val seq: Long? = null,
    val serverVersion: Long? = null,
    val serverDeleted: Boolean? = null,
    val serverPayload: JsonObject? = null,
    val reason: String? = null,
)

@Serializable
internal data class UploadResponseDto(val results: List<ResultDto>)

@Serializable
internal data class ChangeRecordDto(
    val seq: Long,
    val changeId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val version: Long,
    val payload: JsonObject? = null,
)

@Serializable
internal data class ChangePageDto(val changes: List<ChangeRecordDto>, val cursor: Long, val hasMore: Boolean)

internal fun SyncType.wireName(): String = when (this) {
    SyncType.ROUTINE -> "routine"
    SyncType.ROUTINE_STEP -> "routine_step"
    SyncType.TASK -> "task"
    SyncType.SCHEDULE -> "schedule"
}

/** Null for entity types the app does not sync, such as the server's preference records. */
internal fun syncTypeOf(wire: String): SyncType? = SyncType.entries.firstOrNull { it.wireName() == wire }

internal fun PlannedChange.toWire(): ChangeInputDto = ChangeInputDto(
    changeId = mutationId.toString(),
    entityType = key.type.wireName(),
    entityId = key.id.toString(),
    operation = if (kind == MutationKind.DELETE) "DELETE" else "UPSERT",
    baseVersion = baseVersion,
    payload = payload?.toPayload(),
)

/** A delete has no payload, and the server rejects one, so [toPayload] is only called for upserts. */
internal fun FieldMap.toPayload(): JsonObject = buildJsonObject {
    for ((name, value) in this@toPayload) put(name, valueOf(name, value))
}

private fun valueOf(name: String, value: String?): JsonElement = when {
    value == null -> JsonNull
    name in NUMERIC_FIELDS -> JsonPrimitive(value.toLong())
    name in BOOLEAN_FIELDS -> JsonPrimitive(value.toBooleanStrict())
    else -> JsonPrimitive(value)
}

internal fun JsonObject.toFieldMap(): FieldMap = mapValues { (_, element) ->
    when (element) {
        is JsonNull -> null
        is JsonPrimitive -> element.content
        else -> error("A synced field must be a single value")
    }
}

internal fun ChangeRecordDto.toServerChange(): ServerChange? {
    val type = syncTypeOf(entityType) ?: return null
    val deleted = operation == "DELETE"
    return ServerChange(
        seq = seq,
        changeId = UUID.fromString(changeId),
        key = ObjectKey(type, UUID.fromString(entityId)),
        version = version,
        deleted = deleted,
        fields = if (deleted) emptyMap() else payload?.toFieldMap().orEmpty(),
    )
}

internal fun ResultDto.toSubmitResult(): SubmitResult = when (status) {
    "ACCEPTED" -> SubmitResult.Accepted(checkNotNull(version), duplicate = false)
    "DUPLICATE" -> SubmitResult.Accepted(checkNotNull(version), duplicate = true)
    "CONFLICT" -> SubmitResult.Conflict(
        BaseState(
            version = checkNotNull(serverVersion),
            deleted = serverDeleted == true,
            fields = serverPayload?.toFieldMap().orEmpty(),
        ),
    )
    "REJECTED" -> SubmitResult.Rejected(reason ?: "The server refused this change")
    else -> error("Unknown sync result: $status")
}
