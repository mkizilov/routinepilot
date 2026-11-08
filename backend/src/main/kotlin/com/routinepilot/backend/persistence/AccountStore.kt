package com.routinepilot.backend.persistence

import com.routinepilot.backend.db.instant
import com.routinepilot.backend.db.query
import com.routinepilot.backend.db.queryOne
import com.routinepilot.backend.db.update
import com.routinepilot.backend.db.uuid
import com.routinepilot.backend.db.uuidOrNull
import com.routinepilot.backend.domain.Device
import com.routinepilot.backend.domain.NewSession
import com.routinepilot.backend.domain.SessionPrincipal
import com.routinepilot.backend.domain.UserAccount
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** SQL for users, devices, and sessions. Every method runs inside the caller's transaction. */
class AccountStore {
    fun insertUser(connection: Connection, user: UserAccount) {
        connection.update(
            "INSERT INTO users (id, email, password_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            user.id,
            user.email,
            user.passwordHash,
            user.createdAt,
            user.createdAt,
        )
    }

    fun findUserByEmail(connection: Connection, email: String): UserAccount? =
        connection.queryOne("$USER_SQL WHERE email = ?", email) { readUser(it) }

    fun findUserById(connection: Connection, userId: UUID): UserAccount? =
        connection.queryOne("$USER_SQL WHERE id = ?", userId) { readUser(it) }

    fun updatePasswordHash(connection: Connection, userId: UUID, passwordHash: String, now: Instant) {
        connection.update("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?", passwordHash, now, userId)
    }

    /** Removes the account. Its devices, sessions, and synced records go with it through ON DELETE CASCADE. */
    fun deleteUser(connection: Connection, userId: UUID) {
        connection.update("DELETE FROM users WHERE id = ?", userId)
    }

    fun insertDevice(connection: Connection, device: Device) {
        connection.update(
            "INSERT INTO devices (id, user_id, name, platform, created_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?)",
            device.id,
            device.userId,
            device.name,
            device.platform,
            device.createdAt,
            device.revokedAt,
        )
    }

    fun listActiveDevices(connection: Connection, userId: UUID): List<Device> =
        connection.query("$DEVICE_SQL WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at, id", userId) {
            readDevice(it)
        }

    fun findActiveDevice(connection: Connection, userId: UUID, deviceId: UUID): Device? =
        connection.queryOne("$DEVICE_SQL WHERE id = ? AND user_id = ? AND revoked_at IS NULL", deviceId, userId) {
            readDevice(it)
        }

    fun revokeDevice(connection: Connection, deviceId: UUID, now: Instant) {
        connection.update("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, deviceId)
    }

    fun insertSession(connection: Connection, session: NewSession) {
        connection.update(
            "INSERT INTO sessions (id, user_id, device_id, token_hash, created_at, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            session.id,
            session.userId,
            session.deviceId,
            session.tokenHash,
            session.createdAt,
            session.expiresAt,
        )
    }

    /**
     * The session behind a token, or null when it is unknown, expired, revoked, or bound to a revoked device. The
     * device check is what makes revoking a device take effect at once.
     */
    fun findActiveSession(connection: Connection, tokenHash: String, now: Instant): SessionPrincipal? =
        connection.queryOne(
            "SELECT s.id, s.user_id, s.device_id FROM sessions s " +
                "JOIN users u ON u.id = s.user_id " +
                "LEFT JOIN devices d ON d.id = s.device_id " +
                "WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ? " +
                "AND (d.id IS NULL OR d.revoked_at IS NULL)",
            tokenHash,
            now,
        ) { row ->
            SessionPrincipal(
                sessionId = row.uuid("id"),
                userId = row.uuid("user_id"),
                deviceId = row.uuidOrNull("device_id"),
            )
        }

    fun revokeSession(connection: Connection, sessionId: UUID, now: Instant) {
        connection.update("UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, sessionId)
    }

    fun revokeOtherSessions(connection: Connection, userId: UUID, keep: UUID, now: Instant) {
        connection.update(
            "UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND id <> ? AND revoked_at IS NULL",
            now,
            userId,
            keep,
        )
    }

    fun revokeSessionsForDevice(connection: Connection, deviceId: UUID, now: Instant) {
        connection.update(
            "UPDATE sessions SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL",
            now,
            deviceId,
        )
    }

    private fun readUser(row: ResultSet) = UserAccount(
        id = row.uuid("id"),
        email = row.getString("email"),
        passwordHash = row.getString("password_hash"),
        createdAt = row.instant("created_at"),
    )

    private fun readDevice(row: ResultSet) = Device(
        id = row.uuid("id"),
        userId = row.uuid("user_id"),
        name = row.getString("name"),
        platform = row.getString("platform"),
        createdAt = row.instant("created_at"),
        revokedAt = row.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
    )

    private companion object {
        const val USER_SQL = "SELECT id, email, password_hash, created_at FROM users"
        const val DEVICE_SQL = "SELECT id, user_id, name, platform, created_at, revoked_at FROM devices"
    }
}
