package com.routinepilot.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How a session ended. Null while the session is still open. */
enum class SessionOutcome {
    /** Ended with Finish. The only outcome that counts as a completed routine session. */
    FINISHED,

    /** Ended with Cancel, or because its saved plan no longer matched the routine. Never counts as completed. */
    CANCELLED,

    /**
     * Ended before outcomes were recorded (schema version 4 and earlier). Counted as neither finished nor cancelled.
     */
    UNRECORDED,
}

/**
 * [localDate] is the device-zone date the session started, stored so statistics can group by day without time-zone
 * arithmetic in SQL. Null only for sessions recorded before schema version 5.
 * [focusedMillis] is running time only, excluding pauses. Null until the session ends.
 */
data class ExecutionSession(
    val id: UUID,
    val routineId: UUID,
    val occurrenceId: UUID?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val createdAt: Instant,
    val outcome: SessionOutcome? = null,
    val focusedMillis: Long? = null,
    val localDate: LocalDate? = null,
)

data class StepCompletionEvent(
    val id: UUID,
    val sessionId: UUID,
    val stepId: UUID,
    val completedAt: Instant,
    val createdAt: Instant,
    val localDate: LocalDate? = null,
)
