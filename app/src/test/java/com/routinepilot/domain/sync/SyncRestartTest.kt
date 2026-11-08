package com.routinepilot.domain.sync

import com.routinepilot.testing.DeviceStorage
import com.routinepilot.testing.FakeSyncRemote
import com.routinepilot.testing.FakeSyncServer
import com.routinepilot.testing.SimulatedDevice
import com.routinepilot.testing.routineFields
import com.routinepilot.testing.routineKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A process can be killed at any point. Each test kills the device, starts a new process over the same storage, and
 * checks that queued changes survive and reach the server exactly once.
 */
class SyncRestartTest {
    private val key = routineKey

    @Test
    fun editsMadeOffline_surviveAKillBeforeAnySync_andArriveOnceAfterRestart() = runBlocking {
        val server = FakeSyncServer()
        seededRoutine(server)
        val storage = DeviceStorage()
        val before = server.logSize()
        val phone = SimulatedDevice("phone", server, namespace = 2, storage = storage).also { it.pull() }
        phone.save(key, routineFields(title = "Evening", description = "Old"))
        phone.remote.offline = true
        assertEquals(CycleResult.Retry, phone.cycle())

        // The process is killed here. A new one starts over the same storage.
        val restarted = SimulatedDevice("phone", server, namespace = 2, storage = storage)
        restarted.pull()
        assertEquals(1, restarted.queuedTotal())
        assertEquals(CycleResult.Done, restarted.cycle())

        assertEquals(before + 1, server.logSize())
        assertEquals(routineFields(title = "Evening", description = "Old"), server.head(key)?.fields)
        assertEquals(0, restarted.queuedTotal())
        assertEquals(routineFields(title = "Evening", description = "Old"), restarted.appData(key))
    }

    @Test
    fun aLostResponse_thenAKill_appliesThePendingChangeOnce() = runBlocking {
        val server = FakeSyncServer()
        seededRoutine(server)
        val storage = DeviceStorage()
        val remote = FakeSyncRemote(server).also { it.dropNextResponse = true }
        val phone = SimulatedDevice("phone", server, namespace = 2, remote = remote, storage = storage)
        phone.pull()
        phone.save(key, routineFields(title = "Evening", description = "Old"))
        val before = server.logSize()

        // The server applies the change, but the response is lost before the device hears back.
        assertEquals(CycleResult.Retry, phone.cycle())
        assertEquals(before + 1, server.logSize())

        val restarted = SimulatedDevice("phone", server, namespace = 2, storage = storage)
        assertEquals(CycleResult.Done, restarted.cycle())

        assertEquals(before + 1, server.logSize())
        assertEquals(0, restarted.queuedTotal())
        assertEquals(routineFields(title = "Evening", description = "Old"), server.head(key)?.fields)
    }

    @Test
    fun anExpiredSession_keepsTheQueue_throughARestart_andResumesAfterSignInAgain() = runBlocking {
        val server = FakeSyncServer()
        seededRoutine(server)
        val storage = DeviceStorage()
        val expired = FakeSyncRemote(server).also { it.authExpired = true }
        val phone = SimulatedDevice("phone", server, namespace = 2, remote = expired, storage = storage)
        phone.save(key, routineFields(title = "Evening", description = "Old"))

        assertEquals(CycleResult.NeedsSignIn, phone.cycle())
        assertEquals(1, phone.queuedTotal())

        // The process is killed. The user signs in again, so the new process has a working session.
        val restarted = SimulatedDevice("phone", server, namespace = 2, storage = storage)
        assertEquals(CycleResult.Done, restarted.cycle())

        assertEquals(routineFields(title = "Evening", description = "Old"), server.head(key)?.fields)
        assertEquals(0, restarted.queuedTotal())
    }

    @Test
    fun twoDevicesEditingDifferentFields_restartingMidway_convergeInEitherReconnectOrder() = runBlocking {
        for (aFirst in listOf(true, false)) {
            val server = FakeSyncServer()
            seededRoutine(server)
            val storageA = DeviceStorage()
            val storageB = DeviceStorage()
            val a = SimulatedDevice("A", server, namespace = 2, storage = storageA).also { it.pull() }
            val b = SimulatedDevice("B", server, namespace = 3, storage = storageB).also { it.pull() }
            a.save(key, routineFields(title = "Evening", description = "Old"))
            b.save(key, routineFields(title = "Morning", description = "New walk"))

            // Both processes are killed. The new processes reconnect in the order the test chooses.
            val restartedA = SimulatedDevice("A", server, namespace = 2, storage = storageA)
            val restartedB = SimulatedDevice("B", server, namespace = 3, storage = storageB)
            val (firstDevice, secondDevice) = if (aFirst) restartedA to restartedB else restartedB to restartedA
            firstDevice.cycle()
            secondDevice.cycle()
            firstDevice.cycle()
            secondDevice.cycle()

            val expected = routineFields(title = "Evening", description = "New walk")
            assertEquals(expected, server.head(key)?.fields)
            assertEquals(expected, restartedA.appData(key))
            assertEquals(expected, restartedB.appData(key))
        }
    }

    private suspend fun seededRoutine(server: FakeSyncServer) {
        val creator = SimulatedDevice("creator", server, namespace = 1)
        creator.save(key, routineFields(title = "Morning", description = "Old"))
        assertEquals(CycleResult.Done, creator.cycle())
    }
}
