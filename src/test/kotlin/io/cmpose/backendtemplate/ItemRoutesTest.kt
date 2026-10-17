package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.feature.sample.ItemDraft
import io.cmpose.backendtemplate.feature.sample.ItemResponse
import io.cmpose.backendtemplate.feature.sample.Items
import io.cmpose.backendtemplate.sharing.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemRoutesTest {
    @BeforeTest
    fun clean() = TestDb.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(authDeps()) }
        block(jsonClient())
    }

    private fun rowCount() = transaction(TestDb.database) { Items.selectAll().count() }

    private val alice = TestKeys.token(sub = "alice")

    @Test
    fun createListGetUpdateAndDelete() = withApp { client ->
        val created = client.createItem(alice, ItemDraft("  Milk  ", note = "2 litres", dueDate = 1_700_000_000_000))
        assertEquals("Milk", created.title)
        assertEquals("2 litres", created.note)
        assertEquals(1_700_000_000_000, created.dueDate)

        assertEquals(listOf(created), client.itemPage(alice).items)
        assertEquals(created, client.getItem(alice, created.id).body<ItemResponse>())

        val patched = client.patchItem(alice, created.id, """{"title":"Bread","note":null}""")
        assertEquals(HttpStatusCode.OK, patched.status)
        val updated = patched.body<ItemResponse>()
        assertEquals("Bread", updated.title)
        assertNull(updated.note)
        assertEquals(created.dueDate, updated.dueDate)
        assertEquals(created.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt >= created.updatedAt)
        assertEquals(updated, client.getItem(alice, created.id).body<ItemResponse>())

        val untouched = client.patchItem(alice, created.id, "{}").body<ItemResponse>()
        assertEquals(updated.copy(updatedAt = untouched.updatedAt), untouched)

        assertEquals(HttpStatusCode.NoContent, client.deleteItem(alice, created.id).status)
        assertEquals(HttpStatusCode.NotFound, client.getItem(alice, created.id).status)
        assertEquals(HttpStatusCode.NotFound, client.deleteItem(alice, created.id).status)
        assertTrue(client.itemPage(alice).items.isEmpty())
        assertEquals(0L, rowCount())
    }

    @Test
    fun pagesWithLimitAndCursor() = withApp { client ->
        val ids = (1..3).map { client.createItem(alice, ItemDraft("Item $it")).id }.toSet()
        val first = client.itemPage(alice, "limit=2")
        assertEquals(2, first.items.size)
        val cursor = assertNotNull(first.nextCursor)
        val second = client.itemPage(alice, "limit=2&cursor=$cursor")
        assertEquals(1, second.items.size)
        assertNull(second.nextCursor)
        assertEquals(ids, (first.items + second.items).map { it.id }.toSet())
        // Newest first: each page continues where the last one stopped.
        val all = client.itemPage(alice).items
        assertEquals(all.map { it.id }, (first.items + second.items).map { it.id })
        assertTrue(all.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt })
    }

    @Test
    fun exactlyOneFullPageHasNoNextCursor() = withApp { client ->
        repeat(2) { client.createItem(alice) }
        val page = client.itemPage(alice, "limit=2")
        assertEquals(2, page.items.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun everyRouteNeedsAToken() = withApp { client ->
        val id = client.createItem(alice).id
        listOf(
            client.get("/api/v1/items"),
            client.post("/api/v1/items"),
            client.get("/api/v1/items/$id"),
            client.patch("/api/v1/items/$id"),
            client.delete("/api/v1/items/$id"),
            client.get("/api/v1/items/$id") { bearerAuth("not-a-token") },
        ).forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
        assertEquals(1L, rowCount())
    }

    @Test
    fun deletingTheAccountDeletesTheItems() = withApp { client ->
        repeat(2) { client.createItem(alice) }
        val bob = TestKeys.token(sub = "bob")
        client.createItem(bob)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/me") { bearerAuth(alice) }.status)
        assertEquals(1L, rowCount())
        assertEquals(1, client.itemPage(bob).items.size)
    }

    // cmpose:sharing
    @Test
    fun itemsLiveInTheChosenSpace() = withApp { client ->
        val bob = TestKeys.token(sub = "bob")
        val carol = TestKeys.token(sub = "carol")
        val space = client.sharedSpace(alice, "Trip", bob to Role.VIEWER, carol to Role.EDITOR)
        val personal = client.createItem(alice)
        assertEquals(client.personalSpace(alice).id, personal.spaceId)

        val shared = client.createItem(carol, ItemDraft("Tent", spaceId = space.id))
        assertEquals(space.id, shared.spaceId)
        assertEquals(listOf(shared), client.itemPage(bob, "spaceId=${space.id}").items)
        assertEquals(listOf(personal), client.itemPage(alice).items)
        assertEquals(listOf(shared), client.itemPage(alice, "spaceId=${space.id}").items)
        assertTrue(client.itemPage(carol).items.isEmpty())

        val renamed = client.patchItem(carol, shared.id, """{"title":"Big tent"}""")
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals("Big tent", client.getItem(bob, shared.id).body<ItemResponse>().title)
        assertEquals(HttpStatusCode.NoContent, client.deleteItem(alice, shared.id).status)
    }

    @Test
    fun deletingASpaceDeletesItsItems() = withApp { client ->
        val space = client.createSpace(alice, "Trip")
        client.createItem(alice, ItemDraft("Tent", spaceId = space.id))
        client.createItem(alice)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/spaces/${space.id}") { bearerAuth(alice) }.status)
        assertEquals(1L, rowCount())
    }
    // /cmpose:sharing
}
