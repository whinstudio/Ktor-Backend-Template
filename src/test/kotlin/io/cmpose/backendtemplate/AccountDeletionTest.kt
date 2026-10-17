package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.files.StoredFiles
import io.cmpose.backendtemplate.users.UserIdentities
import io.cmpose.backendtemplate.users.Users
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AccountDeletionTest {
    @Serializable
    private data class Me(val id: String, val email: String?)

    @Serializable
    private data class Uploaded(val id: String, val mime: String, val size: Long)

    private lateinit var dir: Path

    @BeforeTest
    fun clean() {
        TestDb.reset()
        dir = createTempDirectory("account-test")
    }

    private val alice = TestKeys.token(sub = "alice")
    private val bob = TestKeys.token(sub = "bob")

    private suspend fun HttpClient.deleteMe(token: String): HttpResponse = delete("/api/v1/me") { bearerAuth(token) }

    private fun rowsOf(user: String) = transaction(TestDb.database) {
        val id = UUID.fromString(user)
        listOf(
            Users.selectAll().where { Users.id eq id }.count(),
            UserIdentities.selectAll().where { UserIdentities.userId eq id }.count(),
            // cmpose:sharing-off: StoredFiles.selectAll().where { StoredFiles.ownerId eq id }.count(),
            StoredFiles.selectAll().where { StoredFiles.uploadedBy eq id }.count(),
        )
    }

    private fun storedNames() = dir.listDirectoryEntries().map { it.name }.toSet()

    @Test
    fun deletionRemovesRowsAndFilesAndKeepsOthers() = testApplication {
        application { module(authDeps(config = testConfig().copy(filesDir = dir.toString()))) }
        val client = jsonClient()
        val aliceId = client.me(alice).body<Me>().id
        val bobId = client.me(bob).body<Me>().id
        repeat(2) { assertEquals(HttpStatusCode.Created, client.upload(alice).status) }
        val bobFile = client.upload(bob).body<Uploaded>().id
        val bobNames = storedNames().also { assertEquals(3, it.size) } - transaction(TestDb.database) {
            // cmpose:sharing-off: StoredFiles.selectAll().where { StoredFiles.ownerId eq UUID.fromString(aliceId) }
            StoredFiles.selectAll().where { StoredFiles.uploadedBy eq UUID.fromString(aliceId) }
                .map { it[StoredFiles.path] }.toSet()
        }

        assertEquals(HttpStatusCode.NoContent, client.deleteMe(alice).status)

        assertEquals(listOf(0L, 0L, 0L), rowsOf(aliceId))
        assertEquals(listOf(1L, 1L, 1L), rowsOf(bobId))
        assertEquals(bobNames, storedNames())
        assertEquals(bobId, client.me(bob).body<Me>().id)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/files/$bobFile") { bearerAuth(bob) }.status)
    }

    @Test
    fun sameTokenAfterDeletionGetsANewUser() = testApplication {
        application { module(authDeps(config = testConfig().copy(filesDir = dir.toString()))) }
        val client = jsonClient()
        val before = client.me(alice).body<Me>().id
        assertEquals(HttpStatusCode.NoContent, client.deleteMe(alice).status)
        val after = client.me(alice).body<Me>().id
        assertNotEquals(before, after)
        assertEquals(listOf(0L, 0L, 0L), rowsOf(before))
        assertEquals(listOf(1L, 1L, 0L), rowsOf(after))
    }

    @Test
    fun deletingTwiceIsStill204() = testApplication {
        application { module(authDeps(config = testConfig().copy(filesDir = dir.toString()))) }
        val client = jsonClient()
        assertEquals(HttpStatusCode.NoContent, client.deleteMe(alice).status)
        assertEquals(HttpStatusCode.NoContent, client.deleteMe(alice).status)
    }

    @Test
    fun withoutTokenIs401() = testApplication {
        application { module(authDeps(config = testConfig().copy(filesDir = dir.toString()))) }
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().delete("/api/v1/me").status)
    }
}
