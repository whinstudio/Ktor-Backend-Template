package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.sharing.PendingInvite
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.RoleChange
import io.cmpose.backendtemplate.sharing.SpaceDraft
import io.cmpose.backendtemplate.sharing.atLeast
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `requireRole`: a non-member and a member whose role is too low both get 404, never 403. */
class AccessTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private val alice = TestKeys.token(sub = "alice")
    private val bob = TestKeys.token(sub = "bob")
    private val carol = TestKeys.token(sub = "carol")

    private fun withApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val config = testConfig().copy(filesDir = createTempDirectory("access-test").toString())
        application { module(authDeps(config = config)) }
        block(jsonClient())
    }

    /**
     * Every call an OWNER may make on [spaceId] (and [memberId]'s membership, and the space's pending invite
     * [inviteId]), made with [token].
     */
    private fun ownerCalls(client: HttpClient, spaceId: String, memberId: String, inviteId: String, token: String) =
        listOf<Pair<String, suspend () -> HttpResponse>>(
            "rename" to { client.patch("/api/v1/spaces/$spaceId") { jsonBody(token, SpaceDraft("X")) } },
            "invite" to { client.inviteResponse(token, spaceId, Role.VIEWER) },
            "pending invites" to { client.pendingInvites(token, spaceId) },
            "revoke invite" to { client.revoke(token, spaceId, inviteId) },
            "set role" to {
                client.patch("/api/v1/spaces/$spaceId/members/$memberId") { jsonBody(token, RoleChange(Role.EDITOR)) }
            },
            "remove" to { client.delete("/api/v1/spaces/$spaceId/members/$memberId") { bearerAuth(token) } },
            "delete" to { client.delete("/api/v1/spaces/$spaceId") { bearerAuth(token) } },
        )

    private suspend fun assertAllNotFound(calls: List<Pair<String, suspend () -> HttpResponse>>) {
        calls.forEach { (name, call) ->
            val response = call()
            assertEquals(HttpStatusCode.NotFound, response.status, name)
            assertEquals("NOT_FOUND", response.errorCode(), name)
        }
    }

    /** The ids of [spaceId]'s pending invites, as its owner [token] lists them. */
    private suspend fun HttpClient.pendingIds(token: String, spaceId: String) =
        pendingInvites(token, spaceId).body<List<PendingInvite>>().map { it.id }

    @Test
    fun nonMemberGets404Everywhere() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val aliceId = client.userId(alice)
        val inviteId = client.invite(alice, space.id, Role.VIEWER).id
        val readCalls = listOf<Pair<String, suspend () -> HttpResponse>>(
            "members" to { client.get("/api/v1/spaces/${space.id}/members") { bearerAuth(bob) } },
            "leave" to { client.post("/api/v1/spaces/${space.id}/leave") { bearerAuth(bob) } },
            "upload" to { client.upload(bob, spaceId = space.id) },
        )
        assertAllNotFound(ownerCalls(client, space.id, aliceId, inviteId, bob) + readCalls)
        assertEquals(listOf(inviteId), client.pendingIds(alice, space.id))
        assertFalse(client.spaces(bob).any { it.id == space.id })
        assertEquals(listOf("Trip"), client.spaces(alice).filter { !it.personal }.map { it.name })
    }

    @Test
    fun viewerCannotWrite() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER, carol to Role.VIEWER)
        val carolId = client.userId(carol)
        val inviteId = client.invite(alice, space.id, Role.VIEWER).id
        val calls = ownerCalls(client, space.id, carolId, inviteId, bob)
        assertAllNotFound(calls + ("upload" to { client.upload(bob, spaceId = space.id) }))
        assertEquals(listOf(inviteId), client.pendingIds(alice, space.id))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/spaces/${space.id}/members") { bearerAuth(bob) }.status)
        assertEquals("Trip", client.spaces(alice).single { it.id == space.id }.name)
    }

    @Test
    fun editorCannotManageMembersOrTheSpace() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR, carol to Role.VIEWER)
        val carolId = client.userId(carol)
        val inviteId = client.invite(alice, space.id, Role.VIEWER).id
        assertAllNotFound(ownerCalls(client, space.id, carolId, inviteId, bob))
        assertEquals(listOf(inviteId), client.pendingIds(alice, space.id))
        assertEquals(Role.VIEWER, client.spaces(carol).single { it.id == space.id }.role)
        assertEquals(HttpStatusCode.Created, client.upload(bob, spaceId = space.id).status)
    }

    @Test
    fun ownerMayDoEverything() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER)
        val bobId = client.userId(bob)
        val inviteId = client.invite(alice, space.id, Role.VIEWER).id
        ownerCalls(client, space.id, bobId, inviteId, alice).forEach { (name, call) ->
            assertTrue(call().status.value in 200..299, name)
        }
    }

    @Test
    fun malformedIdsAre404() = withApp { client ->
        client.createSpace(alice, "Trip")
        listOf("not-a-uuid", "..%2F..%2Fx").forEach { id ->
            val response = client.get("/api/v1/spaces/$id/members") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.NotFound, response.status, id)
            assertEquals("NOT_FOUND", response.errorCode(), id)
        }
        val space = client.personalSpace(alice)
        val response = client.delete("/api/v1/spaces/${space.id}/members/not-a-uuid") { bearerAuth(alice) }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun withoutTokenIs401() = withApp { client ->
        val id = "00000000-0000-0000-0000-000000000000"
        val calls = listOf<suspend () -> HttpResponse>(
            { client.get("/api/v1/spaces") },
            { client.post("/api/v1/spaces") },
            { client.get("/api/v1/spaces/$id/members") },
            { client.delete("/api/v1/spaces/$id") },
            { client.post("/api/v1/spaces/$id/leave") },
            { client.post("/api/v1/spaces/$id/invites") },
            { client.get("/api/v1/spaces/$id/invites") },
            { client.delete("/api/v1/spaces/$id/invites/$id") },
            { client.post("/api/v1/invites/preview") },
            { client.post("/api/v1/invites/accept") },
        )
        calls.forEach { assertEquals(HttpStatusCode.Unauthorized, it().status) }
    }

    @Test
    fun rolesAreOrdered() {
        assertTrue(Role.OWNER.atLeast(Role.EDITOR))
        assertTrue(Role.EDITOR.atLeast(Role.EDITOR))
        assertTrue(Role.EDITOR.atLeast(Role.VIEWER))
        assertFalse(Role.VIEWER.atLeast(Role.EDITOR))
        assertFalse(Role.EDITOR.atLeast(Role.OWNER))
    }
}
