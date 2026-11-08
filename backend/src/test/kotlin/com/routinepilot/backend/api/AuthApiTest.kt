package com.routinepilot.backend.api

import com.routinepilot.backend.backendModule
import com.routinepilot.backend.config.RateLimitConfig
import com.routinepilot.backend.config.RateRule
import com.routinepilot.backend.db.TestPostgres
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.config.MapApplicationConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuthApiTest {
    @BeforeEach
    fun reset() {
        TestPostgres.reset()
    }

    @Test
    fun register_thenMe_returnsTheNormalizedAccount() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val token = client.signUp("  Mary@Example.COM ").token

        val me = client.get("/v1/me") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("mary@example.com", me.body<UserResponse>().email)
    }

    @Test
    fun register_withAnEmailInAnotherCase_isAConflict() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        client.signUp("mary@example.com")

        val response = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("MARY@example.com", PASSWORD))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("email_taken", response.errorCode())
    }

    @Test
    fun register_reportsWhichFieldIsInvalid() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()

        val badEmail = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("not-an-email", PASSWORD))
        }
        val shortPassword = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("ok@example.com", "short"))
        }

        assertEquals(HttpStatusCode.BadRequest, badEmail.status)
        assertEquals("email", badEmail.body<ErrorBody>().error.field)
        assertEquals("password", shortPassword.body<ErrorBody>().error.field)
    }

    @Test
    fun login_failuresLookTheSame_whetherTheEmailExistsOrNot() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        client.signUp("mary@example.com")

        val wrongPassword = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("mary@example.com", "wrong password here"))
        }
        val unknownEmail = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("nobody@example.com", "wrong password here"))
        }

        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals(wrongPassword.bodyAsText(), unknownEmail.bodyAsText())
    }

    @Test
    fun protectedRoutes_requireAValidBearerToken() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()

        val missing = client.get("/v1/me")
        val forged = client.get("/v1/me") { bearerAuth("rp_not-a-real-token") }

        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun logout_revokesTheToken() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val token = client.signUp("mary@example.com").token

        val logout = client.post("/v1/auth/logout") { bearerAuth(token) }
        val afterLogout = client.get("/v1/me") { bearerAuth(token) }

        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
    }

    @Test
    fun login_isRateLimitedPerAccount_withARetryTime() = testApplication {
        val limits = RateLimitConfig(
            authPerIp = RateRule(1_000, 60),
            loginPerAccount = RateRule(3, 900),
            apiPerSession = RateRule(10_000, 60),
        )
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig(rateLimits = limits)) }
        val client = apiClient()
        client.signUp("mary@example.com")

        repeat(3) {
            val attempt = client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(CredentialsRequest("mary@example.com", "wrong password here"))
            }
            assertEquals(HttpStatusCode.Unauthorized, attempt.status)
        }
        val limited = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(CredentialsRequest("mary@example.com", PASSWORD))
        }

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limited", limited.errorCode())
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun plainHttp_isRefused_whenHttpsIsRequired_andForwardedHttpsIsAccepted() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig(requireHttps = true, trustForwardedHeaders = true)) }
        val client = apiClient()

        val plain = client.get("/health")
        val forwarded = client.get("/health") { header("X-Forwarded-Proto", "https") }

        assertEquals(HttpStatusCode.UpgradeRequired, plain.status)
        assertEquals("https_required", plain.errorCode())
        assertEquals(HttpStatusCode.OK, forwarded.status)
        assertNotNull(forwarded.headers[HttpHeaders.StrictTransportSecurity])
    }

    @Test
    fun malformedJson_isABadRequestWithAStableCode() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()

        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("malformed_request", response.errorCode())
    }
}
