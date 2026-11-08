package com.routinepilot.data.sync

import com.routinepilot.BuildConfig
import com.routinepilot.domain.sync.RemoteBusyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** A non-success answer from the server. [code] is the server's stable error code, such as `invalid_credentials`. */
class BackendException(
    val status: Int,
    val code: String,
    message: String,
) : RuntimeException(message)

/**
 * The backend's REST API over the platform's HttpURLConnection, so no networking library is needed.
 * Every call runs on the
 * IO dispatcher. Server-busy and server-error answers become [RemoteBusyException], which is retried later.
 * Other answers
 * become [BackendException] for the caller to interpret.
 */
class BackendClient
    @Inject
    constructor() {
        internal suspend fun login(
            email: String,
            password: String,
        ): TokenDto = credentials("/v1/auth/login", email, password)

        internal suspend fun register(
            email: String,
            password: String,
        ): TokenDto = credentials("/v1/auth/register", email, password)

        internal suspend fun me(accountToken: String): MeDto =
            ApiJson.decodeFromString(exchange("GET", "/v1/me", accountToken))

        internal suspend fun logout(accountToken: String) {
            exchange("POST", "/v1/auth/logout", accountToken)
        }

        internal suspend fun registerDevice(
            accountToken: String,
            name: String,
            platform: String,
        ): DeviceRegisteredDto =
            ApiJson.decodeFromString(
                exchange("POST", "/v1/devices", accountToken, ApiJson.encodeToString(DeviceRequest(name, platform))),
            )

        internal suspend fun revokeDevice(
            accountToken: String,
            deviceId: String,
        ) {
            exchange("DELETE", "/v1/devices/$deviceId", accountToken)
        }

        internal suspend fun deleteAccount(
            accountToken: String,
            password: String,
        ) {
            exchange("POST", "/v1/me/delete", accountToken, ApiJson.encodeToString(DeleteAccountRequest(password)))
        }

        internal suspend fun upload(
            deviceToken: String,
            body: UploadDto,
        ): UploadResponseDto =
            ApiJson.decodeFromString(
                exchange("POST", "/v1/sync/changes", deviceToken, ApiJson.encodeToString(body)),
            )

        internal suspend fun pull(
            deviceToken: String,
            since: Long,
            limit: Int,
        ): ChangePageDto =
            ApiJson.decodeFromString(exchange("GET", "/v1/sync/changes?since=$since&limit=$limit", deviceToken))

        private suspend fun credentials(
            path: String,
            email: String,
            password: String,
        ): TokenDto =
            ApiJson.decodeFromString(
                exchange("POST", path, null, ApiJson.encodeToString(LoginRequest(email, password))),
            )

        private suspend fun exchange(
            method: String,
            path: String,
            token: String?,
            body: String? = null,
        ): String =
            withContext(Dispatchers.IO) {
                if (BuildConfig.SYNC_BASE_URL.isBlank()) {
                    throw RemoteBusyException(
                        "Sync is not configured in this build",
                    )
                }
                val connection =
                    (URL(BuildConfig.SYNC_BASE_URL + path).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty("Accept", "application/json")
                        if (token != null) setRequestProperty("Authorization", "Bearer $token")
                        if (body != null) {
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                    }
                try {
                    if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val text =
                        (if (status < HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            .orEmpty()
                    if (status < HTTP_BAD_REQUEST) text else throw failureFor(status, text)
                } finally {
                    connection.disconnect()
                }
            }

        private fun failureFor(
            status: Int,
            text: String,
        ): Exception {
            if (status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR) {
                return RemoteBusyException("The server is busy (HTTP $status)")
            }
            val detail = runCatching { ApiJson.decodeFromString<ErrorBodyDto>(text).error }.getOrNull()
            return BackendException(status, detail?.code ?: "http_$status", detail?.message ?: "Request failed")
        }

        private companion object {
            const val CONNECT_TIMEOUT_MS = 15_000
            const val READ_TIMEOUT_MS = 30_000
            const val HTTP_BAD_REQUEST = 400
            const val HTTP_TOO_MANY_REQUESTS = 429
            const val HTTP_SERVER_ERROR = 500
        }
    }
