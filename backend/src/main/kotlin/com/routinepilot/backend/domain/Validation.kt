package com.routinepilot.backend.domain

import java.util.UUID

object Validation {
    const val EMAIL_MAX_LENGTH = 254
    const val PASSWORD_MIN_LENGTH = 12
    const val PASSWORD_MAX_LENGTH = 128

    private val emailPattern = Regex("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Emails are compared case-insensitively, so they are stored in lower case. */
    fun email(raw: String): String {
        val normalized = raw.trim().lowercase()
        if (normalized.length > EMAIL_MAX_LENGTH || !emailPattern.matches(normalized)) {
            throw invalid("email", "Enter a valid email address.")
        }
        return normalized
    }

    /** Length rules only, as NIST SP 800-63B recommends. Composition rules would push people toward weak patterns. */
    fun password(raw: String): String {
        if (raw.length !in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH) {
            throw invalid("password", "Password must be $PASSWORD_MIN_LENGTH to $PASSWORD_MAX_LENGTH characters.")
        }
        return raw
    }

    fun uuid(raw: String, field: String): UUID {
        if (!uuidPattern.matches(raw)) throw invalid(field, "Must be a UUID.")
        return UUID.fromString(raw)
    }

    fun text(raw: String?, field: String, maxLength: Int): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > maxLength) throw invalid(field, "Must be 1 to $maxLength characters.")
        return value
    }
}
