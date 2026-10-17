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

    private fun withApp(maxUploadBytes: Long = 10_485_760L, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApplication {
            val config = testConfig().copy(filesDir = dir.toString(), maxUploadBytes = maxUploadBytes)
            application { module(authDeps(config = config)) }
            block(jsonClient())
        }

    private suspend fun HttpResponse.errorCode() =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

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

    /** A body sent without `Content-Length`, so only the limit counting while reading can catch it. */
    private class Chunked(private val bytes: ByteArray, override val contentType: ContentType) :
        OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(channel: ByteWriteChannel) {
            bytes.toList().chunked(256).forEach { channel.writeFully(it.toByteArray()) }
        }
    }
}
