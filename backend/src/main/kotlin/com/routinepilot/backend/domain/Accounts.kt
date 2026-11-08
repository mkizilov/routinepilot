package com.routinepilot.backend.domain

import java.time.Instant
import java.util.UUID

data class UserAccount(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val createdAt: Instant,
)

data class Device(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val platform: String,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

data class NewSession(
    val id: UUID,
    val userId: UUID,
    val deviceId: UUID?,
    val tokenHash: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * The caller of an authenticated request. A session with no device is an account session, which can manage the account
 * and its devices. A session bound to a device is a device session, which can only sync.
 */
data class SessionPrincipal(
    val sessionId: UUID,
    val userId: UUID,
    val deviceId: UUID?,
)

/** The raw token is shown once, at issue time. Only its hash is stored. */
class IssuedSession(val token: String, val expiresAt: Instant)

class RegisteredDevice(val device: Device, val session: IssuedSession)
