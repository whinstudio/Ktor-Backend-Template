package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.openapi.OpenApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiTest {
    private fun JsonObject.serverUrl() = this["servers"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content

    @Test
    fun servedAsJsonWithThePublicBaseUrl() = testApplication {
        application { module(testDeps(config = testConfig().copy(publicBaseUrl = "https://x.test/myapp"))) }
        val response = client.get("/api/v1/openapi.json")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("https://x.test/myapp/api/v1", document.serverUrl())
        assertEquals("3.0.3", document["openapi"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutPublicBaseUrlTheServerIsRelative() {
        assertEquals("/api/v1", OpenApi.document(null).serverUrl())
        assertEquals("https://x.test/myapp/api/v1", OpenApi.document("https://x.test/myapp/").serverUrl())
    }

    // cmpose:sample
    @Test
    fun listsTheItemRoutes() {
        val paths = OpenApi.document(null)["paths"]!!.jsonObject
        assertTrue("/items" in paths)
        assertEquals(setOf("get", "patch", "delete", "parameters"), paths["/items/{id}"]!!.jsonObject.keys)
        val limit = paths["/items"]!!.jsonObject["get"]!!.jsonObject["parameters"]!!.jsonArray
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "limit" }
        assertEquals(JsonPrimitive(200), limit["schema"]!!.jsonObject["maximum"])
        val recent = paths["/items/recent"]!!.jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!
        val schema = recent.jsonObject["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals(JsonPrimitive("array"), schema["type"])
        assertEquals(JsonPrimitive(20), schema["maxItems"])
    }
    // /cmpose:sample

    @Test
    fun everyRefResolves() {
        val document = OpenApi.document(null)
        val refs = mutableListOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "\$ref") refs += value.jsonPrimitive.content else walk(value)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(document)
        assertTrue(refs.isNotEmpty())
        refs.forEach { ref ->
            val target = ref.removePrefix("#/").split('/').fold<String, JsonElement?>(document) { node, part ->
                (node as? JsonObject)?.get(part)
            }
            assertTrue(target is JsonObject, "unresolved $ref")
        }
    }

    @Test
    fun listsEveryRouteAndNothingElse() {
        val expected = buildSet {
            addAll(listOf("/me", "/me/profile", "/files", "/files/{id}"))
            // cmpose:sample
            addAll(listOf("/items", "/items/recent", "/items/{id}"))
            // /cmpose:sample
            // cmpose:sharing
            addAll(listOf("/spaces", "/spaces/{id}", "/spaces/{id}/members", "/spaces/{id}/members/{userId}"))
            addAll(listOf("/spaces/{id}/leave", "/spaces/{id}/invites", "/invites/preview", "/invites/accept"))
            add("/spaces/{id}/invites/{inviteId}")
            // /cmpose:sharing
        }
        assertEquals(expected, OpenApi.document(null)["paths"]!!.jsonObject.keys)
    }

    @Test
    fun theMeSchemaRequiresEmailAndDisplayName() {
        val me = OpenApi.document(null)["components"]!!.jsonObject["schemas"]!!.jsonObject["Me"]!!.jsonObject
        assertEquals(listOf("id", "email", "displayName"), me["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val properties = me["properties"]!!.jsonObject
        assertEquals(JsonPrimitive(true), properties["email"]!!.jsonObject["nullable"])
        assertEquals(JsonPrimitive(true), properties["displayName"]!!.jsonObject["nullable"])
    }

    @Test
    fun builtOnceAndServedTheSame() = testApplication {
        application { module(testDeps()) }
        val first = client.get("/api/v1/openapi.json").bodyAsText()
        assertEquals(first, client.get("/api/v1/openapi.json").bodyAsText())
        assertEquals(OpenApi.document(null), Json.parseToJsonElement(first))
    }
}
