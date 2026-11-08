package com.routinepilot.domain.sync

import com.routinepilot.testing.FakeSyncRemote
import com.routinepilot.testing.FakeSyncServer
import com.routinepilot.testing.SimulatedDevice
import com.routinepilot.testing.routineFields
import com.routinepilot.testing.routineKey
import com.routinepilot.testing.taskFields
import com.routinepilot.testing.taskId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The sync engine with two or more simulated devices. Each scenario runs the
 * same pass the background job runs (`cycle`),
 * and each ends by checking that the server and every device agree.
 */
class SyncEngineTest {
    private val key = routineKey

    @Test
    fun offlineEditsToDifferentFields_convergeInEitherReconnectOrder() {
        for (aFirst in listOf(true, false)) {
            runBlocking {
                val server = FakeSyncServer()
                seededRoutine(server)
                val a = device("A", server, 2)
                val b = device("B", server, 3)
                a.save(key, routineFields(title = "Evening", description = "Old"))
                b.save(key, routineFields(title = "Morning", description = "New walk"))

                first(aFirst, a, b).cycle()
                second(aFirst, a, b).cycle()
                a.cycle()
                b.cycle()

                val expected = routineFields(title = "Evening", description = "New walk")
                assertEquals(expected, server.head(key)?.fields)
                assertEquals(expected, a.appData(key))
                assertEquals(expected, b.appData(key))
                assertEquals(SyncPhase.UP_TO_DATE, a.syncState.value.phase)
                assertEquals(SyncPhase.UP_TO_DATE, b.syncState.value.phase)
                assertEquals(0, a.syncState.value.pendingChanges + b.syncState.value.pendingChanges)
            }
        }
    }

    @Test
    fun editsToTheSameField_theLaterArrivalWins_andTheOtherDeviceConverges() {
        for (aFirst in listOf(true, false)) {
            runBlocking {
                val server = FakeSyncServer()
                seededRoutine(server)
                val a = device("A", server, 2)
                val b = device("B", server, 3)
                a.save(key, routineFields(title = "Evening", description = "Old"))
                b.save(key, routineFields(title = "Dinner", description = "Old"))

                first(aFirst, a, b).cycle()
                second(aFirst, a, b).cycle()
                a.cycle()
                b.cycle()

                val winner = if (aFirst) "Dinner" else "Evening"
                assertEquals(winner, server.head(key)?.fields?.get("title"))
                assertEquals(winner, a.appData(key)?.get("title"))
                assertEquals(winner, b.appData(key)?.get("title"))
                assertEquals(SyncPhase.UP_TO_DATE, second(aFirst, a, b).syncState.value.phase)
            }
        }
    }

    @Test
    fun deleteOnOneDevice_againstAnEditOnAnother_isHeldForTheUser_untilKeepEdit() {
        runBlocking {
            val server = FakeSyncServer()
            seededRoutine(server)
            val a = device("A", server, 2)
            val b = device("B", server, 3)
            a.delete(key)
            b.save(key, routineFields(title = "Evening", description = "Old"))
            b.cycle()

            a.cycle()

            val state = a.syncState.value
            assertEquals(SyncPhase.NEEDS_ATTENTION, state.phase)
            val held = state.heldConflicts.single()
            assertTrue(held is Conflict.DeleteOfEdited)
            assertTrue(a.appData(key) != null)

            a.resolve(held, Decision.KEEP_EDIT)
            a.cycle()

            assertFalse(checkNotNull(server.head(key)).deleted)
            assertEquals(routineFields(title = "Evening", description = "Old"), a.appData(key))
            assertEquals(SyncPhase.UP_TO_DATE, a.syncState.value.phase)
        }
    }

    @Test
    fun unreachableServer_keepsEverything_andTheNextPassSendsIt() {
        runBlocking {
            val server = FakeSyncServer()
            seededRoutine(server)
            val a = device("A", server, 2)
            a.save(key, routineFields(title = "Evening", description = "Old"))
            a.remote.offline = true

            assertEquals(CycleResult.Retry, a.cycle())
            assertEquals(SyncPhase.WAITING_FOR_NETWORK, a.syncState.value.phase)
            assertEquals(1, a.syncState.value.pendingChanges)
            assertEquals(routineFields(title = "Evening", description = "Old"), a.appData(key))

            a.remote.offline = false
            assertEquals(CycleResult.Done, a.cycle())
            assertEquals(routineFields(title = "Evening", description = "Old"), server.head(key)?.fields)
            assertEquals(0, a.syncState.value.pendingChanges)
        }
    }

    @Test
    fun lostResponse_isResent_andAppliedOnce() {
        runBlocking {
            val server = FakeSyncServer()
            seededRoutine(server)
            val remote = FakeSyncRemote(server).also { it.dropNextResponse = true }
            val a = device("A", server, 2, remote)
            a.save(key, routineFields(title = "Evening", description = "Old"))

            assertEquals(CycleResult.Retry, a.cycle())
            assertEquals(2, server.logSize())

            assertEquals(CycleResult.Done, a.cycle())
            assertEquals(2, server.logSize())
            assertEquals(0, a.syncState.value.pendingChanges)
        }
    }

