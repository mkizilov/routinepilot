package com.routinepilot.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutineValidatorTest {
    @Test
    fun title_mustNotBeBlank() {
        assertEquals(RoutineValidationError.BLANK_TITLE, RoutineValidator.validateTitle("   "))
        assertNull(RoutineValidator.validateTitle("Morning"))
    }

    @Test
    fun title_isLimitedAfterTrimming() {
        assertNull(RoutineValidator.validateTitle(" ${"a".repeat(RoutineValidator.MAX_TITLE_LENGTH)} "))
        assertEquals(
            RoutineValidationError.TITLE_TOO_LONG,
            RoutineValidator.validateTitle("a".repeat(RoutineValidator.MAX_TITLE_LENGTH + 1)),
        )
    }

    @Test
    fun description_isLimited() {
        assertNull(RoutineValidator.validateDescription(""))
        assertEquals(
            RoutineValidationError.DESCRIPTION_TOO_LONG,
            RoutineValidator.validateDescription("a".repeat(RoutineValidator.MAX_DESCRIPTION_LENGTH + 1)),
        )
    }

    @Test
    fun duration_blankMeansNoDuration() {
        assertNull(RoutineValidator.validateDurationMinutes(""))
        assertNull(RoutineValidator.validateDurationMinutes("  "))
        assertNull(RoutineValidator.durationSecondsOf(""))
    }

    @Test
    fun duration_acceptsWholeMinutesInRange() {
        assertNull(RoutineValidator.validateDurationMinutes("1"))
        assertNull(RoutineValidator.validateDurationMinutes(" ${RoutineValidator.MAX_DURATION_MINUTES} "))
        assertEquals(90 * 60, RoutineValidator.durationSecondsOf("90"))
    }

    @Test
    fun duration_rejectsZeroNegativeFractionalAndOverflowValues() {
        listOf("0", "-5", "1.5", "abc", "99999999999", "${RoutineValidator.MAX_DURATION_MINUTES + 1}").forEach {
            assertEquals(it, RoutineValidationError.DURATION_INVALID, RoutineValidator.validateDurationMinutes(it))
        }
    }
}
