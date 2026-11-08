package com.routinepilot.domain.validation

enum class RoutineValidationError { BLANK_TITLE, TITLE_TOO_LONG, DESCRIPTION_TOO_LONG, DURATION_INVALID }

object RoutineValidator {
    const val MAX_TITLE_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 500
    const val MAX_DURATION_MINUTES = 480
    private const val SECONDS_PER_MINUTE = 60

    fun validateTitle(title: String): RoutineValidationError? = when {
        title.isBlank() -> RoutineValidationError.BLANK_TITLE
        title.trim().length > MAX_TITLE_LENGTH -> RoutineValidationError.TITLE_TOO_LONG
        else -> null
    }

    fun validateDescription(description: String): RoutineValidationError? =
        if (description.trim().length > MAX_DESCRIPTION_LENGTH) RoutineValidationError.DESCRIPTION_TOO_LONG else null

    /** A blank value means no duration. Otherwise it must be whole minutes in 1..[MAX_DURATION_MINUTES]. */
    fun validateDurationMinutes(minutes: String): RoutineValidationError? {
        if (minutes.isBlank()) return null
        val value = minutes.trim().toIntOrNull()
        return if (value != null && value in 1..MAX_DURATION_MINUTES) null else RoutineValidationError.DURATION_INVALID
    }

    /** Returns null for a blank value. Callers must validate first. */
    fun durationSecondsOf(minutes: String): Int? = minutes.trim().toIntOrNull()?.times(SECONDS_PER_MINUTE)
}