    @Test
    fun expiredSession_keepsLocalChanges_andResumesAfterSignInAgain() {
        runBlocking {
            val server = FakeSyncServer()
            seededRoutine(server)
            val a = device("A", server, 2)
            a.save(key, routineFields(title = "Evening", description = "Old"))
            a.remote.authExpired = true

            assertEquals(CycleResult.NeedsSignIn, a.cycle())
            assertEquals(SyncPhase.NEEDS_SIGN_IN, a.syncState.value.phase)
            assertEquals(1, a.syncState.value.pendingChanges)
            assertEquals(routineFields(title = "Evening", description = "Old"), a.appData(key))
            assertEquals(routineFields(title = "Morning", description = "Old"), server.head(key)?.fields)

            a.remote.authExpired = false
            assertEquals(CycleResult.Done, a.cycle())
            assertEquals(routineFields(title = "Evening", description = "Old"), server.head(key)?.fields)
            assertEquals(0, a.syncState.value.pendingChanges)
        }
    }

    @Test
    fun signedOutDevice_neverContactsTheServer_andKeepsItsChanges() {
        runBlocking {
            val server = FakeSyncServer()
            seededRoutine(server)
            val a = device("A", server, 2)
            a.save(key, routineFields(title = "Evening", description = "Old"))
            a.account.signedIn = false
            val callsBefore = a.remote.calls

            assertEquals(CycleResult.NotSignedIn, a.cycle())

            assertEquals(callsBefore, a.remote.calls)
            assertEquals(SyncPhase.NOT_SIGNED_IN, a.syncState.value.phase)
            assertEquals(1, a.syncState.value.pendingChanges)
        }
    }

    @Test
    fun refusedChange_isKept_isReported_andOtherRecordsStillSync() {
        runBlocking {
            val server = FakeSyncServer()
            seededRoutine(server)
            val refused = FakeSyncRemote(server).also { it.rejectKeys = setOf(key) }
            val a = device("A", server, 2, refused)
            val otherId = UUID.fromString("00000000-0000-4000-8000-0000000000cc")
            a.save(key, routineFields(title = "Evening", description = "Old"))
            a.save(ObjectKey(SyncType.ROUTINE, otherId), routineFields(title = "Second"))

            assertEquals(CycleResult.Done, a.cycle())

            val state = a.syncState.value
            assertEquals(SyncPhase.NEEDS_ATTENTION, state.phase)
            assertEquals(1, state.rejectedChanges)
            assertEquals(1, state.pendingChanges)
            assertEquals(routineFields(title = "Second"), server.head(ObjectKey(SyncType.ROUTINE, otherId))?.fields)
            assertEquals(routineFields(title = "Morning", description = "Old"), server.head(key)?.fields)
        }
    }

    @Test
    fun remoteCreateAndDelete_reachTheAppData() {
        runBlocking {
            val server = FakeSyncServer()
            val creator = SimulatedDevice("creator", server, namespace = 1)
            creator.save(key, routineFields(title = "Morning", description = "Old"))
            creator.cycle()
            val b = device("B", server, 3)
            assertEquals(routineFields(title = "Morning", description = "Old"), b.appData(key))

            creator.delete(key)
            creator.cycle()
            b.cycle()

            assertNull(b.appData(key))
        }
    }

    @Test
    fun taskEdited_onTwoDevices_convergesWithoutLosingEitherChange() {
        runBlocking {
            val taskKey = ObjectKey(SyncType.TASK, taskId)
            val server = FakeSyncServer()
            val creator = SimulatedDevice("creator", server, namespace = 1)
            creator.save(taskKey, taskFields(title = "Water plants", isCompleted = false))
            creator.cycle()
            val a = device("A", server, 2)
            val b = device("B", server, 3)

            a.save(taskKey, taskFields(title = "Water the ferns", isCompleted = false))
            b.save(taskKey, taskFields(title = "Water plants", isCompleted = true))
            a.cycle()
            b.cycle()
            a.cycle()

            val expected = taskFields(title = "Water the ferns", isCompleted = true)
            assertEquals(expected, server.head(taskKey)?.fields)
            assertEquals(expected, a.appData(taskKey))
            assertEquals(expected, b.appData(taskKey))
        }
    }

    private suspend fun seededRoutine(server: FakeSyncServer): SimulatedDevice {
        val creator = SimulatedDevice("creator", server, namespace = 1)
        creator.save(key, routineFields(title = "Morning", description = "Old"))
        assertEquals(CycleResult.Done, creator.cycle())
        return creator
    }

    private suspend fun device(
        name: String,
        server: FakeSyncServer,
        namespace: Long,
        remote: FakeSyncRemote = FakeSyncRemote(server),
    ): SimulatedDevice = SimulatedDevice(name, server, namespace, remote).also { it.pull() }

    private fun first(
        aFirst: Boolean,
        a: SimulatedDevice,
        b: SimulatedDevice,
    ) = if (aFirst) a else b

    private fun second(
        aFirst: Boolean,
        a: SimulatedDevice,
        b: SimulatedDevice,
    ) = if (aFirst) b else a
}
