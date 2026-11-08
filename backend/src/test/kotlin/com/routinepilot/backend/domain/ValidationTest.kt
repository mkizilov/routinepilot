package com.routinepilot.backend.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidationTest {
    @Test
    fun email_isTrimmedAndLowerCased() {
        assertEquals("mary@example.com", Validation.email("  Mary@Example.COM "))
    }

    @Test
    fun email_rejectsMalformedAddresses() {
        listOf("", "no-at-sign", "a@b", "two@@example.com", "spaces in@example.com").forEach { raw ->
            val failure = assertFailsWith<ApiException>(raw) { Validation.email(raw) }
            assertEquals(400, failure.status)
            assertEquals("email", failure.field)
        }
    }

    @Test
    fun password_enforcesLengthOnly() {
        assertEquals("a".repeat(12), Validation.password("a".repeat(12)))
        assertFailsWith<ApiException> { Validation.password("a".repeat(11)) }
        assertFailsWith<ApiException> { Validation.password("a".repeat(129)) }
    }

    @Test
    fun uuid_acceptsOnlyTheCanonicalForm() {
        val canonical = "00000000-0000-4000-8000-0000000000aa"
        assertEquals(canonical, Validation.uuid(canonical, "id").toString())
        assertFailsWith<ApiException> { Validation.uuid("1-1-1-1-1", "id") }
        assertFailsWith<ApiException> { Validation.uuid("not-a-uuid", "id") }
    }

    @Test
    fun text_trimsAndBoundsLength() {
        assertEquals("Brush", Validation.text("  Brush  ", "name", 10))
        assertFailsWith<ApiException> { Validation.text("   ", "name", 10) }
        assertFailsWith<ApiException> { Validation.text("x".repeat(11), "name", 10) }
    }
}
