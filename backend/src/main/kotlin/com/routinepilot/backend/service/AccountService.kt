package com.routinepilot.backend.service

import com.routinepilot.backend.config.AppConfig
import com.routinepilot.backend.db.Database
import com.routinepilot.backend.domain.ApiException
import com.routinepilot.backend.domain.Device
import com.routinepilot.backend.domain.IssuedSession
import com.routinepilot.backend.domain.NewSession
import com.routinepilot.backend.domain.RegisteredDevice
import com.routinepilot.backend.domain.SessionPrincipal
import com.routinepilot.backend.domain.UserAccount
import com.routinepilot.backend.domain.Validation
import com.routinepilot.backend.persistence.AccountStore
import com.routinepilot.backend.security.PasswordHasher
import com.routinepilot.backend.security.SessionTokens
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Accounts, sessions, and devices. Each method is one transaction. */
class AccountService(
    private val db: Database,
    private val store: AccountStore,
    private val hasher: PasswordHasher,
    private val config: AppConfig,
    private val clock: Clock,
) {
    /** Verified against when the email is unknown, so a missing account takes as long to reject as a wrong password. */
    private val decoyHash: String by lazy { hasher.hash(DECOY_PASSWORD) }

    suspend fun register(emailInput: String, passwordInput: String): IssuedSession {
        val email = Validation.email(emailInput)
        val password = Validation.password(passwordInput)
        val now = clock.instant()
        try {
            return db.transaction { connection ->
                val user = UserAccount(UUID.randomUUID(), email, hasher.hash(password), now)
                store.insertUser(connection, user)
                issueSession(connection, user.id, null, now)
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == UNIQUE_VIOLATION) {
                throw ApiException(409, "email_taken", "An account with this email already exists.")
            }
            throw failure
        }
    }

    suspend fun login(emailInput: String, password: String): IssuedSession {
        val email = emailInput.trim().lowercase()
        val now = clock.instant()
        return db.transaction { connection ->
            val user = store.findUserByEmail(connection, email)
            val matches = hasher.verify(password, user?.passwordHash ?: decoyHash)
            if (user == null || !matches) throw invalidCredentials()
            issueSession(connection, user.id, null, now)
        }
    }

    suspend fun logout(sessionId: UUID) {
        val now = clock.instant()
        db.transaction { store.revokeSession(it, sessionId, now) }
    }

    /** The session behind a bearer token, or null when the token does not authenticate. */
    suspend fun resolveSession(token: String): SessionPrincipal? {
        val tokenHash = SessionTokens.hash(token)
        val now = clock.instant()
        return db.transaction { store.findActiveSession(it, tokenHash, now) }
    }

    suspend fun profile(userId: UUID): UserAccount =
        db.transaction { store.findUserById(it, userId) } ?: throw sessionInvalid()

    /** Changing the password signs out every other session. The session making the change stays signed in. */
    suspend fun changePassword(session: SessionPrincipal, currentPassword: String, newPasswordInput: String) {
        val newPassword = Validation.password(newPasswordInput)
        val now = clock.instant()
        db.transaction { connection ->
            val user = store.findUserById(connection, session.userId) ?: throw sessionInvalid()
            requirePassword(currentPassword, user)
            store.updatePasswordHash(connection, user.id, hasher.hash(newPassword), now)
            store.revokeOtherSessions(connection, user.id, session.sessionId, now)
        }
    }

    suspend fun deleteAccount(userId: UUID, password: String) {
        db.transaction { connection ->
            val user = store.findUserById(connection, userId) ?: throw sessionInvalid()
            requirePassword(password, user)
            store.deleteUser(connection, user.id)
        }
    }

    /** A device gets its own session, so its token can sync but cannot manage the account. */
    suspend fun registerDevice(userId: UUID, nameInput: String, platformInput: String): RegisteredDevice {
        val name = Validation.text(nameInput, "name", 100)
        val platform = Validation.text(platformInput, "platform", 32)
        val now = clock.instant()
        return db.transaction { connection ->
            val device = Device(UUID.randomUUID(), userId, name, platform, now, revokedAt = null)
            store.insertDevice(connection, device)
            RegisteredDevice(device, issueSession(connection, userId, device.id, now))
        }
    }

    suspend fun listDevices(userId: UUID): List<Device> = db.transaction { store.listActiveDevices(it, userId) }

    /** Revoking a device ends its sessions at once, because the session lookup also checks the device. */
    suspend fun revokeDevice(userId: UUID, deviceId: UUID) {
        val now = clock.instant()
        db.transaction { connection ->
            if (store.findActiveDevice(connection, userId, deviceId) == null) {
                throw ApiException(404, "device_not_found", "No active device has this id.")
            }
            store.revokeDevice(connection, deviceId, now)
            store.revokeSessionsForDevice(connection, deviceId, now)
        }
    }

    private fun issueSession(connection: Connection, userId: UUID, deviceId: UUID?, now: Instant): IssuedSession {
        val token = SessionTokens.generate()
        val expiresAt = now.plus(Duration.ofDays(config.sessionDays))
        store.insertSession(
            connection,
            NewSession(
                id = UUID.randomUUID(),
                userId = userId,
                deviceId = deviceId,
                tokenHash = SessionTokens.hash(token),
                createdAt = now,
                expiresAt = expiresAt,
            ),
        )
        return IssuedSession(token, expiresAt)
    }

    private fun requirePassword(password: String, user: UserAccount) {
        if (!hasher.verify(password, user.passwordHash)) {
            throw ApiException(403, "invalid_password", "The password is incorrect.")
        }
    }

    private fun invalidCredentials() = ApiException(401, "invalid_credentials", "Email or password is incorrect.")

    private fun sessionInvalid() = ApiException(401, "unauthorized", "Sign in again.")

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val DECOY_PASSWORD = "decoy-password-for-timing-only"
    }
}
