package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.auth.VerifiedIdentity
import io.cmpose.backendtemplate.sharing.AcceptResult
import io.cmpose.backendtemplate.sharing.InvitePreview
import io.cmpose.backendtemplate.sharing.InviteRepository
import io.cmpose.backendtemplate.sharing.InviteResponse
import io.cmpose.backendtemplate.sharing.InviteState
import io.cmpose.backendtemplate.sharing.MemberResponse
import io.cmpose.backendtemplate.sharing.Invites
import io.cmpose.backendtemplate.sharing.PendingInvite
import io.cmpose.backendtemplate.sharing.RevokeResult
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.cmpose.backendtemplate.sharing.SpaceResponse
import io.cmpose.backendtemplate.users.ProfileUpdate
import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.UserRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
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
    private val dave = TestKeys.token(sub = "dave")

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
    fun inviteListSkipsTheSensitiveLimit() = withApp(testConfig().copy(rateLimitSensitivePerMinute = 1)) { client ->
        val space = client.createSpace(alice, "Trip")
        // The members screen lists on every load: that must not use up the owner's sensitive budget.
        repeat(2) { assertEquals(HttpStatusCode.OK, client.pendingInvites(alice, space.id).status) }
        val link = client.invite(alice, space.id, Role.VIEWER)
        // The revoke still counts against the sensitive limit, which the create used up.
        assertEquals(HttpStatusCode.TooManyRequests, client.revoke(alice, space.id, link.id).status)
        assertEquals(listOf(link.id), client.pending(space.id).map { it.id })
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

    private fun inviteCount() = transaction(TestDb.database) { Invites.selectAll().count() }

    private suspend fun HttpClient.state(inviteToken: String) = preview(bob, inviteToken).body<InvitePreview>().state

    /** Alice's view of [spaceId]'s pending invites. */
    private suspend fun HttpClient.pending(spaceId: String): List<PendingInvite> {
        val response = pendingInvites(alice, spaceId)
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    @Test
    fun pendingInviteQuota() = withApp(testConfig().copy(maxPendingInvitesPerSpace = 2)) { client ->
        val space = client.createSpace(alice, "Trip")
        val aliceId = UserId(UUID.fromString(client.userId(alice)))
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        // An expired invite is not pending: it does not count.
        InviteRepository(TestDb.database, ttlDays = 7, now = { eightDaysAgo })
            .create(UUID.fromString(space.id), aliceId, Role.EDITOR)
        val first = client.invite(alice, space.id, Role.EDITOR)
        val second = client.invite(alice, space.id, Role.VIEWER)
        client.inviteResponse(alice, space.id, Role.VIEWER).assertQuotaExceeded("invites")
        // Nor does a used one: once Bob accepts the first, there is room for one more.
        assertEquals(HttpStatusCode.OK, client.accept(bob, first.token).status)
        client.invite(alice, space.id, Role.VIEWER)
        client.inviteResponse(alice, space.id, Role.VIEWER).assertQuotaExceeded("invites")
        // Nor does a revoked one.
        assertEquals(HttpStatusCode.NoContent, client.revoke(alice, space.id, second.id).status)
        client.invite(alice, space.id, Role.VIEWER)
        client.inviteResponse(alice, space.id, Role.VIEWER).assertQuotaExceeded("invites")
        // The quota is per space.
        client.invite(alice, client.createSpace(alice, "Other").id, Role.VIEWER)
        assertEquals(6L, inviteCount())
    }

    @Test
    fun concurrentInviteCreatesCannotBothPass() = assertNoDeadlock {
        withApp(testConfig().copy(maxPendingInvitesPerSpace = 1)) { client ->
            val space = client.createSpace(alice, "Trip")
            // One create stops at its insert with the space row locked, the other at that row; without the lock
            // both would count no invite and stop at the insert.
            val responses = InsertGate("invites").use { gate ->
                coroutineScope {
                    val creates = List(2) { async { client.inviteResponse(alice, space.id, Role.VIEWER) } }
                    gate.awaitWaiting(2)
                    gate.close()
                    creates.awaitAll()
                }
            }
            assertEquals(
                listOf(HttpStatusCode.Created, HttpStatusCode.Conflict),
                responses.map { it.status }.sortedBy { it.value },
            )
            responses.single { it.status == HttpStatusCode.Conflict }.assertQuotaExceeded("invites")
            assertEquals(1L, inviteCount())
        }
    }

    @Test
    fun loweredInviteQuotaKeepsInvites() {
        lateinit var spaceId: String
        val links = mutableListOf<InviteResponse>()
        withApp { client ->
            spaceId = client.createSpace(alice, "Trip").id
            repeat(3) { links += client.invite(alice, spaceId, Role.VIEWER) }
        }
        withApp(testConfig().copy(maxPendingInvitesPerSpace = 2)) { client ->
            links.forEach { assertEquals(InviteState.VALID, client.state(it.token)) }
            client.inviteResponse(alice, spaceId, Role.VIEWER).assertQuotaExceeded("invites")
            // The owner still sees all three and can revoke each.
            assertEquals(links.map { it.id }.toSet(), client.pending(spaceId).map { it.id }.toSet())
            links.forEach { assertEquals(HttpStatusCode.NoContent, client.revoke(alice, spaceId, it.id).status) }
            assertEquals(emptyList(), client.pending(spaceId))
        }
        assertEquals(3L, inviteCount())
    }

    @Test
    fun memberQuotaOnAccept() = withApp(testConfig().copy(maxMembersPerSpace = 2)) { client ->
        val space = client.createSpace(alice, "Trip")
        val first = client.invite(alice, space.id, Role.EDITOR)
        val second = client.invite(alice, space.id, Role.VIEWER)
        // The owner counts: Bob makes two.
        assertEquals(HttpStatusCode.OK, client.accept(bob, first.token).status)
        client.accept(carol, second.token).assertQuotaExceeded("members")
        assertTrue(client.spaces(carol).none { it.id == space.id })
        assertEquals(InviteState.VALID, client.state(second.token))
        // A member already in the full space is let in as before; that adds no one.
        val again = client.accept(bob, second.token)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(SpaceResponse(space.id, "Trip", Role.EDITOR, personal = false, memberCount = 2), again.body())
        assertEquals(HttpStatusCode.OK, client.accept(alice, second.token).status)
        assertEquals(InviteState.VALID, client.state(second.token))
    }

    @Test
    fun loweredMemberQuotaKeepsMembers() {
        lateinit var space: SpaceResponse
        withApp { client -> space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR, carol to Role.VIEWER) }
        withApp(testConfig().copy(maxMembersPerSpace = 2)) { client ->
            // Three members in a space now capped at two: nobody is removed, each keeps the space and the role.
            val members = client.get("/api/v1/spaces/${space.id}/members") { bearerAuth(alice) }
            assertEquals(HttpStatusCode.OK, members.status)
            assertEquals(3, members.body<List<MemberResponse>>().size)
            assertEquals(Role.EDITOR, client.spaces(bob).single { it.id == space.id }.role)
            assertEquals(Role.VIEWER, client.spaces(carol).single { it.id == space.id }.role)
            assertEquals(3, client.spaces(alice).single { it.id == space.id }.memberCount)
            // Only a new member is refused.
            val link = client.invite(alice, space.id, Role.VIEWER)
            client.accept(dave, link.token).assertQuotaExceeded("members")
            assertTrue(client.spaces(dave).none { it.id == space.id })
            assertEquals(InviteState.VALID, client.state(link.token))
        }
    }

    @Test
    fun concurrentAcceptsCannotOverfill() = assertNoDeadlock {
        withApp(testConfig().copy(maxMembersPerSpace = 2)) { client ->
            val space = client.createSpace(alice, "Trip")
            val links = List(2) { client.invite(alice, space.id, Role.EDITOR) }
            client.userId(bob)
            client.userId(carol)
            // One accept stops at its member insert with the space row locked, the other at that row; without
            // the lock both would count one member and stop at the insert.
            val responses = InsertGate("space_members").use { gate ->
                coroutineScope {
                    val accepts = listOf(bob, carol).zip(links).map { (token, link) ->
                        async { client.accept(token, link.token) }
                    }
                    gate.awaitWaiting(2)
                    gate.close()
                    accepts.awaitAll()
                }
            }
            assertEquals(
                listOf(HttpStatusCode.OK, HttpStatusCode.Conflict),
                responses.map { it.status }.sortedBy { it.value },
            )
            responses.single { it.status == HttpStatusCode.Conflict }.assertQuotaExceeded("members")
            assertEquals(2, client.spaces(alice).single { it.id == space.id }.memberCount)
        }
    }

    @Test
    fun editorUploadAndOwnerInviteDoNotDeadlock() {
        val config = testConfig().copy(
            filesDir = createTempDirectory("invite-test").toString(),
            maxStorageBytesPerUser = 1000,
            maxPendingInvitesPerSpace = 5,
        )
        assertNoDeadlock {
            withApp(config) { client ->
                val space = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
                // Each request stops at its insert holding its quota lock: the invite the space row, Bob's upload
                // Alice's user row (she owns the space, so she pays). Each insert's foreign key then key-shares the
                // row the other holds, which only a lock that leaves key-share free lets through.
                val responses = InsertGate("invites", "files").use { gate ->
                    coroutineScope {
                        val invite = async { client.inviteResponse(alice, space.id, Role.VIEWER) }
                        gate.awaitWaiting(1)
                        val upload = async { client.upload(bob, spaceId = space.id) }
                        gate.awaitWaiting(2)
                        gate.close()
                        listOf(invite, upload).awaitAll()
                    }
                }
                assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Created), responses.map { it.status })
            }
        }
    }

    @Test
    fun listShowsValidInvitesNewestFirstWithoutTokens() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val spaceId = UUID.fromString(space.id)
        val aliceId = UserId(UUID.fromString(client.userId(alice)))
        val used = client.invite(alice, space.id, Role.EDITOR)
        assertEquals(HttpStatusCode.OK, client.accept(bob, used.token).status)
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        val expired = InviteRepository(TestDb.database, ttlDays = 7, now = { eightDaysAgo })
            .create(spaceId, aliceId, Role.EDITOR)
        val minuteAgo = System.currentTimeMillis() - 60_000
        val older = InviteRepository(TestDb.database, ttlDays = 7, now = { minuteAgo })
            .create(spaceId, aliceId, Role.EDITOR)
        val newest = client.invite(alice, space.id, Role.VIEWER)
        // Another space's invite is not listed.
        client.invite(alice, client.createSpace(alice, "Other").id, Role.VIEWER)

        val response = client.pendingInvites(alice, space.id)
        assertEquals(HttpStatusCode.OK, response.status)
        val ttl = 7L * 24 * 60 * 60 * 1000
        assertEquals(
            listOf(
                PendingInvite(newest.id, Role.VIEWER, newest.expiresAt - ttl, newest.expiresAt),
                PendingInvite(older.id, Role.EDITOR, minuteAgo, older.expiresAt),
            ),
            response.body<List<PendingInvite>>(),
        )
        val text = response.bodyAsText()
        assertTrue("\"token\"" !in text, text)
        listOf(used, expired, older, newest).forEach {
            assertTrue(it.token !in text && sha256Hex(it.token) !in text, text)
        }
    }

    @Test
    fun revokedInvitePreviewsAsRevokedAndCannotBeAccepted() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val link = client.invite(alice, space.id, Role.EDITOR)
        assertEquals(HttpStatusCode.NoContent, client.revoke(alice, space.id, link.id).status)
        val preview = client.preview(bob, link.token)
        assertEquals(HttpStatusCode.OK, preview.status)
        assertEquals(
            InvitePreview("Trip", inviterName = null, Role.EDITOR, link.expiresAt, InviteState.REVOKED),
            preview.body<InvitePreview>(),
        )
        val accept = client.accept(bob, link.token)
        assertEquals(HttpStatusCode.Gone, accept.status)
        assertEquals("INVITE_REVOKED", accept.errorCode())
        assertTrue(client.spaces(bob).none { it.id == space.id })
        assertEquals(emptyList(), client.pending(space.id))
    }

    @Test
    fun revokeIsIdempotent() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val link = client.invite(alice, space.id, Role.VIEWER)
        assertEquals(HttpStatusCode.NoContent, client.revoke(alice, space.id, link.id).status)
        val afterFirst = inviteRows()
        assertEquals(HttpStatusCode.NoContent, client.revoke(alice, space.id, link.id).status)
        assertEquals(afterFirst, inviteRows())
        assertEquals(InviteState.REVOKED, client.state(link.token))
        // An expired invite is left as it is: it stays expired.
        val aliceId = UserId(UUID.fromString(client.userId(alice)))
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        val expired = InviteRepository(TestDb.database, ttlDays = 7, now = { eightDaysAgo })
            .create(UUID.fromString(space.id), aliceId, Role.EDITOR)
        assertEquals(HttpStatusCode.NoContent, client.revoke(alice, space.id, expired.id).status)
        assertEquals(InviteState.EXPIRED, client.state(expired.token))
    }

    @Test
    fun revokedThenExpiredStaysRevoked() {
        val db = TestDb.database
        val users = UserRepository(db)
        val owner = users.resolveOrCreate(VerifiedIdentity("firebase", "alice", null, false, null))
        val joiner = users.resolveOrCreate(VerifiedIdentity("firebase", "bob", null, false, null))
        val spaces = SpaceRepository(db)
        val spaceId = UUID.fromString(spaces.create(owner, "Trip").id)
        var clock = 1_000L
        val invites = InviteRepository(db, ttlDays = 1, now = { clock })
        val link = invites.create(spaceId, owner, Role.VIEWER)
        assertEquals(RevokeResult.REVOKED, invites.revoke(spaceId, UUID.fromString(link.id)))
        assertEquals(InviteState.REVOKED, invites.preview(link.token)?.state)
        clock = link.expiresAt + 1
        assertEquals(InviteState.REVOKED, invites.preview(link.token)?.state)
        assertEquals(AcceptResult.Revoked, invites.accept(link.token, joiner, spaces))
        assertEquals(RevokeResult.REVOKED, invites.revoke(spaceId, UUID.fromString(link.id)))
    }

    @Test
    fun revokingAUsedInviteIs409() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val link = client.invite(alice, space.id, Role.EDITOR)
        assertEquals(HttpStatusCode.OK, client.accept(bob, link.token).status)
        val revoke = client.revoke(alice, space.id, link.id)
        assertEquals(HttpStatusCode.Conflict, revoke.status)
        assertEquals("INVITE_USED", revoke.errorCode())
        assertEquals(Role.EDITOR, client.spaces(bob).single { it.id == space.id }.role)
        assertEquals(InviteState.USED, client.state(link.token))
    }

    @Test
    fun revokeOfAnotherSpacesInviteIs404() = withApp { client ->
        val first = client.createSpace(alice, "Trip")
        val other = client.createSpace(alice, "Other")
        val link = client.invite(alice, other.id, Role.VIEWER)
        listOf(link.id, UUID.randomUUID().toString(), "abc").forEach { inviteId ->
            val response = client.revoke(alice, first.id, inviteId)
            assertEquals(HttpStatusCode.NotFound, response.status, inviteId)
            assertEquals("NOT_FOUND", response.errorCode(), inviteId)
        }
        assertEquals(InviteState.VALID, client.state(link.token))
        assertEquals(listOf(link.id), client.pending(other.id).map { it.id })
    }

    @Test
    fun listIsCappedAt200() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        val aliceId = UserId(UUID.fromString(client.userId(alice)))
        var clock = System.currentTimeMillis() - 10_000
        // The quota is off (0): nothing but the list's own limit keeps it short.
        val invites = InviteRepository(TestDb.database, ttlDays = 7, now = { clock++ })
        val ids = List(201) { invites.create(UUID.fromString(space.id), aliceId, Role.VIEWER).id }
        assertEquals(ids.reversed().take(200), client.pending(space.id).map { it.id })
    }

    @Test
    fun revokeAndAcceptRace() = assertNoDeadlock {
        withApp { client ->
            val space = client.createSpace(alice, "Trip")
            client.userId(bob)
            // Whichever request comes first stops at the invite row with the space row locked, the other at the
            // space row. A revoke that comes first wins: the accept then finds it revoked (204, 410). An accept
            // that comes first wins: the revoke then finds it used (409, 200). Both cannot pass.
            for (revokeFirst in listOf(true, false)) {
                val link = client.invite(alice, space.id, Role.VIEWER)
                val revoke = suspend { client.revoke(alice, space.id, link.id) }
                val accept = suspend { client.accept(bob, link.token) }
                val (revoked, accepted) =
                    if (revokeFirst) race(revoke, accept) else race(accept, revoke).let { (a, r) -> r to a }
                val statuses = revoked.status to accepted.status
                if (revokeFirst) {
                    assertEquals(HttpStatusCode.NoContent to HttpStatusCode.Gone, statuses)
                    assertEquals("INVITE_REVOKED", accepted.errorCode())
                    assertEquals(InviteState.REVOKED, client.state(link.token))
                    assertTrue(client.spaces(bob).none { it.id == space.id })
                } else {
                    assertEquals(HttpStatusCode.Conflict to HttpStatusCode.OK, statuses)
                    assertEquals("INVITE_USED", revoked.errorCode())
                    assertEquals(InviteState.USED, client.state(link.token))
                    assertTrue(client.spaces(bob).any { it.id == space.id })
                }
            }
        }
    }

    /** Runs [first] until it stops at the gated `invites` table, then [second] until it waits too, then both on. */
    private suspend fun race(
        first: suspend () -> HttpResponse,
        second: suspend () -> HttpResponse,
    ): Pair<HttpResponse, HttpResponse> = InsertGate("invites").use { gate ->
        coroutineScope {
            val firstCall = async { first() }
            val secondCall = try {
                gate.awaitWaiting(1)
                async { second() }.also { gate.awaitWaiting(2) }
            } finally {
                gate.close()
            }
            firstCall.await() to secondCall.await()
        }
    }
}
