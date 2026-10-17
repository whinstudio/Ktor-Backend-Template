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

    @Test
    fun listsTheItemRoutes() {
        val paths = OpenApi.document(null)["paths"]!!.jsonObject
        assertTrue("/items" in paths)
        assertEquals(setOf("get", "patch", "delete", "parameters"), paths["/items/{id}"]!!.jsonObject.keys)
        val limit = paths["/items"]!!.jsonObject["get"]!!.jsonObject["parameters"]!!.jsonArray
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "limit" }
        assertEquals(JsonPrimitive(200), limit["schema"]!!.jsonObject["maximum"])
    }

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
            addAll(listOf("/me", "/files", "/files/{id}", "/items", "/items/{id}"))
            // cmpose:sharing
            addAll(listOf("/spaces", "/spaces/{id}", "/spaces/{id}/members", "/spaces/{id}/members/{userId}"))
            addAll(listOf("/spaces/{id}/leave", "/spaces/{id}/invites", "/invites/preview", "/invites/accept"))
            // /cmpose:sharing
        }
        assertEquals(expected, OpenApi.document(null)["paths"]!!.jsonObject.keys)
    }

    @Test
    fun builtOnceAndServedTheSame() = testApplication {
        application { module(testDeps()) }
        val first = client.get("/api/v1/openapi.json").bodyAsText()
        assertEquals(first, client.get("/api/v1/openapi.json").bodyAsText())
        assertEquals(OpenApi.document(null), Json.parseToJsonElement(first))
    }
}
