package com.routinepilot.backend.domain

/** A failure reported to the client. [status] is the HTTP status, and [code] is stable so clients can match on it. */
class ApiException(
    val status: Int,
    val code: String,
    message: String,
    val field: String? = null,
) : RuntimeException(message)

fun invalid(field: String, message: String) = ApiException(400, "validation_failed", message, field)

class RateLimitedException(val retryAfterSeconds: Long) : RuntimeException("rate_limited")
