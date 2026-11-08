package com.routinepilot.backend.api

import com.routinepilot.backend.domain.ApiException
import com.routinepilot.backend.domain.RateLimitedException
import com.routinepilot.backend.service.AccountService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * The JSON format for every request and response. Unknown request fields are ignored, and absent optional fields are
 * left out of responses.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal const val SESSION_AUTH = "session"

private val log = LoggerFactory.getLogger("com.routinepilot.backend")

fun Application.configureAuthentication(accounts: AccountService) {
    install(Authentication) {
        bearer(SESSION_AUTH) {
            realm = "routinepilot"
            authenticate { credential -> accounts.resolveSession(credential.token) }
        }
    }
}

/** Every failure becomes a JSON error with a stable code. Internal details are logged, not sent to the client. */
fun Application.configureErrors() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.status),
                ErrorBody(ErrorDetail(cause.code, cause.message.orEmpty(), cause.field)),
            )
        }
        exception<RateLimitedException> { call, cause ->
            call.response.headers.append(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorBody(ErrorDetail("rate_limited", "Too many requests. Try again later.")),
            )
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ErrorBody(ErrorDetail("unsupported_media_type", "Send the request as application/json.")),
            )
        }
        exception<ContentTransformationException> { call, _ -> respondMalformed(call) }
        exception<BadRequestException> { call, _ -> respondMalformed(call) }
        exception<SerializationException> { call, _ -> respondMalformed(call) }
        exception<Throwable> { call, cause ->
            log.error("Unhandled failure for ${call.request.origin.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorBody(ErrorDetail("internal_error", "Something went wrong.")),
            )
        }
    }
}

private suspend fun respondMalformed(call: ApplicationCall) {
    call.respond(
        HttpStatusCode.BadRequest,
        ErrorBody(ErrorDetail("malformed_request", "The request body does not match this endpoint.")),
    )
}

/**
 * Refuses plain HTTP when [requireHttps] is set, and sends HSTS on HTTPS responses. Behind a reverse proxy, the
 * scheme comes from X-Forwarded-Proto, but only when [trustForwardedHeaders] is set, because a client could otherwise
 * forge it.
 */
fun transportSecurity(requireHttps: Boolean, trustForwardedHeaders: Boolean) =
    createApplicationPlugin(name = "TransportSecurity") {
        onCall { call ->
            if (call.isSecure(trustForwardedHeaders)) {
                call.response.headers.append(HttpHeaders.StrictTransportSecurity, "max-age=31536000")
            } else if (requireHttps) {
                call.respond(
                    HttpStatusCode.UpgradeRequired,
                    ErrorBody(ErrorDetail("https_required", "Use HTTPS for this API.")),
                )
            }
        }
    }

private fun ApplicationCall.isSecure(trustForwardedHeaders: Boolean): Boolean {
    val forwarded = if (trustForwardedHeaders) {
        request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()
    } else {
        null
    }
    return (forwarded ?: request.origin.scheme) == "https"
}
