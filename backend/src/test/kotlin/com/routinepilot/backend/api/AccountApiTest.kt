package com.routinepilot.backend.api

import com.routinepilot.backend.backendModule
import com.routinepilot.backend.db.TestPostgres
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountApiTest {
    @BeforeEach
    fun reset() {
        TestPostgres.reset()
    }

    @Test
    fun changePassword_needsTheCurrentPassword_andSignsOutOtherSessions() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val first = client.signUp("mary@example.com").token
        val second = client.login("mary@example.com", PASSWORD)

        val wrong = client.put("/v1/me/password") {
            bearerAuth(first)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest("not the password", "a brand new passphrase"))
        }
        val changed = client.put("/v1/me/password") {
            bearerAuth(first)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(PASSWORD, "a brand new passphrase"))
        }
        val otherSession = client.get("/v1/me") { bearerAuth(second) }
        val stillSignedIn = client.get("/v1/me") { bearerAuth(first) }
        val oldPassword = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("mary@example.com", PASSWORD))
        }

        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals("invalid_password", wrong.errorCode())
        assertEquals(HttpStatusCode.NoContent, changed.status)
        assertEquals(HttpStatusCode.Unauthorized, otherSession.status)
        assertEquals(HttpStatusCode.OK, stillSignedIn.status)
        assertEquals(HttpStatusCode.Unauthorized, oldPassword.status)
    }

    @Test
    fun deleteAccount_needsThePassword_andThenNothingAuthenticates() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val token = client.signUp("mary@example.com").token

        val refused = client.post("/v1/me/delete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest("not the password"))
        }
        val deleted = client.post("/v1/me/delete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(PASSWORD))
        }
        val afterDelete = client.get("/v1/me") { bearerAuth(token) }
        val login = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("mary@example.com", PASSWORD))
        }

        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.Unauthorized, afterDelete.status)
        assertEquals(HttpStatusCode.Unauthorized, login.status)
    }

    @Test
    fun deviceSession_canSyncButCannotManageTheAccount() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val account = client.signUp("mary@example.com").token
        val device = client.registerDevice(account)

        val createDevice = client.post("/v1/devices") {
            bearerAuth(device.token)
            contentType(ContentType.Application.Json)
            setBody(DeviceRequest("Tablet", "android"))
        }
        val listDevices = client.get("/v1/devices") { bearerAuth(device.token) }
        val me = client.get("/v1/me") { bearerAuth(device.token) }

        assertEquals(HttpStatusCode.Forbidden, createDevice.status)
        assertEquals("account_session_required", createDevice.errorCode())
        assertEquals(HttpStatusCode.Forbidden, listDevices.status)
        assertEquals(HttpStatusCode.OK, me.status)
    }

    @Test
    fun devices_areListed_andRevokingOneEndsItsSession() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val account = client.signUp("mary@example.com").token
        val device = client.registerDevice(account, name = "Old phone")

        val listed = client.get("/v1/devices") { bearerAuth(account) }.body<DeviceListResponse>()
        val revoke = client.delete("/v1/devices/${device.device.id}") { bearerAuth(account) }
        val deviceAfter = client.get("/v1/me") { bearerAuth(device.token) }
        val listedAfter = client.get("/v1/devices") { bearerAuth(account) }.body<DeviceListResponse>()
        val revokeAgain = client.delete("/v1/devices/${device.device.id}") { bearerAuth(account) }

        assertEquals(listOf("Old phone"), listed.devices.map { it.name })
        assertEquals(HttpStatusCode.NoContent, revoke.status)
        assertEquals(HttpStatusCode.Unauthorized, deviceAfter.status)
        assertTrue(listedAfter.devices.isEmpty())
        assertEquals(HttpStatusCode.NotFound, revokeAgain.status)
    }

    private suspend fun io.ktor.client.HttpClient.login(email: String, password: String): String {
        val response = post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest(email, password))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body<TokenResponse>().token
    }
}
