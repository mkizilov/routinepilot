package com.routinepilot.data.sync

import com.routinepilot.domain.sync.BaseState
import com.routinepilot.domain.sync.MutationKind
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.PlannedChange
import com.routinepilot.domain.sync.SubmitResult
import com.routinepilot.domain.sync.SyncType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SyncWireTest {
    private val entityId = UUID.fromString("00000000-0000-4000-8000-0000000000aa")
    private val changeId = UUID.fromString("00000000-0000-4000-8000-0000000000bb")

    @Test
    fun numbersAndBooleans_areSentAsJsonValues_andTextFieldsStayText() {
        val payload =
            mapOf(
                "title" to "Brush",
                "position" to "2",
                "isCompleted" to "true",
                "description" to null,
            ).toPayload()

        assertEquals(
            2L,
            payload
                .getValue("position")
                .jsonPrimitive.content
                .toLong(),
        )
        assertTrue(!payload.getValue("position").jsonPrimitive.isString)
        assertEquals(true, payload.getValue("isCompleted").jsonPrimitive.booleanOrNull)
        assertTrue(payload.getValue("title").jsonPrimitive.isString)
        assertEquals(JsonNull, payload.getValue("description"))
    }

    @Test
    fun payloadRoundTrip_restoresTheSameCanonicalText() {
        val fields = mapOf("title" to "Brush", "position" to "2", "isCompleted" to "false", "routineId" to null)

        assertEquals(fields, fields.toPayload().toFieldMap())
    }

    @Test
    fun deleteRequest_hasNoPayload() {
        val wire = PlannedChange(changeId, ObjectKey(SyncType.TASK, entityId), MutationKind.DELETE, 3, null).toWire()

        assertEquals("DELETE", wire.operation)
        assertNull(wire.payload)
        assertEquals("task", wire.entityType)
        assertEquals(3L, wire.baseVersion)
    }

    @Test
    fun upsertRequest_namesTheWireEntityType() {
        val step =
            PlannedChange(
                changeId,
                ObjectKey(SyncType.ROUTINE_STEP, entityId),
                MutationKind.UPSERT,
                1,
                mapOf("routineId" to entityId.toString(), "title" to "Brush", "position" to "1"),
            ).toWire()

        assertEquals("routine_step", step.entityType)
        assertEquals("UPSERT", step.operation)
        assertEquals(changeId.toString(), step.changeId)
    }

    @Test
    fun serverLogEntry_becomesAServerChange_withItsFields() {
        val record =
            ChangeRecordDto(
                seq = 9,
                changeId = changeId.toString(),
                entityType = "task",
                entityId = entityId.toString(),
                operation = "UPSERT",
                version = 4,
                payload = mapOf("title" to "Ferns", "isCompleted" to "true").toPayload(),
            )

        val change = checkNotNull(record.toServerChange())

        assertEquals(ObjectKey(SyncType.TASK, entityId), change.key)
        assertEquals(4L, change.version)
        assertEquals(mapOf("title" to "Ferns", "isCompleted" to "true"), change.fields)
        assertTrue(!change.deleted)
    }

    @Test
    fun entityTypesTheAppDoesNotSync_areSkipped() {
        val preference =
            ChangeRecordDto(
                seq = 10,
                changeId = changeId.toString(),
                entityType = "preference",
                entityId = entityId.toString(),
                operation = "UPSERT",
                version = 1,
            )

        assertNull(preference.toServerChange())
    }

    @Test
    fun conflictAnswer_carriesTheServerState() {
        val result =
            ResultDto(
                changeId = changeId.toString(),
                status = "CONFLICT",
                serverVersion = 5,
                serverDeleted = false,
                serverPayload = mapOf("title" to "Evening").toPayload(),
            ).toSubmitResult()

        assertEquals(SubmitResult.Conflict(BaseState(5, deleted = false, fields = mapOf("title" to "Evening"))), result)
    }

    @Test
    fun acceptedAndDuplicateAnswers_carryTheVersion() {
        val accepted = ResultDto(changeId.toString(), "ACCEPTED", version = 2, seq = 7).toSubmitResult()
        val duplicate = ResultDto(changeId.toString(), "DUPLICATE", version = 2, seq = 7).toSubmitResult()

        assertEquals(SubmitResult.Accepted(2, duplicate = false), accepted)
        assertEquals(SubmitResult.Accepted(2, duplicate = true), duplicate)
    }

    @Test
    fun serverUploadResponse_decodes_withItsRealFieldNames() {
        val body =
            """
            {"results":[{"changeId":"$changeId","status":"ACCEPTED","entityType":"routine",
            "entityId":"$entityId","version":2,"seq":5}]}
            """.trimIndent()

        val decoded = ApiJson.decodeFromString<UploadResponseDto>(body)

        assertEquals(SubmitResult.Accepted(2, duplicate = false), decoded.results.single().toSubmitResult())
    }
}
