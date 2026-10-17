@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.openapi

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val API_PREFIX = "/api/v1"

/** The API description (see `OpenApiSpec.kt`); built once, on first use. */
object OpenApi {
    private val spec by lazy { openApiSpec() }

    /**
     * The document as JSON. Its one server is [publicBaseUrl] + `/api/v1`, or just `/api/v1` without one: behind a
     * proxy that strips a path prefix the app cannot see its outside address, so `PUBLIC_BASE_URL` supplies it.
     */
    fun document(publicBaseUrl: String?): JsonObject {
        val server = JsonObject(mapOf("url" to JsonPrimitive(publicBaseUrl.orEmpty().trimEnd('/') + API_PREFIX)))
        return JsonObject(spec + ("servers" to JsonArray(listOf(server))))
    }
}

/** `GET /api/v1/openapi.json`: public; the JSON text is built on the first request and kept. */
fun Route.openApiRoutes(publicBaseUrl: String?) {
    val json by lazy { OpenApi.document(publicBaseUrl).toString() }
    get("$API_PREFIX/openapi.json") { call.respondText(json, ContentType.Application.Json) }
}
