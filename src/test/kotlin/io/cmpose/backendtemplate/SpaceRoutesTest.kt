package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.auth.VerifiedIdentity
import io.cmpose.backendtemplate.config.AppConfig
import io.cmpose.backendtemplate.files.StoredFiles
import io.cmpose.backendtemplate.sharing.Invites
import io.cmpose.backendtemplate.sharing.PERSONAL_SPACE_NAME
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.SpaceDraft
import io.cmpose.backendtemplate.sharing.SpaceMembers
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.cmpose.backendtemplate.sharing.SpaceResponse
import io.cmpose.backendtemplate.sharing.Spaces
import io.cmpose.backendtemplate.users.UserRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceRoutesTest {
    private lateinit var dir: Path

    @BeforeTest
    fun clean() {
        TestDb.reset()
        dir = createTempDirectory("space-test")
    }

    private val alice = TestKeys.token(sub = "alice")
    private val bob = TestKeys.token(sub = "bob")

    private fun withApp(
        config: AppConfig = testConfig(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application { module(authDeps(config = config.copy(filesDir = dir.toString()))) }
        block(jsonClient())
    }

    private fun personalSpacesOf(user: String) = transaction(TestDb.database) {
        Spaces.selectAll().where { Spaces.ownerId eq UUID.fromString(user) }.filter { it[Spaces.isPersonal] }.size
    }

    @Test
    fun firstAuthenticatedCallCreatesExactlyOnePersonalSpace() = withApp { client ->
        val id = client.userId(alice)
        client.userId(alice)
        client.spaces(alice)
        assertEquals(1, personalSpacesOf(id))
        val personal = client.personalSpace(alice)
        assertEquals(SpaceResponse(personal.id, PERSONAL_SPACE_NAME, Role.OWNER, personal = true, memberCount = 1), personal)
    }

    @Test
    fun concurrentFirstSightCreatesOnePersonalSpace() {
        val db = TestDb.database
        val users = UserRepository(db, onCreated = { SpaceRepository(db).personalSpace(it) })
        val identity = VerifiedIdentity("firebase", "racer", email = null, emailVerified = false, name = null)
        val pool = Executors.newFixedThreadPool(8)
        val ids = try {
            pool.invokeAll(List(8) { Callable { users.resolveOrCreate(identity) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, ids.toSet().size)
        assertEquals(1, personalSpacesOf(ids.first().toString()))
        assertEquals(1L, transaction(db) { Spaces.selectAll().count() })
        assertEquals(ids.first().value, SpaceRepository(db).personalSpace(ids.first()).let { id ->
            transaction(db) { Spaces.selectAll().where { Spaces.id eq id }.single()[Spaces.ownerId] }
        })
    }

    @Test
    fun listShowsPersonalFirstThenByName() = withApp { client ->
        client.createSpace(alice, "Zoo")
        client.createSpace(alice, "apple")
        val shared = client.sharedSpace(bob, "Beta", alice to Role.VIEWER)
        val spaces = client.spaces(alice)
        assertEquals(listOf(PERSONAL_SPACE_NAME, "apple", "Beta", "Zoo"), spaces.map { it.name })
        val beta = spaces.single { it.id == shared.id }
        assertEquals(Role.VIEWER, beta.role)
        assertEquals(2, beta.memberCount)
        assertFalse(beta.personal)
    }

    @Test
    fun createAndRename() = withApp { client ->
        val space = client.createSpace(alice, "  Trip  ")
        assertEquals(SpaceResponse(space.id, "Trip", Role.OWNER, personal = false, memberCount = 1), space)
        val renamed = client.patch("/api/v1/spaces/${space.id}") { jsonBody(alice, SpaceDraft("Holiday")) }
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals(space.copy(name = "Holiday"), renamed.body<SpaceResponse>())
    }

    @Test
    fun personalSpaceCannotBeDeletedButCanBeRenamed() = withApp { client ->
        val personal = client.personalSpace(alice)
        val response = client.delete("/api/v1/spaces/${personal.id}") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.errorCode())
        val renamed = client.patch("/api/v1/spaces/${personal.id}") { jsonBody(alice, SpaceDraft("Home")) }
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals(listOf("Home"), client.spaces(alice).map { it.name })
    }

    @Test
    fun invalidNamesAre400() = withApp { client ->
        listOf("", "   ", "x".repeat(81)).forEach { name ->
            val response = client.post("/api/v1/spaces") { jsonBody(alice, SpaceDraft(name)) }
            assertEquals(HttpStatusCode.BadRequest, response.status, "'$name'")
            assertEquals("BAD_REQUEST", response.errorCode())
        }
        client.createSpace(alice, "x".repeat(80))
        val unknownField = client.post("/api/v1/spaces") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Trip","personal":true}""")
        }
        assertEquals(HttpStatusCode.BadRequest, unknownField.status)
        assertEquals(listOf(PERSONAL_SPACE_NAME, "x".repeat(80)), client.spaces(alice).map { it.name })
    }

    @Test
    fun deletingASpaceRemovesMembersInvitesAndFiles() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
        client.invite(alice, space.id, Role.VIEWER)
        assertEquals(HttpStatusCode.Created, client.upload(bob, spaceId = space.id).status)
        assertEquals(HttpStatusCode.Created, client.upload(alice).status)
        assertEquals(2, dir.listDirectoryEntries().size)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/spaces/${space.id}") { bearerAuth(alice) }.status)

        val spaceId = UUID.fromString(space.id)
        transaction(TestDb.database) {
            assertEquals(0L, Spaces.selectAll().where { Spaces.id eq spaceId }.count())
            assertEquals(0L, SpaceMembers.selectAll().where { SpaceMembers.spaceId eq spaceId }.count())
            assertEquals(0L, Invites.selectAll().count())
            assertEquals(1L, StoredFiles.selectAll().count())
        }
        assertEquals(1, dir.listDirectoryEntries().size)
        assertEquals(listOf(PERSONAL_SPACE_NAME), client.spaces(bob).map { it.name })
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/spaces/${space.id}/members") { bearerAuth(alice) }.status)
        assertTrue(client.spaces(alice).none { it.id == space.id })
    }

    private fun ownedSharedSpacesOf(user: String) = transaction(TestDb.database) {
        Spaces.selectAll().where { Spaces.ownerId eq UUID.fromString(user) }.count { !it[Spaces.isPersonal] }
    }

    @Test
    fun spaceQuotaCountsOwnedSharedSpaces() = withApp(testConfig().copy(maxSpacesPerUser = 2)) { client ->
        // Neither her personal space nor the space Bob invited her into counts against Alice.
        client.sharedSpace(bob, "Bob's", alice to Role.EDITOR)
        client.createSpace(alice, "One")
        client.createSpace(alice, "Two")
        client.post("/api/v1/spaces") { jsonBody(alice, SpaceDraft("Three")) }.assertQuotaExceeded("spaces")
        assertEquals(2, ownedSharedSpacesOf(client.userId(alice)))
        assertEquals(
            listOf(PERSONAL_SPACE_NAME, "Bob's", "One", "Two"),
            client.spaces(alice).map { it.name },
        )
        // The quota is per user: Bob, at one space, still creates another.
        client.createSpace(bob, "Bob's second")
    }

    @Test
    fun loweredSpaceQuotaKeepsSpaces() {
        val ids = mutableListOf<String>()
        withApp { client -> listOf("One", "Two", "Three").forEach { ids += client.createSpace(alice, it).id } }
        withApp(testConfig().copy(maxSpacesPerUser = 2)) { client ->
            // Three spaces under a quota of two: all stay listed and usable; only a new one is refused.
            assertEquals(ids.toSet(), client.spaces(alice).filter { !it.personal }.map { it.id }.toSet())
            ids.forEach { id ->
                val rename = client.patch("/api/v1/spaces/$id") { jsonBody(alice, SpaceDraft("Renamed")) }
                assertEquals(HttpStatusCode.OK, rename.status, id)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/spaces/$id/members") { bearerAuth(alice) }.status)
            }
            client.post("/api/v1/spaces") { jsonBody(alice, SpaceDraft("Four")) }.assertQuotaExceeded("spaces")
            assertEquals(3, ownedSharedSpacesOf(client.userId(alice)))
        }
    }

    @Test
    fun concurrentCreatesCannotBothPass() = assertNoDeadlock {
        withApp(testConfig().copy(maxSpacesPerUser = 1)) { client ->
            client.userId(alice)
            // The first create stops at its insert, the second at the user row the first locked; without the
            // lock both would count zero spaces and stop at the insert.
            val responses = InsertGate("spaces").use { gate ->
                coroutineScope {
                    val creates = List(2) {
                        async { client.post("/api/v1/spaces") { jsonBody(alice, SpaceDraft("Trip")) } }
                    }
                    gate.awaitWaiting(2)
                    gate.close()
                    creates.awaitAll()
                }
            }
            assertEquals(
                listOf(HttpStatusCode.Created, HttpStatusCode.Conflict),
                responses.map { it.status }.sortedBy { it.value },
            )
            responses.single { it.status == HttpStatusCode.Conflict }.assertQuotaExceeded("spaces")
            assertEquals(1, ownedSharedSpacesOf(client.userId(alice)))
        }
    }
}
