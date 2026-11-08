package com.routinepilot.domain.sync

import com.routinepilot.testing.FakeSyncServer
import com.routinepilot.testing.SimulatedDevice
import com.routinepilot.testing.routineFields
import com.routinepilot.testing.routineKey
import com.routinepilot.testing.stepFields
import com.routinepilot.testing.stepKey
import com.routinepilot.testing.stepOneId
import com.routinepilot.testing.stepTwoId
import com.routinepilot.testing.taskFields
import com.routinepilot.testing.taskId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Two or more devices edit offline, then sync in different orders. Every scenario ends by pulling on every device, and
 * checks that the server and all devices agree.
 */
class OfflineSyncSimulationTest {
    private val key = routineKey

    @Test
    fun editsToDifferentFields_surviveInEitherSyncOrder() {
        for (aFirst in listOf(true, false)) {
            runBlocking {
                val server = seededServer()
                val a = device("A", server, 2)
                val b = device("B", server, 3)
                a.save(key, routineFields(title = "Evening", description = "Old"))
                b.save(key, routineFields(title = "Morning", description = "New walk"))

                first(aFirst, a, b).sync(key)
                second(aFirst, a, b).sync(key)
                a.pull()
                b.pull()

                val head = checkNotNull(server.head(key))
                assertEquals(3L, head.version)
                assertEquals(routineFields(title = "Evening", description = "New walk"), head.fields)
                assertEquals(head.fields, a.appData(key))
                assertEquals(head.fields, b.appData(key))
            }
        }
    }

    @Test
    fun editsToTheSameField_theLaterArrivalAtTheServerWins_whateverTheClocks() {
        for (aFirst in listOf(true, false)) {
            runBlocking {
                val server = seededServer()
                val a = device("A", server, 2).also { it.clockMillis = Long.MAX_VALUE / 2 }
                val b = device("B", server, 3)
                a.save(key, routineFields(title = "Evening", description = "Old"))
                b.save(key, routineFields(title = "Dinner", description = "Old"))

                first(aFirst, a, b).sync(key)
                val late = second(aFirst, a, b).sync(key)
                a.pull()
                b.pull()

                val winner = if (aFirst) "Dinner" else "Evening"
                val replaced = if (aFirst) "Evening" else "Dinner"
                assertEquals(winner, server.head(key)?.fields?.get("title"))
                assertEquals(
                    listOf(Conflict.Superseded(key, late.conflicts.single().mutationId, "title", replaced, winner)),
                    late.conflicts,
                )
                assertEquals(winner, a.appData(key)?.get("title"))
                assertEquals(winner, b.appData(key)?.get("title"))
            }
        }
    }

    @Test
    fun deleteOnOneDevice_againstAnEditOnAnother_isHeld_andKeepingTheEditRestoresTheRecord() {
        runBlocking {
            val server = seededServer()
            val a = device("A", server, 2)
            val b = device("B", server, 3)
            a.delete(key)
            b.save(key, routineFields(title = "Evening", description = "Old"))
            b.sync(key)

            val held = a.sync(key)
            assertTrue(held.blocked)
            assertTrue(held.exists)
            a.resolve(held.conflicts.single(), Decision.KEEP_EDIT)
            a.sync(key)

            val head = checkNotNull(server.head(key))
            assertFalse(head.deleted)
            assertEquals(routineFields(title = "Evening", description = "Old"), head.fields)
            assertEquals(head.fields, a.appData(key))
        }
    }

    @Test
    fun deleteOnOneDevice_againstAnEditOnAnother_canBeDeletedAnyway() {
        runBlocking {
            val server = seededServer()
            val a = device("A", server, 2)
            val b = device("B", server, 3)
            a.delete(key)
            b.save(key, routineFields(title = "Evening", description = "Old"))
            b.sync(key)

            val held = a.sync(key)
            a.resolve(held.conflicts.single(), Decision.DELETE_ANYWAY)
            a.sync(key)

            val head = checkNotNull(server.head(key))
            assertTrue(head.deleted)
            assertEquals(3L, head.version)
            assertNull(a.appData(key))
        }
    }

