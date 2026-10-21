package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.files.FileStore
import io.cmpose.backendtemplate.files.LocalDiskFileStore
import io.cmpose.backendtemplate.files.StoredFiles
import io.cmpose.backendtemplate.sharing.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FileRoutesTest {
    @Serializable
    private data class Uploaded(val id: String, val mime: String, val size: Long)

    private lateinit var dir: Path

    @BeforeTest
    fun clean() {
        TestDb.reset()
        dir = createTempDirectory("files-test")
    }

    private fun withApp(
        maxUploadBytes: Long = 10_485_760L,
        maxStorageBytesPerUser: Long = 0L,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val config = testConfig().copy(
            filesDir = dir.toString(),
            maxUploadBytes = maxUploadBytes,
            maxStorageBytesPerUser = maxStorageBytesPerUser,
        )
        application { module(authDeps(config = config)) }
        block(jsonClient())
    }

    private suspend fun HttpResponse.errorCode() =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.quota() =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["quota"]?.jsonPrimitive?.content

    /** A PNG of exactly [size] bytes: [PNG_BYTES] padded with zeros. */
    private fun pngOf(size: Int) = PNG_BYTES + ByteArray(size - PNG_BYTES.size)

    private suspend fun assertQuotaExceeded(response: HttpResponse) {
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("QUOTA_EXCEEDED", response.errorCode())
        assertEquals("storage", response.quota())
    }

    private fun rowCount() = transaction(TestDb.database) { StoredFiles.selectAll().count() }

    private val alice = TestKeys.token(sub = "alice")
    private val bob = TestKeys.token(sub = "bob")

    @Test
    fun uploadedPngReadsBackTheSameBytes() = withApp { client ->
        val response = client.upload(alice)
        assertEquals(HttpStatusCode.Created, response.status)
        val uploaded = response.body<Uploaded>()
        assertEquals("image/png", uploaded.mime)
        assertEquals(PNG_BYTES.size.toLong(), uploaded.size)
        val read = client.get("/api/v1/files/${uploaded.id}") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.OK, read.status)
        assertContentEquals(PNG_BYTES, read.bodyAsBytes())
        assertEquals(ContentType.Image.PNG, read.contentType()?.withoutParameters())
        assertTrue(read.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
    }

    @Test
    fun exeDisguisedAsPngIs415() = withApp { client ->
        val exe = "MZ".toByteArray() + ByteArray(64)
        val response = client.upload(alice, exe, filename = "cat.png", type = "image/png")
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", response.errorCode())
        assertEquals(0L, rowCount())
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun pngDeclaredAsAnythingIsStillPng() = withApp { client ->
        val response = client.upload(alice, filename = "notes.txt", type = "text/plain")
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("image/png", response.body<Uploaded>().mime)
    }

    @Test
    fun bodyOverUploadLimitIs413() = withApp(maxUploadBytes = 1024) { client ->
        val response = client.upload(alice, PNG_BYTES + ByteArray(2048))
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("PAYLOAD_TOO_LARGE", response.errorCode())
        assertEquals(0L, rowCount())
    }

    @Test
    fun otherUsersFileIs404AndStays() = withApp { client ->
        val id = client.upload(alice).body<Uploaded>().id
        val read = client.get("/api/v1/files/$id") { bearerAuth(bob) }
        assertEquals(HttpStatusCode.NotFound, read.status)
        assertEquals("NOT_FOUND", read.errorCode())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/files/$id") { bearerAuth(bob) }.status)
        assertEquals(1L, rowCount())
        assertEquals(1, dir.listDirectoryEntries().size)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/files/$id") { bearerAuth(alice) }.status)
    }

    @Test
    fun nonUuidIdIs404() = withApp { client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/files/not-a-uuid") { bearerAuth(alice) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/files/..%2F..%2Fx") { bearerAuth(alice) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/files/not-a-uuid") { bearerAuth(alice) }.status)
    }

    @Test
    fun ownerDeletesRowAndFile() = withApp { client ->
        val id = client.upload(alice).body<Uploaded>().id
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/files/$id") { bearerAuth(alice) }.status)
        assertEquals(0L, rowCount())
        assertTrue(dir.listDirectoryEntries().isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/files/$id") { bearerAuth(alice) }.status)
    }

    @Test
    fun withoutTokenIs401() = withApp { client ->
        val response = client.post("/api/v1/files")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/files/not-a-uuid").status)
    }

    @Test
    fun missingFilePartIs400() = withApp { client ->
        val response = client.post("/api/v1/files") {
            bearerAuth(alice)
            contentType(ContentType.MultiPart.FormData.withParameter("boundary", "b"))
            setBody("--b\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\nx\r\n--b--\r\n")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.errorCode())
    }

    @Test
    fun nonMultipartUploadIs415() = withApp { client ->
        val response = client.post("/api/v1/files") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", response.errorCode())
    }

    @Test
    fun chunkedBodyOverUploadLimitIs413() = withApp(maxUploadBytes = 1024) { client ->
        val boundary = "b"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n"
        val bytes = body.toByteArray() + PNG_BYTES + ByteArray(4096) + "\r\n--$boundary--\r\n".toByteArray()
        val response = client.post("/api/v1/files") {
            bearerAuth(alice)
            setBody(Chunked(bytes, ContentType.MultiPart.FormData.withParameter("boundary", boundary)))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(0L, rowCount())
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun uploadBeyondTheConcurrencyLimitIs503() = testApplication {
        val store = GatedStore(LocalDiskFileStore(dir))
        val config = testConfig().copy(filesDir = dir.toString(), maxConcurrentUploads = 1)
        application { module(AppDeps(config, TestKeys.jwkProvider, TestDb.database, store)) }
        val client = jsonClient()
        coroutineScope {
            val first = async { client.upload(alice) }
            withTimeout(10.seconds) { store.entered.await() }
            val busy = client.upload(bob)
            assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
            assertEquals("UPLOADS_BUSY", busy.errorCode())
            assertEquals("5", busy.headers[HttpHeaders.RetryAfter])
            store.release.countDown()
            assertEquals(HttpStatusCode.Created, first.await().status)
        }
        assertEquals(HttpStatusCode.Created, client.upload(bob).status)
        assertEquals(2L, rowCount())
    }

    @Test
    fun uploadWithinStorageQuotaPasses() = withApp(maxStorageBytesPerUser = 1000) { client ->
        assertEquals(HttpStatusCode.Created, client.upload(alice, pngOf(600)).status)
        // Exactly at the limit still passes: only going past it is refused.
        assertEquals(HttpStatusCode.Created, client.upload(alice, pngOf(400)).status)
        assertEquals(2L, rowCount())
    }

    @Test
    fun uploadOverStorageQuotaIs409() = withApp(maxStorageBytesPerUser = 1000) { client ->
        client.upload(alice, pngOf(600))
        client.upload(alice, pngOf(400))
        val filesBefore = dir.listDirectoryEntries().size
        // The smallest file the sniffer accepts; any byte past the limit is refused.
        assertQuotaExceeded(client.upload(alice, PNG_BYTES))
        assertEquals(2L, rowCount())
        assertEquals(filesBefore, dir.listDirectoryEntries().size)
        // The quota is per user: another user still uploads.
        assertEquals(HttpStatusCode.Created, client.upload(bob, pngOf(600)).status)
    }

    @Test
    fun zeroStorageQuotaIsOff() = withApp(maxStorageBytesPerUser = 0) { client ->
        repeat(3) { assertEquals(HttpStatusCode.Created, client.upload(alice, pngOf(600)).status) }
        assertEquals(3L, rowCount())
    }

    @Test
    fun loweredStorageQuotaKeepsFiles() {
        val ids = mutableListOf<String>()
        withApp { client -> repeat(2) { ids += client.upload(alice, pngOf(600)).body<Uploaded>().id } }
        withApp(maxStorageBytesPerUser = 1) { client ->
            ids.forEach { id ->
                val read = client.get("/api/v1/files/$id") { bearerAuth(alice) }
                assertEquals(HttpStatusCode.OK, read.status)
                assertContentEquals(pngOf(600), read.bodyAsBytes())
            }
            assertQuotaExceeded(client.upload(alice))
        }
        assertEquals(2L, rowCount())
    }

    @Test
    fun concurrentUploadsCannotBothPassTheQuota() = assertNoDeadlock {
        withApp(maxStorageBytesPerUser = 1000) { client ->
            assertEquals(HttpStatusCode.Created, client.upload(alice, pngOf(600)).status)
            // Either 300 alone fits (900); both together (1200) do not. One upload stops at its insert with the user
            // row locked, the other at that row; without the lock both would count 600 and stop at the insert.
            val responses = InsertGate("files").use { gate ->
                coroutineScope {
                    val uploads = List(2) { async { client.upload(alice, pngOf(300)) } }
                    gate.awaitWaiting(2)
                    gate.close()
                    uploads.awaitAll()
                }
            }
            assertEquals(
                listOf(HttpStatusCode.Created, HttpStatusCode.Conflict),
                responses.map { it.status }.sortedBy { it.value },
            )
            assertQuotaExceeded(responses.single { it.status == HttpStatusCode.Conflict })
        }
        assertEquals(2L, rowCount())
        assertEquals(2, dir.listDirectoryEntries().size)
    }

    // cmpose:sharing
    private val carol = TestKeys.token(sub = "carol")

    private fun spaceOf(fileId: String) = transaction(TestDb.database) {
        StoredFiles.selectAll().where { StoredFiles.id eq UUID.fromString(fileId) }.single()[StoredFiles.spaceId]
    }

    @Test
    fun uploadWithoutSpaceGoesToThePersonalSpace() = withApp { client ->
        val id = client.upload(alice).body<Uploaded>().id
        assertEquals(client.personalSpace(alice).id, spaceOf(id).toString())
    }

    @Test
    fun membersShareFilesByRole() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER, carol to Role.EDITOR)
        val upload = client.upload(carol, spaceId = space.id)
        assertEquals(HttpStatusCode.Created, upload.status)
        val id = upload.body<Uploaded>().id
        assertEquals(space.id, spaceOf(id).toString())
        assertContentEquals(PNG_BYTES, client.get("/api/v1/files/$id") { bearerAuth(bob) }.bodyAsBytes())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/files/$id") { bearerAuth(bob) }.status)
        val stranger = TestKeys.token(sub = "dave")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/files/$id") { bearerAuth(stranger) }.status)
        assertEquals(1L, rowCount())
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/files/$id") { bearerAuth(alice) }.status)
        assertEquals(0L, rowCount())
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun uploadToASpaceWithoutEditorRoleIs404AndStoresNothing() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER)
        val bobPersonal = client.personalSpace(bob).id
        listOf(
            client.upload(bob, spaceId = space.id),
            client.upload(carol, spaceId = space.id),
            client.upload(alice, spaceId = bobPersonal),
            client.upload(alice, spaceId = "not-a-uuid"),
        ).forEach { response ->
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", response.errorCode())
        }
        assertEquals(0L, rowCount())
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun editorUploadCountsAgainstTheOwner() = withApp(maxStorageBytesPerUser = 1000) { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
        // Alice's own space counts too: the total is over every space she owns.
        assertEquals(HttpStatusCode.Created, client.upload(alice, pngOf(600)).status)
        assertQuotaExceeded(client.upload(bob, pngOf(500), spaceId = space.id))
        // Bob's personal space is his own: his quota, still empty.
        assertEquals(HttpStatusCode.Created, client.upload(bob, pngOf(500)).status)
        assertEquals(2L, rowCount())
        assertEquals(2, dir.listDirectoryEntries().size)
    }

    @Test
    fun crossEditorUploadsDoNotDeadlock() {
        // A deadlock would not show in the answers: the losing transaction is retried (and logged), then passes.
        assertNoDeadlock {
            testApplication {
                val store = PairedStore(LocalDiskFileStore(dir))
                val config = testConfig().copy(filesDir = dir.toString(), maxStorageBytesPerUser = 1000)
                application { module(AppDeps(config, TestKeys.jwkProvider, TestDb.database, store)) }
                val client = jsonClient()
                val aliceSpace = client.sharedSpace(alice, "Alice's", bob to Role.EDITOR)
                val bobSpace = client.sharedSpace(bob, "Bob's", alice to Role.EDITOR)
                // Each upload locks the other's quota row, and its insert's foreign key then checks its uploader's.
                store.paired = true
                val responses = coroutineScope {
                    awaitAll(
                        async { client.upload(alice, pngOf(300), spaceId = bobSpace.id) },
                        async { client.upload(bob, pngOf(300), spaceId = aliceSpace.id) },
                    )
                }
                assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Created), responses.map { it.status })
            }
        }
        assertEquals(2L, rowCount())
    }

    // /cmpose:sharing

    /** Holds every write until [release] opens; [entered] completes once a write waits, i.e. an upload holds a permit. */
    private class GatedStore(private val inner: FileStore) : FileStore by inner {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)

        override fun write(bytes: ByteArray): String {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "the gate never opened" }
            return inner.write(bytes)
        }
    }

    // cmpose:sharing
    /** Once [paired] is set, holds each write until a second one arrives, so the two reach the database together. */
    private class PairedStore(private val inner: FileStore) : FileStore by inner {
        @Volatile var paired = false
        private val both = CountDownLatch(2)

        override fun write(bytes: ByteArray): String {
            if (paired) {
                both.countDown()
                check(both.await(10, TimeUnit.SECONDS)) { "the second write never came" }
            }
            return inner.write(bytes)
        }
    }
    // /cmpose:sharing

    /** A body sent without `Content-Length`, so only the limit counting while reading can catch it. */
    private class Chunked(private val bytes: ByteArray, override val contentType: ContentType) :
        OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(channel: ByteWriteChannel) {
            bytes.toList().chunked(256).forEach { channel.writeFully(it.toByteArray()) }
        }
    }
}
