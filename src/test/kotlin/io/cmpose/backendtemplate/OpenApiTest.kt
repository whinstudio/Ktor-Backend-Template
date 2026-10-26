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
    private fun JsonObject.schema(name: String) =
        this["components"]!!.jsonObject["schemas"]!!.jsonObject[name]!!.jsonObject
    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

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
        val me = OpenApi.document(null).schema("Me")
        assertEquals(listOf("id", "email", "displayName"), me["required"]!!.strings())
        val properties = me["properties"]!!.jsonObject
        assertEquals(JsonPrimitive(true), properties["email"]!!.jsonObject["nullable"])
        assertEquals(JsonPrimitive(true), properties["displayName"]!!.jsonObject["nullable"])
    }

    @Test
    fun theErrorCarriesTheQuota() {
        val detail = OpenApi.document(null).schema("Error")["properties"]!!.jsonObject["error"]!!.jsonObject
        val quota = detail["properties"]!!.jsonObject["quota"]!!.jsonObject
        assertEquals(JsonPrimitive("string"), quota["type"])
    }

    // cmpose:sharing
    @Test
    fun listsAndRevokesPendingInvites() {
        val paths = OpenApi.document(null)["paths"]!!.jsonObject
        val invites = paths["/spaces/{id}/invites"]!!.jsonObject
        assertEquals(setOf("parameters", "get", "post"), invites.keys)
        val listed = invites["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!
            .jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals(JsonPrimitive("array"), listed["type"])
        assertEquals(JsonPrimitive("#/components/schemas/PendingInvite"), listed["items"]!!.jsonObject["\$ref"])
        val invite = paths["/spaces/{id}/invites/{inviteId}"]!!.jsonObject
        assertEquals(setOf("parameters", "delete"), invite.keys)
        val revoked = invite["delete"]!!.jsonObject["responses"]!!.jsonObject
        assertTrue("204" in revoked, "no 204 on revoke")
        assertTrue("409" in revoked, "no 409 on revoke")
    }

    @Test
    fun thePendingInviteHasItsFieldsAndNoToken() {
        val pending = OpenApi.document(null).schema("PendingInvite")
        assertEquals(listOf("id", "role", "createdAt", "expiresAt"), pending["required"]!!.strings())
        assertEquals(setOf("id", "role", "createdAt", "expiresAt"), pending["properties"]!!.jsonObject.keys)
        assertTrue("token" !in pending["properties"]!!.jsonObject)
    }

    @Test
    fun aRevokedInviteIsAState() {
        val document = OpenApi.document(null)
        assertEquals(listOf("valid", "used", "expired", "revoked"), document.schema("InviteState")["enum"]!!.strings())
        val accept = document["paths"]!!.jsonObject["/invites/accept"]!!.jsonObject["post"]!!.jsonObject
        assertTrue("410" in accept["responses"]!!.jsonObject)
    }
    // /cmpose:sharing

    @Test
    fun builtOnceAndServedTheSame() = testApplication {
        application { module(testDeps()) }
        val first = client.get("/api/v1/openapi.json").bodyAsText()
        assertEquals(first, client.get("/api/v1/openapi.json").bodyAsText())
        assertEquals(OpenApi.document(null), Json.parseToJsonElement(first))
    }
}