    @Test
    fun editOnOneDevice_againstADeleteOnAnother_canBeRestoredOrDiscarded() {
        for (restore in listOf(true, false)) {
            runBlocking {
                val server = seededServer()
                val a = device("A", server, 2)
                val b = device("B", server, 3)
                a.save(key, routineFields(title = "Evening", description = "Old"))
                b.delete(key)
                b.sync(key)

                val held = a.sync(key)
                assertTrue(held.blocked)
                assertFalse(held.exists)
                a.resolve(held.conflicts.single(), if (restore) Decision.RESTORE_EDITS else Decision.DISCARD_EDITS)
                a.sync(key)

                val head = checkNotNull(server.head(key))
                if (restore) {
                    assertFalse(head.deleted)
                    assertEquals(3L, head.version)
                    assertEquals(routineFields(title = "Evening", description = "Old"), head.fields)
                    assertEquals(head.fields, a.appData(key))
                } else {
                    assertTrue(head.deleted)
                    assertEquals(2L, head.version)
                    assertNull(a.appData(key))
                }
            }
        }
    }

    @Test
    fun reorderingSteps_onTwoDevices_settlesByServerOrder() {
        for (aFirst in listOf(true, false)) {
            runBlocking {
                val server = FakeSyncServer()
                val creator = SimulatedDevice("creator", server, namespace = 1)
                creator.save(stepKey(stepOneId), stepFields(title = "Brush", position = 1))
                creator.save(stepKey(stepTwoId), stepFields(title = "Floss", position = 2))
                creator.sync(stepKey(stepOneId))
                creator.sync(stepKey(stepTwoId))
                val a = device("A", server, 2)
                val b = device("B", server, 3)

                // A swaps the two steps. B moves the first step to the end.
                a.save(stepKey(stepOneId), stepFields(title = "Brush", position = 2))
                a.save(stepKey(stepTwoId), stepFields(title = "Floss", position = 1))
                b.save(stepKey(stepOneId), stepFields(title = "Brush", position = 3))

                val reorderSecond = if (aFirst) b else a
                val reorderFirst = if (aFirst) a else b
                reorderFirst.sync(stepKey(stepOneId))
                reorderFirst.sync(stepKey(stepTwoId))
                val late = reorderSecond.sync(stepKey(stepOneId))
                reorderSecond.sync(stepKey(stepTwoId))
                a.pull()
                b.pull()

                // The move that reaches the server last sets the first step's position. The swapped step is untouched.
                assertEquals(if (aFirst) "3" else "2", positionOf(server, stepKey(stepOneId)))
                assertEquals("1", positionOf(server, stepKey(stepTwoId)))
                assertEquals(positionOf(server, stepKey(stepOneId)), a.appData(stepKey(stepOneId))?.get("position"))
                assertEquals(positionOf(server, stepKey(stepOneId)), b.appData(stepKey(stepOneId))?.get("position"))
                assertEquals("position", (late.conflicts.single() as Conflict.Superseded).field)
            }
        }
    }

    @Test
    fun aRetryAfterALostResponse_isAcknowledgedAsADuplicate_andAppliesOnce() {
        runBlocking {
            val server = seededServer()
            val lossy = LostFirstResponse(server)
            val a = SimulatedDevice("A", server, namespace = 2, transport = lossy).also { it.pull() }
            a.save(key, routineFields(title = "Evening", description = "Old"))

            assertThrows(IOException::class.java) { runBlocking { a.sync(key) } }
            assertEquals(2, server.logSize())

            a.sync(key)
            assertEquals(2, server.logSize())
            assertEquals(0, a.queued(key))
            assertEquals("Evening", server.head(key)?.fields?.get("title"))
        }
    }

    @Test
    fun aRetryAfterALostResponse_stillWorks_whenAnotherDeviceChangedTheRecordInTheMeantime() {
        runBlocking {
            val server = seededServer()
            val lossy = LostFirstResponse(server)
            val a = SimulatedDevice("A", server, namespace = 2, transport = lossy).also { it.pull() }
            val b = device("B", server, 3)
            a.save(key, routineFields(title = "Evening", description = "Old"))
            assertThrows(IOException::class.java) { runBlocking { a.sync(key) } }

            b.save(key, routineFields(title = "Morning", description = "New walk"))
            b.sync(key)
            a.sync(key)
            a.pull()

            val head = checkNotNull(server.head(key))
            assertEquals(3L, head.version)
            assertEquals(routineFields(title = "Evening", description = "New walk"), head.fields)
            assertEquals(head.fields, a.appData(key))
            assertEquals(3, server.logSize())
        }
    }

