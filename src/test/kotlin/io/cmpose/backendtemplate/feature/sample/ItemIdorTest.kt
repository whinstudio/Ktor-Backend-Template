package io.cmpose.backendtemplate.feature.sample

import io.cmpose.backendtemplate.TestDb
import io.cmpose.backendtemplate.TestKeys
import io.cmpose.backendtemplate.authDeps
import io.cmpose.backendtemplate.createItem
import io.cmpose.backendtemplate.deleteItem
import io.cmpose.backendtemplate.errorCode
import io.cmpose.backendtemplate.getItem
import io.cmpose.backendtemplate.itemPage
import io.cmpose.backendtemplate.jsonClient
import io.cmpose.backendtemplate.listItems
import io.cmpose.backendtemplate.module
import io.cmpose.backendtemplate.patchItem
import io.cmpose.backendtemplate.postItem
import io.cmpose.backendtemplate.sharedSpace
import io.cmpose.backendtemplate.sharing.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Another user's item looks exactly like a missing one: 404 `NOT_FOUND`, and nothing changes. */
class ItemIdorTest {
    @BeforeTest
    fun clean() = TestDb.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(authDeps()) }
        block(jsonClient())
    }

    private val alice = TestKeys.token(sub = "alice")
    private val bob = TestKeys.token(sub = "bob")

    private suspend fun assertHidden(response: HttpResponse) {
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.errorCode())
    }

    private suspend fun HttpClient.assertUnchanged(owner: String, item: ItemResponse) {
        val read = getItem(owner, item.id)
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(item, read.body<ItemResponse>())
    }

    @Test
    fun anotherUsersItemIs404ForEveryRoute() = withApp { client ->
        val item = client.createItem(alice, ItemDraft("Secret", note = "mine"))
        assertHidden(client.getItem(bob, item.id))
        assertHidden(client.patchItem(bob, item.id, """{"title":"Taken"}"""))
        assertHidden(client.deleteItem(bob, item.id))
        client.assertUnchanged(alice, item)
        assertTrue(client.itemPage(bob).items.isEmpty())
    }

    @Test
    fun anotherUsersItemLooksLikeAMissingOne() = withApp { client ->
        val item = client.createItem(alice)
        val foreign = client.getItem(bob, item.id)
        val missing = client.getItem(bob, "00000000-0000-0000-0000-000000000000")
        assertEquals(missing.status, foreign.status)
        assertEquals(missing.errorCode(), foreign.errorCode())
    }

    @Test
    fun nonUuidIdIs404() = withApp { client ->
        assertHidden(client.getItem(alice, "not-a-uuid"))
        assertHidden(client.patchItem(alice, "not-a-uuid", "{}"))
        assertHidden(client.deleteItem(alice, "..%2F..%2Fx"))
    }

    @Test
    fun anotherUsersCursorShowsNothingOfTheirs() = withApp { client ->
        repeat(3) { client.createItem(alice) }
        val cursor = client.itemPage(alice, "limit=1").nextCursor!!
        assertTrue(client.itemPage(bob, "cursor=$cursor").items.isEmpty())
    }

    // cmpose:sharing
    private val carol = TestKeys.token(sub = "carol")

    @Test
    fun viewerCannotChangeOrDelete() = withApp { client ->
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER)
        val item = client.createItem(alice, ItemDraft("Tent", spaceId = space.id))
        assertEquals(HttpStatusCode.OK, client.getItem(bob, item.id).status)
        assertHidden(client.patchItem(bob, item.id, """{"title":"Taken"}"""))
        assertHidden(client.deleteItem(bob, item.id))
        assertHidden(client.postItem(bob, ItemDraft("Mine", spaceId = space.id)))
        client.assertUnchanged(alice, item)
        assertEquals(1, client.itemPage(alice, "spaceId=${space.id}").items.size)
    }

    @Test
    fun memberOfAnotherSpaceIs404() = withApp { client ->
        val trip = client.sharedSpace(alice, "Trip", bob to Role.EDITOR)
        client.sharedSpace(alice, "Home", carol to Role.EDITOR)
        val item = client.createItem(alice, ItemDraft("Tent", spaceId = trip.id))
        assertHidden(client.getItem(carol, item.id))
        assertHidden(client.patchItem(carol, item.id, """{"title":"Taken"}"""))
        assertHidden(client.deleteItem(carol, item.id))
        assertHidden(client.listItems(carol, "spaceId=${trip.id}"))
        assertHidden(client.postItem(carol, ItemDraft("Mine", spaceId = trip.id)))
        assertHidden(client.listItems(carol, "spaceId=not-a-uuid"))
        client.assertUnchanged(alice, item)
    }
    // /cmpose:sharing
}
