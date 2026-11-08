package com.routinepilot.backend.api

import com.routinepilot.backend.config.AppConfig
import com.routinepilot.backend.config.RateLimitConfig
import com.routinepilot.backend.config.RateRule
import com.routinepilot.backend.config.SyncConfig
import com.routinepilot.backend.db.TestPostgres
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.assertEquals

internal const val PASSWORD = "correct horse battery"

internal val generousRateLimits = RateLimitConfig(
    authPerIp = RateRule(1_000, 60),
    loginPerAccount = RateRule(1_000, 900),
    apiPerSession = RateRule(10_000, 60),
)

internal fun apiConfig(
    rateLimits: RateLimitConfig = generousRateLimits,
    requireHttps: Boolean = false,
    trustForwardedHeaders: Boolean = false,
    maxBodyBytes: Long = 262_144,
): AppConfig = AppConfig(
    database = TestPostgres.databaseConfig,
    sessionDays = 30,
    passwordIterations = 10_000,
    requireHttps = requireHttps,
    trustForwardedHeaders = trustForwardedHeaders,
    rateLimits = rateLimits,
    sync = SyncConfig(maxBatchChanges = 50, maxPayloadBytes = 4_096, maxBodyBytes = maxBodyBytes, pageSize = 100),
)

internal fun ApplicationTestBuilder.apiClient(): HttpClient = createClient {
    install(ContentNegotiation) { json(ApiJson) }
}

internal suspend fun HttpClient.signUp(email: String, password: String = PASSWORD): TokenResponse {
    val response = post("/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(CredentialsRequest(email, password))
    }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

internal suspend fun HttpClient.registerDevice(accountToken: String, name: String = "Pixel"): DeviceRegisteredResponse {
    val response = post("/v1/devices") {
        bearerAuth(accountToken)
        contentType(ContentType.Application.Json)
        setBody(DeviceRequest(name, "android"))
    }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

internal suspend fun HttpResponse.errorCode(): String = body<ErrorBody>().error.code

internal fun routineTitle(title: String) = buildJsonObject { put("title", title) }

internal fun routineChange(
    entityId: UUID,
    title: String,
    baseVersion: Long = 0,
    changeId: UUID = UUID.randomUUID(),
) = ChangeInputDto(
    changeId = changeId.toString(),
    entityType = "routine",
    entityId = entityId.toString(),
    operation = "UPSERT",
    baseVersion = baseVersion,
    payload = routineTitle(title),
)

internal fun deleteChange(entityId: UUID, baseVersion: Long, changeId: UUID = UUID.randomUUID()) = ChangeInputDto(
    changeId = changeId.toString(),
    entityType = "routine",
    entityId = entityId.toString(),
    operation = "DELETE",
    baseVersion = baseVersion,
)

internal suspend fun HttpClient.upload(deviceToken: String, vararg changes: ChangeInputDto): SyncUploadResponse {
    val response = post("/v1/sync/changes") {
        bearerAuth(deviceToken)
        contentType(ContentType.Application.Json)
        setBody(SyncUploadRequest(changes.toList()))
    }
    assertEquals(HttpStatusCode.OK, response.status)
    return response.body()
}
