package com.routinepilot.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.createInMemoryDatabase
import com.routinepilot.infrastructure.database.newRoutine
import com.routinepilot.infrastructure.database.newStep
import com.routinepilot.infrastructure.database.scalar
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RoutineRepositoryTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var repository: RoutineRepositoryImpl

    private companion object {
        const val DISTINCT_TIMESTAMP_MILLIS = 5L
    }

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        repository = RoutineRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveRoutine_isReadBackWithAllFields() = runBlocking {
        val routine = newRoutine(title = "Morning")

        repository.saveRoutine(routine, emptyList())

        assertEquals(listOf(routine), repository.observeRoutines().first())
    }

    @Test
    fun saveRoutine_updatesExistingRowInPlace() = runBlocking {
        val routine = newRoutine(title = "Morning")
        repository.saveRoutine(routine, emptyList())

        repository.saveRoutine(routine.copy(title = "Evening"), emptyList())

        assertEquals(listOf("Evening"), repository.observeRoutines().first().map { it.title })
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM routines"))
    }

    @Test
    fun observeRoutines_ordersByPositionThenId() = runBlocking {
        val first = newRoutine(title = "First", position = 0)
        val second = newRoutine(title = "Second", position = 1)
        val third = newRoutine(title = "Third", position = 2)
        repository.saveRoutine(third, emptyList())
        repository.saveRoutine(first, emptyList())
        repository.saveRoutine(second, emptyList())

        assertEquals(
            listOf("First", "Second", "Third"),
            repository.observeRoutines().first().map { it.title },
        )
    }

    @Test
    fun observeSteps_ordersStepsByPosition() = runBlocking {
        val routine = newRoutine()
        val steps = listOf(
            newStep(routine.id, "Brush", position = 2),
            newStep(routine.id, "Stretch", position = 0),
            newStep(routine.id, "Water", position = 1),
        )

        repository.saveRoutine(routine, steps)

        assertEquals(
            listOf("Stretch", "Water", "Brush"),
            repository.observeSteps(routine.id).first().map { it.title },
        )
    }

    @Test
    fun observeRoutines_emitsWhenDataChanges() = runBlocking {
        val received = Channel<List<String>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            repository.observeRoutines().collect { list -> received.send(list.map { it.title }) }
        }

        assertEquals(emptyList<String>(), received.receive())
        repository.saveRoutine(newRoutine(title = "Morning"), emptyList())
        assertEquals(listOf("Morning"), received.receive())

        collector.cancel()
    }

    @Test
    fun deleteRoutine_hidesRoutineAndStepsButKeepsTombstones() = runBlocking {
        val routine = newRoutine()
        repository.saveRoutine(routine, listOf(newStep(routine.id, "Brush", position = 0)))

        repository.deleteRoutine(routine.id)

        assertEquals(emptyList<Any>(), repository.observeRoutines().first())
        assertEquals(emptyList<Any>(), repository.observeSteps(routine.id).first())
        assertNull(repository.observeRoutine(routine.id).first())
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM routines WHERE deletedAt IS NOT NULL"))
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM routine_steps WHERE deletedAt IS NOT NULL"))
    }

    @Test
    fun deleteStep_tombstonesOnlyThatStep() = runBlocking {
        val routine = newRoutine()
        val keep = newStep(routine.id, "Keep", position = 0)
        val drop = newStep(routine.id, "Drop", position = 1)
        repository.saveRoutine(routine, listOf(keep, drop))

        repository.deleteStep(drop.id)

        assertEquals(listOf("Keep"), repository.observeSteps(routine.id).first().map { it.title })
    }

    @Test
    fun saveRoutine_rollsBackRoutineWhenStepWriteFails() = runBlocking {
        val routine = newRoutine(title = "Atomic")
        val orphanStep = newStep(routineId = UUID.randomUUID(), title = "Orphan", position = 0)

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { repository.saveRoutine(routine, listOf(orphanStep)) }
        }

        assertNull(repository.observeRoutine(routine.id).first())
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM routines"))
    }

    @Test
    fun saveRoutine_softDeletesActiveStepsMissingFromTheList() = runBlocking {
        val routine = newRoutine()
        val keep = newStep(routine.id, "Keep", position = 0)
        val drop = newStep(routine.id, "Drop", position = 1)
        repository.saveRoutine(routine, listOf(keep, drop))

        repository.saveRoutine(routine, listOf(keep.copy(title = "Kept")))

        assertEquals(listOf("Kept"), repository.observeSteps(routine.id).first().map { it.title })
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM routine_steps WHERE deletedAt IS NOT NULL"))
    }

    @Test
    fun stepDetails_roundTripDescriptionAndDuration() = runBlocking {
        val routine = newRoutine()
        val step = newStep(routine.id, "Stretch", position = 0).copy(description = "Hips", durationSeconds = 600)
        repository.saveRoutine(routine.copy(description = "Daily"), listOf(step))

        assertEquals("Daily", repository.observeRoutine(routine.id).first()?.description)
        assertEquals(listOf(step), repository.observeSteps(routine.id).first())
    }

    @Test
    fun restoreRoutine_restoresRoutineAndOnlyStepsDeletedWithIt() = runBlocking {
        val routine = newRoutine()
        val earlier = newStep(routine.id, "Earlier", position = 0)
        val kept = newStep(routine.id, "Kept", position = 1)
        repository.saveRoutine(routine, listOf(earlier, kept))
        repository.deleteStep(earlier.id)
        // Tombstones are matched by timestamp, so the two deletions need distinct milliseconds.
        delay(DISTINCT_TIMESTAMP_MILLIS)
        repository.deleteRoutine(routine.id)

        repository.restoreRoutine(routine.id)

        assertEquals(listOf(routine), repository.observeRoutines().first())
        assertEquals(listOf("Kept"), repository.observeSteps(routine.id).first().map { it.title })
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM routine_steps WHERE deletedAt IS NOT NULL"))
    }

    @Test
    fun duplicateRoutine_copiesRoutineAndStepsAsNewRows() = runBlocking {
        val source = newRoutine(title = "Morning", position = 0)
        val brush = newStep(source.id, "Brush", position = 0).copy(durationSeconds = 120)
        repository.saveRoutine(source, listOf(brush))

        val copyId = repository.duplicateRoutine(source.id)!!

        val copy = repository.observeRoutine(copyId).first()!!
        assertEquals("Morning (copy)", copy.title)
        assertEquals(1, copy.position)
        val copySteps = repository.observeSteps(copyId).first()
        assertEquals(listOf("Brush"), copySteps.map { it.title })
        assertEquals(120, copySteps.single().durationSeconds)
        assertEquals(copyId, copySteps.single().routineId)
        assertNotEquals(brush.id, copySteps.single().id)
        assertEquals(listOf(brush), repository.observeSteps(source.id).first())
        assertEquals(2, repository.observeRoutines().first().size)
    }

    @Test
    fun duplicateRoutine_ofMissingRoutine_returnsNull() = runBlocking {
        assertNull(repository.duplicateRoutine(UUID.randomUUID()))
    }

    @Test
    fun reads_runOffTheMainThreadSafely() = runBlocking {
        val routine = newRoutine()
        withContext(Dispatchers.IO) { repository.saveRoutine(routine, emptyList()) }

        assertEquals(listOf(routine), withContext(Dispatchers.IO) { repository.observeRoutines().first() })
    }
}
