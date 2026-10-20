package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.auth.VerifiedIdentity
import io.cmpose.backendtemplate.sharing.AcceptResult
import io.cmpose.backendtemplate.sharing.InvitePreview
import io.cmpose.backendtemplate.sharing.InviteRepository
import io.cmpose.backendtemplate.sharing.InviteState
import io.cmpose.backendtemplate.sharing.Invites
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.cmpose.backendtemplate.sharing.SpaceResponse
import io.cmpose.backendtemplate.users.ProfileUpdate
import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.UserRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteRoutesTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private val alice = TestKeys.token(sub = "alice")
    private val bob = TestKeys.token(sub = "bob")
    private val carol = TestKeys.token(sub = "carol")

    private fun withApp(
        config: io.cmpose.backendtemplate.config.AppConfig = testConfig(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application { module(authDeps(config = config)) }
        block(jsonClient())
    }

    private fun sha256Hex(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun inviteIsSingleUseAndItsRoleIsApplied() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val link = client.invite(alice, space.id, Role.EDITOR)
        val preview = client.preview(bob, link.token)
        assertEquals(HttpStatusCode.OK, preview.status)
        assertEquals(
            InvitePreview("Trip", inviterName = null, Role.EDITOR, link.expiresAt, InviteState.VALID),
            preview.body<InvitePreview>(),
        )

        val joined = client.accept(bob, link.token)
        assertEquals(HttpStatusCode.OK, joined.status)
        assertEquals(SpaceResponse(space.id, "Trip", Role.EDITOR, personal = false, memberCount = 2), joined.body())

        val again = client.accept(carol, link.token)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("INVITE_USED", again.errorCode())
        assertTrue(client.spaces(carol).none { it.id == space.id })
    }

    @Test
    fun viewerInviteGivesViewer() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val joined = client.accept(bob, client.invite(alice, space.id, Role.VIEWER).token).body<SpaceResponse>()
        assertEquals(Role.VIEWER, joined.role)
    }

    @Test
    fun expiredInvitePreviewSaysExpiredAndAcceptIs410() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val aliceId = UserId(UUID.fromString(client.userId(alice)))
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        val old = InviteRepository(TestDb.database, ttlDays = 7, now = { eightDaysAgo })
            .create(UUID.fromString(space.id), aliceId, Role.EDITOR)
        val preview = client.preview(bob, old.token)
        assertEquals(HttpStatusCode.OK, preview.status)
        assertEquals(InviteState.EXPIRED, preview.body<InvitePreview>().state)
        val accept = client.accept(bob, old.token)
        assertEquals(HttpStatusCode.Gone, accept.status)
        assertEquals("INVITE_EXPIRED", accept.errorCode())
        assertTrue(client.spaces(bob).none { it.id == space.id })
    }

    @Test
    fun usedInvitePreviewSaysUsedAndAcceptIs409() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val link = client.invite(alice, space.id, Role.EDITOR)
        assertEquals(HttpStatusCode.OK, client.accept(bob, link.token).status)
        val preview = client.preview(carol, link.token)
        assertEquals(HttpStatusCode.OK, preview.status)
        assertEquals(
            InvitePreview("Trip", inviterName = null, Role.EDITOR, link.expiresAt, InviteState.USED),
            preview.body<InvitePreview>(),
        )
        val accept = client.accept(carol, link.token)
        assertEquals(HttpStatusCode.Conflict, accept.status)
        assertEquals("INVITE_USED", accept.errorCode())
        assertTrue(client.spaces(carol).none { it.id == space.id })
    }

    @Test
    fun previewNamesTheInviter() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val profile = client.put("/api/v1/me/profile") { jsonBody(alice, ProfileUpdate(displayName = "Alice")) }
        assertEquals(HttpStatusCode.NoContent, profile.status)
        val link = client.invite(alice, space.id, Role.VIEWER)
        assertEquals("Alice", client.preview(bob, link.token).body<InvitePreview>().inviterName)
    }

    @Test
    fun previewWithoutInviterNameIsNull() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val link = client.invite(alice, space.id, Role.VIEWER)
        val preview = client.preview(bob, link.token)
        assertEquals(HttpStatusCode.OK, preview.status)
        assertNull(preview.body<InvitePreview>().inviterName)
    }

    @Test
    fun inviteIsValidUntilItsLastMillisecond() {
        val db = TestDb.database
        val users = UserRepository(db)
        val owner = users.resolveOrCreate(VerifiedIdentity("firebase", "alice", null, false, null))
        val joiner = users.resolveOrCreate(VerifiedIdentity("firebase", "bob", null, false, null))
        val spaces = SpaceRepository(db)
        val space = spaces.create(owner, "Trip")
        var clock = 1_000L
        val invites = InviteRepository(db, ttlDays = 1, now = { clock })
        val link = invites.create(UUID.fromString(space.id), owner, Role.VIEWER)
        assertEquals(1_000L + 24 * 60 * 60 * 1000, link.expiresAt)
        clock = link.expiresAt - 1
        assertEquals(InviteState.VALID, invites.preview(link.token)?.state)
        clock = link.expiresAt
        assertEquals(InviteState.EXPIRED, invites.preview(link.token)?.state)
        assertEquals(AcceptResult.Expired, invites.accept(link.token, joiner, spaces))
        assertNull(spaces.membership(UUID.fromString(space.id), joiner))
    }

    @Test
    fun ttlFollowsTheConfig() = withApp(testConfig().copy(inviteTtlDays = 3)) { client ->
        val space = client.createSpace(alice, "Trip")
        val before = System.currentTimeMillis()
        val link = client.invite(alice, space.id, Role.VIEWER)
        val after = System.currentTimeMillis()
        val ttl = 3L * 24 * 60 * 60 * 1000
        assertTrue(link.expiresAt in (before + ttl)..(after + ttl))
    }

    @Test
    fun acceptingWhileAlreadyAMemberKeepsTheRoleAndTheInvite() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
        val link = client.invite(alice, space.id, Role.VIEWER)
        assertEquals(Role.EDITOR, client.accept(bob, link.token).body<SpaceResponse>().role)
        assertEquals(Role.OWNER, client.accept(alice, link.token).body<SpaceResponse>().role)
        assertEquals(Role.VIEWER, client.accept(carol, link.token).body<SpaceResponse>().role)
    }

    @Test
    fun memberReopeningAUsedOrExpiredInviteGetsTheSpace() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", carol to Role.VIEWER)
        val used = client.invite(alice, space.id, Role.EDITOR)
        assertEquals(HttpStatusCode.OK, client.accept(bob, used.token).status)
        val aliceId = UserId(UUID.fromString(client.userId(alice)))
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        val expired = InviteRepository(TestDb.database, ttlDays = 7, now = { eightDaysAgo })
            .create(UUID.fromString(space.id), aliceId, Role.EDITOR)
        val before = inviteRows()

        val reopened = client.accept(bob, used.token)
        assertEquals(HttpStatusCode.OK, reopened.status)
        assertEquals(SpaceResponse(space.id, "Trip", Role.EDITOR, personal = false, memberCount = 3), reopened.body())
        val viaExpired = client.accept(carol, expired.token)
        assertEquals(HttpStatusCode.OK, viaExpired.status)
        assertEquals(SpaceResponse(space.id, "Trip", Role.VIEWER, personal = false, memberCount = 3), viaExpired.body())
        assertEquals(before, inviteRows())
    }

    private fun inviteRows() = transaction(TestDb.database) {
        Invites.selectAll().map { row -> Invites.columns.map { row[it] } }.sortedBy { it.first().toString() }
    }

    @Test
    fun tokenIsNeverStoredInClear() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val link = client.invite(alice, space.id, Role.VIEWER)
        val values = transaction(TestDb.database) {
            val row = Invites.selectAll().single()
            Invites.columns.map { row[it]?.toString() }
        }
        assertTrue(values.none { it == link.token }, "$values")
        assertTrue(values.none { it != null && link.token in it })
        assertTrue(sha256Hex(link.token) in values)
        assertEquals(32, Base64.getUrlDecoder().decode(link.token).size)
        assertTrue(link.token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, link.token)
        assertNotEquals(link.token, client.invite(alice, space.id, Role.VIEWER).token)
    }

    @Test
    fun unknownOrMalformedTokenIs404() = withApp { client ->
        client.createSpace(alice, "Trip")
        val unknown = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        listOf(unknown, "", "short", "x".repeat(500)).forEach { token ->
            assertEquals(HttpStatusCode.NotFound, client.preview(bob, token).status, token)
            assertEquals(HttpStatusCode.NotFound, client.accept(bob, token).status, token)
        }
    }

    @Test
    fun ownerRoleCannotBeInvitedAndOnlyOwnersInvite() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
        val owner = client.inviteResponse(alice, space.id, Role.OWNER)
        assertEquals(HttpStatusCode.BadRequest, owner.status)
        assertEquals("BAD_REQUEST", owner.errorCode())
        assertEquals(HttpStatusCode.NotFound, client.inviteResponse(bob, space.id, Role.VIEWER).status)
        assertEquals(1L, transaction(TestDb.database) { Invites.selectAll().count() })
    }

    @Test
    fun previewAndAcceptAreRateLimited() = withApp(testConfig().copy(rateLimitSensitivePerMinute = 3)) { client ->
        val statuses = List(4) { client.preview(bob, "guess-$it").status }
        assertEquals(List(3) { HttpStatusCode.NotFound } + HttpStatusCode.TooManyRequests, statuses)
        assertEquals(HttpStatusCode.TooManyRequests, client.accept(bob, "guess").status)
    }

    @Test
    fun inviteCreationIsRateLimited() = withApp(testConfig().copy(rateLimitSensitivePerMinute = 3)) { client ->
        val space = client.createSpace(alice, "Trip")
        val statuses = List(4) { client.inviteResponse(alice, space.id, Role.VIEWER).status }
        assertEquals(List(3) { HttpStatusCode.Created } + HttpStatusCode.TooManyRequests, statuses)
        assertEquals(3L, transaction(TestDb.database) { Invites.selectAll().count() })
    }

    @Test
    fun concurrentAcceptsUseAnInviteOnce() {
        val db = TestDb.database
        val users = UserRepository(db)
        val owner = users.resolveOrCreate(VerifiedIdentity("firebase", "owner", null, false, null))
        val joiners = List(6) { users.resolveOrCreate(VerifiedIdentity("firebase", "joiner$it", null, false, null)) }
        val spaces = SpaceRepository(db)
        val space = spaces.create(owner, "Trip")
        val invites = InviteRepository(db, ttlDays = 7)
        val link = invites.create(UUID.fromString(space.id), owner, Role.EDITOR)
        val pool = Executors.newFixedThreadPool(joiners.size)
        val results = try {
            pool.invokeAll(joiners.map { Callable { invites.accept(link.token, it, spaces) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, results.count { it is AcceptResult.Joined }, "$results")
        assertEquals(joiners.size - 1, results.count { it == AcceptResult.Used }, "$results")
        assertEquals(2, spaces.get(UUID.fromString(space.id), owner)?.memberCount)
    }
}
