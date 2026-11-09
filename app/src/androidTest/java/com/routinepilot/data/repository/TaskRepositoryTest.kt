package com.routinepilot.data.repository

import com.routinepilot.domain.model.Task
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import com.routinepilot.infrastructure.database.TEST_TIME
import com.routinepilot.infrastructure.database.createInMemoryDatabase
import com.routinepilot.infrastructure.database.newRoutine
import com.routinepilot.infrastructure.database.scalar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TaskRepositoryTest {
    private lateinit var db: RoutinePilotDatabase
    private lateinit var repository: TaskRepositoryImpl

    @Before
    fun setUp() {
        db = createInMemoryDatabase()
        repository = TaskRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveTask_updatesCompletionAndKeepsCreatedAt() =
        runBlocking {
            val task = newTask(title = "Buy filters")
            repository.saveTask(task)

            repository.saveTask(task.copy(isCompleted = true))

            assertEquals(listOf(task.copy(isCompleted = true)), repository.observeTasks().first())
        }

    @Test
    fun observeTasks_ordersByPosition() =
        runBlocking {
            repository.saveTask(newTask(title = "Second", position = 1))
            repository.saveTask(newTask(title = "First", position = 0))

            assertEquals(listOf("First", "Second"), repository.observeTasks().first().map { it.title })
        }

    @Test
    fun deleteTask_hidesTaskFromObservers() =
        runBlocking {
            val task = newTask(title = "Temporary")
            repository.saveTask(task)

            repository.deleteTask(task.id)

            assertEquals(emptyList<Task>(), repository.observeTasks().first())
            assertEquals(1L, db.scalar("SELECT COUNT(*) FROM tasks WHERE deletedAt IS NOT NULL"))
        }

    @Test
    fun taskLinkedToRoutine_isStoredWithRoutineId() =
        runBlocking {
            val routine = newRoutine()
            RoutineRepositoryImpl(db).saveRoutine(routine, emptyList())
            val task = newTask(title = "Linked", routineId = routine.id)

            repository.saveTask(task)

            assertEquals(
                routine.id,
                repository
                    .observeTasks()
                    .first()
                    .single()
                    .routineId,
            )
        }

    private fun newTask(
        title: String,
        position: Int = 0,
        routineId: UUID? = null,
    ) = Task(
        id = UUID.randomUUID(),
        title = title,
        isCompleted = false,
        routineId = routineId,
        position = position,
        createdAt = TEST_TIME,
    )
}
