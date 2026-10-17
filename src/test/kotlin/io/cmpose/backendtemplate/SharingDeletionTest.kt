package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.feature.sample.ItemDraft
import io.cmpose.backendtemplate.files.FileResponse
import io.cmpose.backendtemplate.files.StoredFiles
import io.cmpose.backendtemplate.sharing.Invites
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.SpaceMembers
import io.cmpose.backendtemplate.sharing.Spaces
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Account deletion with sharing: owned spaces go with everything in them; what the user added to other people's spaces
 * stays there, with the uploader erased.
 */
class SharingDeletionTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private val alice = TestKeys.token(sub = "alice")
    private val bob = TestKeys.token(sub = "bob")
    private val carol = TestKeys.token(sub = "carol")

    @Test
    fun deletionRemovesOwnedSpacesAndMembershipsAndKeepsOthers() = testApplication {
        val dir = createTempDirectory("sharing-deletion-test")
        application { module(authDeps(config = testConfig().copy(filesDir = dir.toString()))) }
        val client = jsonClient()
        val aliceId = UUID.fromString(client.userId(alice))
        val owned = client.sharedSpace(alice, "Alice's", bob to Role.EDITOR)
        val bobs = client.sharedSpace(bob, "Bob's", alice to Role.EDITOR, carol to Role.VIEWER)
        client.invite(alice, owned.id, Role.VIEWER)
        assertEquals(HttpStatusCode.Created, client.upload(alice).status)
        assertEquals(HttpStatusCode.Created, client.upload(bob, spaceId = owned.id).status)
        val aliceInBobs = client.upload(alice, spaceId = bobs.id).body<FileResponse>().id
        val itemInBobs = client.createItem(alice, ItemDraft("Tickets", spaceId = bobs.id)).id
        assertEquals(3, dir.listDirectoryEntries().size)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/me") { bearerAuth(alice) }.status)

        transaction(TestDb.database) {
            assertEquals(0L, Spaces.selectAll().where { Spaces.ownerId eq aliceId }.count())
            assertEquals(0L, SpaceMembers.selectAll().where { SpaceMembers.userId eq aliceId }.count())
            assertEquals(0L, Invites.selectAll().where { Invites.createdBy eq aliceId }.count())
            assertEquals(2L, Invites.selectAll().count())
            val kept = StoredFiles.selectAll().single()
            assertEquals(UUID.fromString(aliceInBobs), kept[StoredFiles.id])
            assertNull(kept[StoredFiles.uploadedBy])
        }
        assertEquals(1, dir.listDirectoryEntries().size)
        assertTrue(client.spaces(bob).none { it.id == owned.id })
        val bobsNow = client.spaces(bob).single { it.id == bobs.id }
        assertEquals(2, bobsNow.memberCount)
        assertEquals(Role.VIEWER, client.spaces(carol).single { it.id == bobs.id }.role)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/files/$aliceInBobs") { bearerAuth(carol) }.status)
        assertEquals(HttpStatusCode.OK, client.getItem(carol, itemInBobs).status)
    }
}
