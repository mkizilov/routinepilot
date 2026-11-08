package com.routinepilot.backend.api

import com.routinepilot.backend.domain.ChangeOutcome
import com.routinepilot.backend.domain.ChangeRecord
import com.routinepilot.backend.domain.Device
import com.routinepilot.backend.domain.EntityHead
import com.routinepilot.backend.domain.IssuedSession
import com.routinepilot.backend.domain.Operation
import com.routinepilot.backend.domain.RawChange
import com.routinepilot.backend.domain.RegisteredDevice
import com.routinepilot.backend.domain.UserAccount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Wire types. They are separate from the domain types, so the API can change without touching the rules. */
@Serializable
data class HealthResponse(val status: String)

@Serializable
data class CredentialsRequest(val email: String, val password: String)

@Serializable
data class TokenResponse(val token: String, val tokenType: String = "Bearer", val expiresAt: String)

@Serializable
data class UserResponse(val id: String, val email: String, val createdAt: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class DeleteAccountRequest(val password: String)

@Serializable
data class DeviceRequest(val name: String, val platform: String)

@Serializable
data class DeviceResponse(val id: String, val name: String, val platform: String, val createdAt: String)

@Serializable
data class DeviceRegisteredResponse(
    val device: DeviceResponse,
    val token: String,
    val tokenType: String = "Bearer",
    val expiresAt: String,
)

@Serializable
data class DeviceListResponse(val devices: List<DeviceResponse>)

@Serializable
data class ChangeInputDto(
    val changeId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseVersion: Long,
    val payload: JsonObject? = null,
)

@Serializable
data class SyncUploadRequest(val changes: List<ChangeInputDto>)

/**
 * status is ACCEPTED, DUPLICATE (a repeat of an accepted change), CONFLICT, or REJECTED. A conflict carries the
 * server's current version and payload, so the device can decide what to keep.
 */
@Serializable
data class ChangeResultDto(
    val changeId: String,
    val status: String,
    val entityType: String,
    val entityId: String,
    val version: Long? = null,
    val seq: Long? = null,
    val serverVersion: Long? = null,
    val serverDeleted: Boolean? = null,
    val serverPayload: JsonObject? = null,
    val reason: String? = null,
)

@Serializable
data class SyncUploadResponse(val results: List<ChangeResultDto>)

@Serializable
data class ChangeRecordDto(
    val seq: Long,
    val changeId: String,
    val deviceId: String?,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseVersion: Long,
    val version: Long,
    val payload: JsonObject?,
    val createdAt: String,
)

@Serializable
data class ChangePageResponse(val changes: List<ChangeRecordDto>, val cursor: Long, val hasMore: Boolean)

@Serializable
data class RecordDto(
    val entityType: String,
    val entityId: String,
    val version: Long,
    val payload: JsonObject,
    val updatedAt: String,
)

@Serializable
data class RecordPageResponse(val records: List<RecordDto>, val next: String? = null)

@Serializable
data class ErrorDetail(val code: String, val message: String, val field: String? = null)

@Serializable
data class ErrorBody(val error: ErrorDetail)

internal fun IssuedSession.toTokenResponse() = TokenResponse(token = token, expiresAt = expiresAt.toString())

internal fun RegisteredDevice.toResponse() = DeviceRegisteredResponse(
    device = device.toResponse(),
    token = session.token,
    expiresAt = session.expiresAt.toString(),
)

internal fun Device.toResponse() = DeviceResponse(
    id = id.toString(),
    name = name,
    platform = platform,
    createdAt = createdAt.toString(),
)

internal fun UserAccount.toResponse() =
    UserResponse(id = id.toString(), email = email, createdAt = createdAt.toString())

internal fun ChangeInputDto.toRaw() = RawChange(changeId, entityType, entityId, operation, baseVersion, payload)

internal fun ChangeOutcome.toDto(): ChangeResultDto = when (this) {
    is ChangeOutcome.Accepted -> ChangeResultDto(
        changeId = changeId.toString(),
        status = if (duplicate) "DUPLICATE" else "ACCEPTED",
        entityType = entityType.wireName,
        entityId = entityId.toString(),
        version = version,
        seq = seq,
    )
    is ChangeOutcome.Conflict -> ChangeResultDto(
        changeId = changeId.toString(),
        status = "CONFLICT",
        entityType = entityType.wireName,
        entityId = entityId.toString(),
        serverVersion = serverVersion,
        serverDeleted = serverDeleted,
        serverPayload = serverPayload,
    )
    is ChangeOutcome.Rejected -> ChangeResultDto(
        changeId = changeId.toString(),
        status = "REJECTED",
        entityType = entityType.wireName,
        entityId = entityId.toString(),
        reason = reason,
    )
}

internal fun ChangeRecord.toDto() = ChangeRecordDto(
    seq = seq,
    changeId = changeId.toString(),
    deviceId = deviceId?.toString(),
    entityType = entityType.wireName,
    entityId = entityId.toString(),
    operation = if (operation == Operation.DELETE) "DELETE" else "UPSERT",
    baseVersion = baseVersion,
    version = version,
    payload = payload,
    createdAt = createdAt.toString(),
)

internal fun EntityHead.toRecordDto() = RecordDto(
    entityType = entityType.wireName,
    entityId = entityId.toString(),
    version = version,
    payload = checkNotNull(payload) { "A live record always has a payload" },
    updatedAt = updatedAt.toString(),
)
