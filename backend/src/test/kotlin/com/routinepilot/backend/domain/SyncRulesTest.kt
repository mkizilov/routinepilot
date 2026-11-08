package com.routinepilot.backend.domain

import com.routinepilot.backend.config.SyncConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncRulesTest {
    private val limits = SyncConfig(maxBatchChanges = 3, maxPayloadBytes = 300, maxBodyBytes = 10_000, pageSize = 10)
    private val entityId = "00000000-0000-4000-8000-0000000000aa"
    private val changeId = "00000000-0000-4000-8000-0000000000bb"

    @Test
    fun decide_appliesOnlyWhenTheClientSawTheCurrentVersion() {
        assertEquals(SyncDecider.Decision.Apply(4), SyncDecider.decide(serverVersion = 3, baseVersion = 3))
        assertEquals(SyncDecider.Decision.Conflict(3), SyncDecider.decide(serverVersion = 3, baseVersion = 2))
        assertEquals(SyncDecider.Decision.Conflict(3), SyncDecider.decide(serverVersion = 3, baseVersion = 4))
    }

    @Test
    fun decide_treatsAMissingRecordAsVersionZero() {
        assertEquals(SyncDecider.Decision.Apply(1), SyncDecider.decide(serverVersion = 0, baseVersion = 0))
        assertEquals(SyncDecider.Decision.Conflict(0), SyncDecider.decide(serverVersion = 0, baseVersion = 1))
    }

    @Test
    fun validate_acceptsAWellFormedRoutine() {
        val change = validate(upsert("routine", routineTitle("Morning"))).single()

        assertEquals(EntityType.ROUTINE, change.entityType)
        assertEquals(Operation.UPSERT, change.operation)
    }

    @Test
    fun validate_rejectsEmptyAndOversizedBatches() {
        assertFailsWith<ApiException> { SyncValidator.validateBatch(emptyList(), limits) }
        val oversized = List(4) { upsert("routine", routineTitle("x")) }
        assertFailsWith<ApiException> { SyncValidator.validateBatch(oversized, limits) }
    }

    @Test
    fun validate_rejectsUnknownTypesAndMalformedIds() {
        assertFailsWith<ApiException> { validate(upsert("habit", routineTitle("x"))) }
        assertFailsWith<ApiException> { validate(upsert("routine", routineTitle("x"), entityId = "nope")) }
        assertFailsWith<ApiException> { validate(upsert("routine", routineTitle("x"), changeId = "nope")) }
    }

    @Test
    fun validate_requiresAPayloadForUpsertAndNoneForDelete() {
        assertFailsWith<ApiException> { validate(upsert("routine", payload = null)) }
        assertFailsWith<ApiException> { validate(raw("routine", "DELETE", payload = routineTitle("x"))) }
        assertEquals(Operation.DELETE, validate(raw("routine", "DELETE", payload = null)).single().operation)
    }

    @Test
    fun validate_rejectsNegativeBaseVersionAndOversizedPayload() {
        assertFailsWith<ApiException> { validate(upsert("routine", routineTitle("x"), baseVersion = -1)) }
        val huge = buildJsonObject { put("title", "x".repeat(400)) }
        assertFailsWith<ApiException> { validate(upsert("routine", huge)) }
    }

    @Test
    fun payloadRules_checkRequiredFieldsAndTypes() {
        assertFailsWith<ApiException> { validate(upsert("routine", buildJsonObject {})) }
        assertFailsWith<ApiException> { validate(upsert("routine", buildJsonObject { put("title", 42) })) }
        assertFailsWith<ApiException> { validate(upsert("routine", buildJsonObject { put("title", "   ") })) }
        assertFailsWith<ApiException> {
            validate(upsert("routine_step", buildJsonObject { put("title", "Brush") }))
        }
        val tooLong = buildJsonObject {
            put("routineId", entityId)
            put("title", "Brush")
            put("durationSeconds", 90_000)
        }
        assertFailsWith<ApiException> { validate(upsert("routine_step", tooLong)) }
    }

    @Test
    fun payloadRules_scheduleNeedsExactlyOneTargetAndAKnownZone() {
        assertFailsWith<ApiException> { validate(upsert("schedule", schedule())) }
        assertFailsWith<ApiException> {
            validate(upsert("schedule", schedule(routineId = entityId, taskId = entityId)))
        }
        assertFailsWith<ApiException> {
            validate(upsert("schedule", schedule(routineId = entityId, zoneId = "Mars/Olympus")))
        }
        assertEquals(1, validate(upsert("schedule", schedule(routineId = entityId))).size)
    }

    private fun schedule(routineId: String? = null, taskId: String? = null, zoneId: String = "Europe/Berlin") =
        buildJsonObject {
            put("kind", "DAILY")
            put("timeOfDayMinutes", 540)
            put("startDate", "2026-03-10")
            put("zoneId", zoneId)
            if (routineId != null) put("routineId", routineId)
            if (taskId != null) put("taskId", taskId)
        }

    private fun routineTitle(title: String) = buildJsonObject { put("title", title) }

    private fun upsert(
        type: String,
        payload: JsonObject? = routineTitle("x"),
        entityId: String = this.entityId,
        changeId: String = this.changeId,
        baseVersion: Long = 0,
    ) = raw(type, "UPSERT", payload, entityId, changeId, baseVersion)

    private fun raw(
        type: String,
        operation: String,
        payload: JsonObject?,
        entityId: String = this.entityId,
        changeId: String = this.changeId,
        baseVersion: Long = 0,
    ) = RawChange(changeId, type, entityId, operation, baseVersion, payload)

    private fun validate(change: RawChange) = SyncValidator.validateBatch(listOf(change), limits)
}
