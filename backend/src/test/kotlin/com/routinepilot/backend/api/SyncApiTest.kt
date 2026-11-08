package com.routinepilot.backend.api

import com.routinepilot.backend.backendModule
import com.routinepilot.backend.db.TestPostgres
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncApiTest {
    @BeforeEach
    fun reset() {
        TestPostgres.reset()
    }

    @Test
    fun accountSession_cannotSync() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val account = client.signUp("mary@example.com").token

        val response = client.post("/v1/sync/changes") {
            bearerAuth(account)
            contentType(ContentType.Application.Json)
            setBody(SyncUploadRequest(listOf(routineChange(UUID.randomUUID(), "Morning"))))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("device_session_required", response.errorCode())
    }

    @Test
    fun upload_thenDownload_returnsTheChangeWithItsVersionAndCursor() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)

        val result = client.upload(device.token, routineChange(UUID.randomUUID(), "Morning")).results.single()
        val page = client.download(device.token, since = 0)

        assertEquals("ACCEPTED", result.status)
        assertEquals(1L, result.version)
        assertEquals(1, page.changes.size)
        assertEquals(result.seq, page.cursor)
        assertFalse(page.hasMore)
        assertEquals("Morning", page.changes.single().payload?.get("title")?.jsonPrimitive?.content)
        assertEquals(device.device.id, page.changes.single().deviceId)
    }

    @Test
    fun staleWrite_isAConflict_withTheServerState() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val id = UUID.randomUUID()
        client.upload(device.token, routineChange(id, "Morning"))

        val stale = client.upload(device.token, routineChange(id, "Evening", baseVersion = 0)).results.single()
        val current = client.upload(device.token, routineChange(id, "Evening", baseVersion = 1)).results.single()

        assertEquals("CONFLICT", stale.status)
        assertEquals(1L, stale.serverVersion)
        assertEquals("Morning", stale.serverPayload?.get("title")?.jsonPrimitive?.content)
        assertEquals("ACCEPTED", current.status)
        assertEquals(2L, current.version)
    }

    @Test
    fun retriedUpload_isAcknowledgedAgain_andNeverAppliedTwice() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val change = routineChange(UUID.randomUUID(), "Morning")

        val first = client.upload(device.token, change).results.single()
        val retry = client.upload(device.token, change).results.single()
        val page = client.download(device.token, since = 0)

        assertEquals("ACCEPTED", first.status)
        assertEquals("DUPLICATE", retry.status)
        assertEquals(first.seq, retry.seq)
        assertEquals(first.version, retry.version)
        assertEquals(1, page.changes.size)
    }

    @Test
    fun reusingAChangeId_forADifferentChange_isRejected() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val changeId = UUID.randomUUID()
        client.upload(device.token, routineChange(UUID.randomUUID(), "Morning", changeId = changeId))

        val reused = client.upload(device.token, routineChange(UUID.randomUUID(), "Other", changeId = changeId))

        assertEquals("REJECTED", reused.results.single().status)
        assertEquals("change_id_reused", reused.results.single().reason)
    }

    @Test
    fun delete_leavesATombstone_thatIsHiddenFromRecords_butVisibleInTheLog() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val id = UUID.randomUUID()
        client.upload(device.token, routineChange(id, "Morning"))

        val deleted = client.upload(device.token, deleteChange(id, baseVersion = 1)).results.single()
        val records = client.get("/v1/records?entityType=routine") { bearerAuth(device.token) }
            .body<RecordPageResponse>()
        val page = client.download(device.token, since = 0)

        assertEquals(2L, deleted.version)
        assertTrue(records.records.isEmpty())
        assertEquals("DELETE", page.changes.last().operation)
        assertNull(page.changes.last().payload)
    }

    @Test
    fun download_pagesThroughTheWholeLog() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val titles = listOf("one", "two", "three")
        client.upload(device.token, *titles.map { routineChange(UUID.randomUUID(), it) }.toTypedArray())

        val firstPage = client.download(device.token, since = 0, limit = 2)
        val secondPage = client.download(device.token, since = firstPage.cursor, limit = 2)

        assertEquals(2, firstPage.changes.size)
        assertTrue(firstPage.hasMore)
        assertEquals(listOf("three"), secondPage.changes.map { it.payload?.get("title")?.jsonPrimitive?.content })
        assertFalse(secondPage.hasMore)
    }

    @Test
    fun twoDevices_shareTheLog_andOtherAccountsSeeNothing() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val mary = client.signUp("mary@example.com").token
        val phone = client.registerDevice(mary, name = "Phone")
        val tablet = client.registerDevice(mary, name = "Tablet")
        val other = client.registerDevice(client.signUp("other@example.com").token)
        client.upload(phone.token, routineChange(UUID.randomUUID(), "Morning"))

        val onTablet = client.download(tablet.token, since = 0)
        val onOther = client.download(other.token, since = 0)

        assertEquals(1, onTablet.changes.size)
        assertEquals(phone.device.id, onTablet.changes.single().deviceId)
        assertTrue(onOther.changes.isEmpty())
    }

    @Test
    fun records_listLiveRecords_andPageByEntityId() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val ids = List(3) { UUID.randomUUID() }.sortedBy { it.toString() }
        client.upload(device.token, *ids.map { routineChange(it, "title $it") }.toTypedArray())

        val first = client.get("/v1/records?entityType=routine&limit=2") { bearerAuth(device.token) }
            .body<RecordPageResponse>()
        val second = client.get("/v1/records?entityType=routine&limit=2&after=${first.next}") {
            bearerAuth(device.token)
        }.body<RecordPageResponse>()

        assertEquals(ids.take(2), first.records.map { UUID.fromString(it.entityId) })
        assertEquals(listOf(ids[2]), second.records.map { UUID.fromString(it.entityId) })
        assertNull(second.next)
    }

    @Test
    fun invalidChange_rejectsTheWholeBatch_andStoresNothing() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig()) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val invalid = ChangeInputDto(
            changeId = UUID.randomUUID().toString(),
            entityType = "routine",
            entityId = UUID.randomUUID().toString(),
            operation = "UPSERT",
            baseVersion = 0,
            payload = routineTitle("   "),
        )

        val response = client.post("/v1/sync/changes") {
            bearerAuth(device.token)
            contentType(ContentType.Application.Json)
            setBody(SyncUploadRequest(listOf(routineChange(UUID.randomUUID(), "Fine"), invalid)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("validation_failed", response.errorCode())
        assertTrue(client.download(device.token, since = 0).changes.isEmpty())
    }

    @Test
    fun oversizedBody_isRefusedBeforeItIsRead() = testApplication {
        environment { config = MapApplicationConfig() }
        application { backendModule(apiConfig(maxBodyBytes = 200)) }
        val client = apiClient()
        val device = client.registerDevice(client.signUp("mary@example.com").token)
        val large = List(10) { routineChange(UUID.randomUUID(), "a long title that takes up space") }

        val response = client.post("/v1/sync/changes") {
            bearerAuth(device.token)
            contentType(ContentType.Application.Json)
            setBody(SyncUploadRequest(large))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("payload_too_large", response.errorCode())
    }

    private suspend fun io.ktor.client.HttpClient.download(
        deviceToken: String,
        since: Long,
        limit: Int? = null,
    ): ChangePageResponse {
        val suffix = if (limit == null) "" else "&limit=$limit"
        val response = get("/v1/sync/changes?since=$since$suffix") { bearerAuth(deviceToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }
}
