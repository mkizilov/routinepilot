package com.routinepilot.domain.sync

import com.routinepilot.testing.routineFields
import com.routinepilot.testing.routineKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OfflineMergeTest {
    private val key = routineKey

    @Test
    fun editsToDifferentFields_bothSurvive() {
        val base = live(version = 2, routineFields(title = "Morning", description = "Walk"))
        val queued = titleEdit(seq = 1, from = "Morning", to = "Evening", seen = 1)

        val result = merge(base, queued)

        assertEquals("Evening", result.fields["title"])
        assertEquals("Walk", result.fields["description"])
        assertTrue(result.conflicts.isEmpty())
        assertEquals(2L, result.planned.single().baseVersion)
    }

    @Test
    fun editsToTheSameField_theLaterOneWins_andReportsTheValueItReplaced() {
        val base = live(version = 2, routineFields(title = "Evening"))
        val queued = titleEdit(seq = 1, from = "Morning", to = "Dinner", seen = 1)

        val result = merge(base, queued)

        assertEquals("Dinner", result.fields["title"])
        assertEquals(
            listOf(Conflict.Superseded(key, mutationId(1), "title", replacedValue = "Evening", keptValue = "Dinner")),
            result.conflicts,
        )
    }

    @Test
    fun editThatAlreadyMatchesTheServer_isNotAConflict() {
        val base = live(version = 2, routineFields(title = "Dinner"))
        val queued = titleEdit(seq = 1, from = "Morning", to = "Dinner", seen = 1)

        val result = merge(base, queued)

        assertTrue(result.conflicts.isEmpty())
        assertEquals("Dinner", result.fields["title"])
    }

    @Test
    fun deleteAfterAnotherDeviceEdited_isHeldForTheUser() {
        val base = live(version = 2, routineFields(title = "Evening"))

        val result = merge(base, deletion(seq = 1, seen = 1))

        assertEquals(listOf(Conflict.DeleteOfEdited(key, mutationId(1), remoteVersion = 2)), result.conflicts)
        assertTrue(result.blocked)
        assertTrue(result.planned.isEmpty())
        assertTrue(result.exists)
    }

    @Test
    fun deleteWithNoEditSinceTheViewWasSeen_isSentAgainstThatVersion() {
        val base = live(version = 2, routineFields(title = "Morning"))

        val result = merge(base, deletion(seq = 1, seen = 2))

        assertFalse(result.blocked)
        assertEquals(MutationKind.DELETE, result.planned.single().kind)
        assertEquals(2L, result.planned.single().baseVersion)
        assertFalse(result.exists)
        assertEquals(3L, result.nextVersion)
    }

    @Test
    fun editAfterAnotherDeviceDeleted_isHeldForTheUser() {
        val base = BaseState(version = 2, deleted = true, fields = emptyMap())
        val queued = titleEdit(seq = 1, from = "Morning", to = "Evening", seen = 1)

        val result = merge(base, queued)

        assertEquals(listOf(Conflict.EditOfDeleted(key, mutationId(1))), result.conflicts)
        assertTrue(result.blocked)
        assertFalse(result.exists)
    }

    @Test
    fun restoreOverATombstone_writesTheWholeRecord() {
        val base = BaseState(version = 2, deleted = true, fields = emptyMap())
        val restore = whole(seq = 1, snapshot = routineFields(title = "Morning"), seen = 2)

        val result = merge(base, restore)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(2L, result.planned.single().baseVersion)
        assertEquals(routineFields(title = "Morning"), result.fields)
        assertTrue(result.exists)
    }

    @Test
    fun deleteOfARecordThatIsAlreadyDeleted_isRedundantAndNotSent() {
        val base = BaseState(version = 2, deleted = true, fields = emptyMap())

        val result = merge(base, deletion(seq = 1, seen = 2))

        assertEquals(listOf(mutationId(1)), result.redundant)
        assertTrue(result.planned.isEmpty())
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun queuedChanges_replayInLocalOrder_notInListOrder() {
        val base = live(version = 1, routineFields(title = "Morning"))
        val first = titleEdit(seq = 1, from = "Morning", to = "Evening", seen = 1)
        val second = titleEdit(seq = 2, from = "Evening", to = "Night", seen = 1)

        val result = OfflineMerge.merge(key, base, listOf(second, first))

        assertEquals(listOf("Evening", "Night"), result.planned.map { it.payload?.get("title") })
        assertEquals(listOf(1L, 2L), result.planned.map { it.baseVersion })
        assertEquals("Night", result.fields["title"])
    }

    @Test
    fun result_doesNotDependOnDeviceClocks() {
        val base = live(version = 2, routineFields(title = "Evening"))
        val early = titleEdit(seq = 1, from = "Morning", to = "Dinner", seen = 1).copy(createdAtMillis = 1)
        val late = early.copy(createdAtMillis = 9_999_999_999_999)

        assertEquals(merge(base, early), merge(base, late))
    }

    @Test
    fun noServerRecordAndNoQueue_doesNotExist() {
        val result = OfflineMerge.merge(key, base = null, pending = emptyList())

        assertFalse(result.exists)
        assertTrue(result.planned.isEmpty())
        assertEquals(0L, result.nextVersion)
    }

    private fun merge(base: BaseState?, vararg queued: Mutation) = OfflineMerge.merge(key, base, queued.toList())

    private fun live(version: Long, fields: FieldMap) = BaseState(version, deleted = false, fields = fields)

    private fun mutationId(seq: Long): UUID = UUID(MUTATION_NAMESPACE, seq)

    private fun titleEdit(seq: Long, from: String, to: String, seen: Long) =
        edit(seq, before = routineFields(title = from), after = routineFields(title = to), seen = seen)

    private fun edit(seq: Long, before: FieldMap, after: FieldMap, seen: Long) = Mutation(
        mutationId = mutationId(seq),
        key = key,
        localSequence = seq,
        seenVersion = seen,
        kind = MutationKind.UPSERT,
        expectation = Expectation.PRESENT,
        snapshot = after,
        changes = diffOf(before, after),
        createdAtMillis = 0,
    )

    private fun whole(seq: Long, snapshot: FieldMap, seen: Long) = Mutation(
        mutationId = mutationId(seq),
        key = key,
        localSequence = seq,
        seenVersion = seen,
        kind = MutationKind.UPSERT,
        expectation = Expectation.ABSENT,
        snapshot = snapshot,
        changes = emptyMap(),
        createdAtMillis = 0,
    )

    private fun deletion(seq: Long, seen: Long) = Mutation(
        mutationId = mutationId(seq),
        key = key,
        localSequence = seq,
        seenVersion = seen,
        kind = MutationKind.DELETE,
        expectation = Expectation.PRESENT,
        snapshot = emptyMap(),
        changes = emptyMap(),
        createdAtMillis = 0,
    )

    private fun diffOf(before: FieldMap, after: FieldMap): Map<String, FieldChange> =
        (before.keys + after.keys).mapNotNull { name ->
            if (before[name] == after[name]) null else name to FieldChange(before[name], after[name])
        }.toMap()

    private companion object {
        const val MUTATION_NAMESPACE = 7L
    }
}
