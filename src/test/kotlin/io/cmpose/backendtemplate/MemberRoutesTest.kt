package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.sharing.MemberResponse
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.RoleChange
import io.cmpose.backendtemplate.users.ProfileUpdate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemberRoutesTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private val alice = TestKeys.token(sub = "alice", email = "alice@example.com")
    private val bob = TestKeys.token(sub = "bob")
    private val carol = TestKeys.token(sub = "carol")

    private fun withApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(authDeps()) }
        block(jsonClient())
    }

    private suspend fun HttpClient.members(token: String, spaceId: String): List<MemberResponse> =
        get("/api/v1/spaces/$spaceId/members") { bearerAuth(token) }.body()

    private suspend fun HttpClient.setRole(token: String, spaceId: String, user: String, role: Role): HttpResponse =
        patch("/api/v1/spaces/$spaceId/members/$user") { jsonBody(token, RoleChange(role)) }

    private suspend fun HttpClient.remove(token: String, spaceId: String, user: String): HttpResponse =
        delete("/api/v1/spaces/$spaceId/members/$user") { bearerAuth(token) }

    private suspend fun HttpClient.leave(token: String, spaceId: String): HttpResponse =
        post("/api/v1/spaces/$spaceId/leave") { bearerAuth(token) }

    @Test
    fun ownerChangesARoleAndRemovesAMember() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER)
        val bobId = client.userId(bob)
        assertEquals(HttpStatusCode.NoContent, client.setRole(alice, space.id, bobId, Role.EDITOR).status)
        assertEquals(Role.EDITOR, client.members(alice, space.id).single { it.userId == bobId }.role)
        assertEquals(Role.EDITOR, client.spaces(bob).single { it.id == space.id }.role)

        assertEquals(HttpStatusCode.NoContent, client.remove(alice, space.id, bobId).status)
        assertEquals(listOf(client.userId(alice)), client.members(alice, space.id).map { it.userId })
        assertTrue(client.spaces(bob).none { it.id == space.id })
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/spaces/${space.id}/members") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.remove(alice, space.id, bobId).status)
        assertEquals(HttpStatusCode.NotFound, client.setRole(alice, space.id, bobId, Role.VIEWER).status)
    }

    @Test
    fun editorChangingARoleIs404() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR, carol to Role.VIEWER)
        val carolId = client.userId(carol)
        val response = client.setRole(bob, space.id, carolId, Role.EDITOR)
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.errorCode())
        assertEquals(HttpStatusCode.NotFound, client.setRole(bob, space.id, client.userId(bob), Role.OWNER).status)
        assertEquals(Role.VIEWER, client.members(alice, space.id).single { it.userId == carolId }.role)
    }

    @Test
    fun aMemberLeaves() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER, carol to Role.EDITOR)
        assertEquals(HttpStatusCode.NoContent, client.leave(bob, space.id).status)
        assertTrue(client.spaces(bob).none { it.id == space.id })
        assertEquals(HttpStatusCode.NotFound, client.leave(bob, space.id).status)
        assertEquals(2, client.members(alice, space.id).size)
    }

    @Test
    fun lastOwnerCannotLeaveBeRemovedOrDemoted() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
        val aliceId = client.userId(alice)
        listOf(
            client.leave(alice, space.id),
            client.remove(alice, space.id, aliceId),
            client.setRole(alice, space.id, aliceId, Role.EDITOR),
        ).forEach { response ->
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("LAST_OWNER", response.errorCode())
        }
        val personal = client.personalSpace(alice)
        assertEquals(HttpStatusCode.Conflict, client.leave(alice, personal.id).status)
        assertEquals(Role.OWNER, client.members(alice, space.id).single { it.userId == aliceId }.role)
        assertEquals(2, client.members(alice, space.id).size)
    }

    @Test
    fun ownerRoleIsNeverGranted() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
        val bobId = client.userId(bob)
        val response = client.setRole(alice, space.id, bobId, Role.OWNER)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.errorCode())
        assertEquals(Role.EDITOR, client.members(alice, space.id).single { it.userId == bobId }.role)
    }

    @Test
    fun membersListPutsTheOwnerFirst() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", carol to Role.EDITOR, bob to Role.VIEWER)
        val members = client.members(bob, space.id)
        assertEquals(listOf("alice", "carol", "bob").map { client.userId(TestKeys.token(sub = it)) }, members.map { it.userId })
        val owner = members.first()
        assertEquals(Role.OWNER, owner.role)
        assertEquals("alice@example.com", owner.email)
        assertTrue(members.zipWithNext().drop(1).all { (a, b) -> a.joinedAt <= b.joinedAt })
    }

    @Test
    fun membersCarryTheirDisplayName() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER)
        val profile = client.put("/api/v1/me/profile") { jsonBody(bob, ProfileUpdate(displayName = "Bob")) }
        assertEquals(HttpStatusCode.NoContent, profile.status)
        val members = client.members(alice, space.id)
        assertEquals("Bob", members.single { it.userId == client.userId(bob) }.displayName)
        assertNull(members.single { it.userId == client.userId(alice) }.displayName)
    }

    @Test
    fun membersListShowsOnlyVerifiedEmails() = withApp { client ->
        val unverifiedBob = TestKeys.token(sub = "bob", email = "bob@example.com", emailVerified = false)
        val space = client.sharedSpace(alice, "Trip", unverifiedBob to Role.VIEWER)
        val bobId = client.userId(unverifiedBob)
        assertNull(client.members(alice, space.id).single { it.userId == bobId }.email)

        client.me(TestKeys.token(sub = "bob", email = "bob@example.com"))
        assertEquals("bob@example.com", client.members(alice, space.id).single { it.userId == bobId }.email)
    }
}