    @Test
    fun threeDevices_editingDifferentFields_convergeInEverySyncOrder() {
        val orders = listOf(
            listOf("A", "B", "C"), listOf("A", "C", "B"), listOf("B", "A", "C"),
            listOf("B", "C", "A"), listOf("C", "A", "B"), listOf("C", "B", "A"),
        )
        for (order in orders) {
            runBlocking {
                val server = seededServer()
                val devices = mapOf(
                    "A" to device("A", server, 2),
                    "B" to device("B", server, 3),
                    "C" to device("C", server, 4),
                )
                devices.getValue("A").save(key, routineFields(title = "Evening", description = "Old", position = 1))
                val bView = routineFields(title = "Morning", description = "New walk", position = 1)
                devices.getValue("B").save(key, bView)
                devices.getValue("C").save(key, routineFields(title = "Morning", description = "Old", position = 2))

                order.forEach { devices.getValue(it).sync(key) }
                devices.values.forEach { it.pull() }

                val expected = routineFields(title = "Evening", description = "New walk", position = 2)
                assertEquals(expected, checkNotNull(server.head(key)).fields)
                devices.values.forEach { assertEquals(expected, it.appData(key)) }
            }
        }
    }

    @Test
    fun offlineEditsOnOneDevice_reachTheServerInTheirLocalOrder() {
        runBlocking {
            val server = seededServer()
            val a = device("A", server, 2)
            a.save(key, routineFields(title = "Evening", description = "Old"))
            a.save(key, routineFields(title = "Night", description = "Old"))

            a.sync(key)

            assertEquals(3, server.logSize())
            assertEquals(listOf("Evening", "Night"), server.changesAfter(1).map { it.fields["title"] })
            assertEquals("Night", server.head(key)?.fields?.get("title"))
        }
    }

    @Test
    fun savingAnUnchangedRecord_queuesNothing() {
        runBlocking {
            val server = seededServer()
            val a = device("A", server, 2)

            a.save(key, routineFields(title = "Morning", description = "Old"))

            assertEquals(0, a.queued(key))
        }
    }

    @Test
    fun taskEdits_onDifferentFields_surviveTogether() {
        runBlocking {
            val taskKey = ObjectKey(SyncType.TASK, taskId)
            val server = FakeSyncServer()
            val creator = SimulatedDevice("creator", server, namespace = 1)
            creator.save(taskKey, taskFields(title = "Water plants", isCompleted = false))
            creator.sync(taskKey)
            val a = device("A", server, 2)
            val b = device("B", server, 3)

            a.save(taskKey, taskFields(title = "Water the ferns", isCompleted = false))
            b.save(taskKey, taskFields(title = "Water plants", isCompleted = true))
            a.sync(taskKey)
            b.sync(taskKey)

            assertEquals(taskFields(title = "Water the ferns", isCompleted = true), server.head(taskKey)?.fields)
        }
    }

    private suspend fun seededServer(): FakeSyncServer {
        val server = FakeSyncServer()
        val creator = SimulatedDevice("creator", server, namespace = 1)
        creator.save(key, routineFields(title = "Morning", description = "Old"))
        creator.sync(key)
        return server
    }

    private suspend fun device(name: String, server: FakeSyncServer, namespace: Long): SimulatedDevice =
        SimulatedDevice(name, server, namespace).also { it.pull() }

    private fun first(aFirst: Boolean, a: SimulatedDevice, b: SimulatedDevice) = if (aFirst) a else b

    private fun second(aFirst: Boolean, a: SimulatedDevice, b: SimulatedDevice) = if (aFirst) b else a

    private fun positionOf(server: FakeSyncServer, key: ObjectKey): String? =
        server.head(key)?.fields?.get("position")

    /** Applies the first submission but loses its response, as a dropped connection would. */
    private class LostFirstResponse(private val server: FakeSyncServer) : SyncTransport {
        private var dropped = false

        override suspend fun submit(change: PlannedChange): SubmitResult {
            val result = server.submit(change)
            if (!dropped) {
                dropped = true
                throw IOException("response lost")
            }
            return result
        }
    }
}
