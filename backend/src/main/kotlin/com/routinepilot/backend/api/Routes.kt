package com.routinepilot.backend.api

import com.routinepilot.backend.config.AppConfig
import com.routinepilot.backend.domain.ApiException
import com.routinepilot.backend.domain.EntityType
import com.routinepilot.backend.domain.SessionPrincipal
import com.routinepilot.backend.domain.SyncValidator
import com.routinepilot.backend.domain.Validation
import com.routinepilot.backend.domain.invalid
import com.routinepilot.backend.security.FixedWindowRateLimiter
import com.routinepilot.backend.service.AccountService
import com.routinepilot.backend.service.SyncService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

private val NIL_UUID = UUID(0L, 0L)

/**
 * Version 1 of the API. Account sessions manage the account and devices. Device sessions sync. Reading records
 * works with either kind. Unauthenticated routes are limited by client address, and authenticated routes that
 * write or sync are limited by session.
 */
fun Route.v1Routes(accounts: AccountService, sync: SyncService, limiter: FixedWindowRateLimiter, config: AppConfig) {
    val trustForwarded = config.trustForwardedHeaders
    val limits = config.rateLimits
    val maxBody = config.sync.maxBodyBytes

    route("/v1") {
        route("/auth") {
            post("/register") {
                limiter.enforce("auth:${call.clientAddress(trustForwarded)}", limits.authPerIp)
                val request = call.receiveLimited<CredentialsRequest>(maxBody)
                val issued = accounts.register(request.email, request.password)
                call.respond(HttpStatusCode.Created, issued.toTokenResponse())
            }
            post("/login") {
                val address = call.clientAddress(trustForwarded)
                limiter.enforce("auth:$address", limits.authPerIp)
                val request = call.receiveLimited<CredentialsRequest>(maxBody)
                limiter.enforce("login:${request.email.trim().lowercase()}|$address", limits.loginPerAccount)
                call.respond(HttpStatusCode.OK, accounts.login(request.email, request.password).toTokenResponse())
            }
            authenticate(SESSION_AUTH) {
                post("/logout") {
                    accounts.logout(call.session().sessionId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        authenticate(SESSION_AUTH) {
            get("/me") {
                call.respond(HttpStatusCode.OK, accounts.profile(call.session().userId).toResponse())
            }
            put("/me/password") {
                val session = call.session().requireAccount()
                val request = call.receiveLimited<ChangePasswordRequest>(maxBody)
                accounts.changePassword(session, request.currentPassword, request.newPassword)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/me/delete") {
                val session = call.session().requireAccount()
                val request = call.receiveLimited<DeleteAccountRequest>(maxBody)
                accounts.deleteAccount(session.userId, request.password)
                call.respond(HttpStatusCode.NoContent)
            }

            route("/devices") {
                post {
                    val session = call.session().requireAccount()
                    val request = call.receiveLimited<DeviceRequest>(maxBody)
                    val registered = accounts.registerDevice(session.userId, request.name, request.platform)
                    call.respond(HttpStatusCode.Created, registered.toResponse())
                }
                get {
                    val session = call.session().requireAccount()
                    val devices = accounts.listDevices(session.userId).map { it.toResponse() }
                    call.respond(HttpStatusCode.OK, DeviceListResponse(devices))
                }
                delete("/{id}") {
                    val session = call.session().requireAccount()
                    accounts.revokeDevice(session.userId, Validation.uuid(call.parameters["id"].orEmpty(), "id"))
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            get("/records") {
                val session = call.session()
                limiter.enforce("session:${session.sessionId}", limits.apiPerSession)
                val type = EntityType.fromWire(call.request.queryParameters["entityType"].orEmpty())
                    ?: throw invalid("entityType", "Unknown entity type.")
                val after = call.request.queryParameters["after"]?.let { Validation.uuid(it, "after") } ?: NIL_UUID
                val limit = call.queryInt("limit", default = config.sync.pageSize, min = 1, max = config.sync.pageSize)
                val page = sync.records(session.userId, type, after, limit)
                call.respond(
                    HttpStatusCode.OK,
                    RecordPageResponse(page.heads.map { it.toRecordDto() }, page.next?.toString()),
                )
            }

            route("/sync") {
                post("/changes") {
                    val session = call.session()
                    val deviceId = session.requireDevice()
                    limiter.enforce("session:${session.sessionId}", limits.apiPerSession)
                    val request = call.receiveLimited<SyncUploadRequest>(maxBody)
                    val changes = SyncValidator.validateBatch(request.changes.map { it.toRaw() }, config.sync)
                    val outcomes = sync.upload(session.userId, deviceId, changes)
                    call.respond(HttpStatusCode.OK, SyncUploadResponse(outcomes.map { it.toDto() }))
                }
                get("/changes") {
                    val session = call.session()
                    session.requireDevice()
                    limiter.enforce("session:${session.sessionId}", limits.apiPerSession)
                    val since = call.queryLong("since", default = 0L, min = 0L)
                    val limit = call.queryInt("limit", config.sync.pageSize, min = 1, max = config.sync.pageSize)
                    val page = sync.download(session.userId, since, limit)
                    call.respond(
                        HttpStatusCode.OK,
                        ChangePageResponse(page.changes.map { it.toDto() }, page.cursor, page.hasMore),
                    )
                }
            }
        }
    }
}

private fun ApplicationCall.session(): SessionPrincipal =
    principal<SessionPrincipal>() ?: throw ApiException(401, "unauthorized", "Sign in to continue.")

private fun SessionPrincipal.requireAccount(): SessionPrincipal {
    if (deviceId != null) throw ApiException(403, "account_session_required", "Use an account session for this action.")
    return this
}

private fun SessionPrincipal.requireDevice(): UUID =
    deviceId ?: throw ApiException(403, "device_session_required", "Register this device before syncing.")

/** The first hop of X-Forwarded-For when headers are trusted, otherwise the socket address. */
private fun ApplicationCall.clientAddress(trustForwardedHeaders: Boolean): String =
    (if (trustForwardedHeaders) request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim() else null)
        ?: request.origin.remoteAddress

/** Rejects bodies that declare a length over the limit before they are read. */
private suspend inline fun <reified T : Any> ApplicationCall.receiveLimited(maxBytes: Long): T {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) {
        throw ApiException(413, "payload_too_large", "The request body is too large.")
    }
    return receive()
}

private fun ApplicationCall.queryLong(name: String, default: Long, min: Long): Long {
    val raw = request.queryParameters[name] ?: return default
    val value = raw.toLongOrNull()
    if (value == null || value < min) throw invalid(name, "Must be a whole number of at least $min.")
    return value
}

private fun ApplicationCall.queryInt(name: String, default: Int, min: Int, max: Int): Int {
    val raw = request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull()
    if (value == null || value !in min..max) throw invalid(name, "Must be a whole number from $min to $max.")
    return value
}
