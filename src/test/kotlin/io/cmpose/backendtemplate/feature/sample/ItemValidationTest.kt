package io.cmpose.backendtemplate.feature.sample

import io.cmpose.backendtemplate.TestDb
import io.cmpose.backendtemplate.TestKeys
import io.cmpose.backendtemplate.authDeps
import io.cmpose.backendtemplate.createItem
import io.cmpose.backendtemplate.getItem
import io.cmpose.backendtemplate.jsonClient
import io.cmpose.backendtemplate.listItems
import io.cmpose.backendtemplate.module
import io.cmpose.backendtemplate.patchItem
import io.cmpose.backendtemplate.postItem
import io.cmpose.backendtemplate.postItemJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemValidationTest {
    @BeforeTest
    fun clean() = TestDb.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(authDeps()) }
        block(jsonClient())
    }

    private val alice = TestKeys.token(sub = "alice")

    private fun rowCount() = transaction(TestDb.database) { Items.selectAll().count() }

    /** The `field` names of a 400 `BAD_REQUEST`, in order. */
    private suspend fun HttpResponse.invalidFields(): List<String> {
        assertEquals(HttpStatusCode.BadRequest, status)
        val error = Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("BAD_REQUEST", error["code"]!!.jsonPrimitive.content)
        return error["fields"]?.jsonArray.orEmpty().map { it.jsonObject["field"]!!.jsonPrimitive.content }
    }

    @Test
    fun emptyTitleIs400NamingTitle() = withApp { client ->
        assertEquals("title", client.postItem(alice, ItemDraft("")).invalidFields()[0])
        assertEquals(listOf("title"), client.postItem(alice, ItemDraft("   ")).invalidFields())
        assertEquals(listOf("title"), client.postItemJson(alice, """{"note":"no title"}""").invalidFields())
        assertEquals(0L, rowCount())
    }

    @Test
    fun titleLongerThan200Is400() = withApp { client ->
        assertEquals(listOf("title"), client.postItem(alice, ItemDraft("a".repeat(201))).invalidFields())
        assertEquals(HttpStatusCode.Created, client.postItem(alice, ItemDraft("a".repeat(200))).status)
        // Characters, not UTF-16 units: 200 emoji fit.
        assertEquals(HttpStatusCode.Created, client.postItem(alice, ItemDraft("😀".repeat(200))).status)
    }

    @Test
    fun noteLongerThan2000AndNegativeDueDateAre400() = withApp { client ->
        assertEquals(listOf("note"), client.postItem(alice, ItemDraft("ok", note = "n".repeat(2001))).invalidFields())
        assertEquals(listOf("dueDate"), client.postItem(alice, ItemDraft("ok", dueDate = -1)).invalidFields())
        assertEquals(HttpStatusCode.Created, client.postItem(alice, ItemDraft("ok", note = "n".repeat(2000))).status)
    }

    @Test
    fun everyInvalidFieldIsReportedAtOnce() = withApp { client ->
        val response = client.postItem(alice, ItemDraft("", note = "n".repeat(2001), dueDate = -1))
        assertEquals(listOf("title", "note", "dueDate"), response.invalidFields())
    }

    @Test
    fun limitOutsideOneTo200Is400() = withApp { client ->
        listOf("limit=500", "limit=0", "limit=-1", "limit=abc", "limit=").forEach { query ->
            assertEquals(listOf("limit"), client.listItems(alice, query).invalidFields(), query)
        }
        assertEquals(HttpStatusCode.OK, client.listItems(alice, "limit=200").status)
    }

    @Test
    fun cursorNotFromThisServerIs400() = withApp { client ->
        listOf("cursor=abc", "cursor=%25%25", "cursor=").forEach { query ->
            assertEquals(listOf("cursor"), client.listItems(alice, query).invalidFields(), query)
        }
    }

    @Test
    fun patchIsValidatedAndChangesNothingWhenInvalid() = withApp { client ->
        val item = client.createItem(alice)
        assertEquals(listOf("title"), client.patchItem(alice, item.id, """{"title":""}""").invalidFields())
        assertEquals(listOf("title"), client.patchItem(alice, item.id, """{"title":null}""").invalidFields())
        val tooLong = """{"note":"${"n".repeat(2001)}"}"""
        assertEquals(listOf("note"), client.patchItem(alice, item.id, tooLong).invalidFields())
        assertEquals(item, client.getItem(alice, item.id).body<ItemResponse>())
    }

    @Test
    fun fieldsTheServerOwnsCannotBeSet() = withApp { client ->
        val item = client.createItem(alice)
        val forged = listOf(
            """{"title":"x","id":"00000000-0000-0000-0000-000000000000"}""",
            """{"title":"x","createdAt":1}""",
            """{"title":"x","ownerId":"00000000-0000-0000-0000-000000000000"}""",
        )
        forged.forEach { json ->
            assertEquals(HttpStatusCode.BadRequest, client.postItemJson(alice, json).status, json)
            assertEquals(HttpStatusCode.BadRequest, client.patchItem(alice, item.id, json).status, json)
        }
        assertEquals(item, client.getItem(alice, item.id).body<ItemResponse>())
        assertEquals(1L, rowCount())
    }

    @Test
    fun wrongTypesAndBrokenJsonAre400() = withApp { client ->
        val item = client.createItem(alice)
        listOf("""{"title":5}""", """{"dueDate":"soon"}""", "{", "[]").forEach { json ->
            assertEquals(HttpStatusCode.BadRequest, client.postItemJson(alice, json).status, json)
            assertEquals(HttpStatusCode.BadRequest, client.patchItem(alice, item.id, json).status, json)
        }
    }
}
