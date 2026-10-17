package io.cmpose.backendtemplate

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Default `json()` settings on purpose: a real client must not need special settings to read the server. */
fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
    install(ContentNegotiation) { json() }
}

/** A JSON [body] with [token] as the bearer token. */
inline fun <reified T> HttpRequestBuilder.jsonBody(token: String, body: T) {
    bearerAuth(token)
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** The `error.code` of an error response. */
suspend fun HttpResponse.errorCode(): String =
    Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

/** `GET /api/v1/me`, with [token] as the bearer token when given. */
suspend fun HttpClient.me(token: String? = null): HttpResponse =
    get("/api/v1/me") { if (token != null) bearerAuth(token) }

/** Starts like a PNG: what [io.cmpose.backendtemplate.files.ContentSniffer] looks at. */
val PNG_BYTES = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
    ByteArray(64) { it.toByte() }

/** `POST /api/v1/files` with [bytes] as the multipart `file` part, named [filename] and declared as [type]. */
// cmpose:sharing
/** With [spaceId] the form also carries a `spaceId` field. */
// /cmpose:sharing
suspend fun HttpClient.upload(
    token: String,
    bytes: ByteArray = PNG_BYTES,
    filename: String = "photo.png",
    type: String = "image/png",
    // cmpose:sharing
    spaceId: String? = null,
    // /cmpose:sharing
): HttpResponse = submitFormWithBinaryData(
    url = "/api/v1/files",
    formData = formData {
        // cmpose:sharing
        if (spaceId != null) append("spaceId", spaceId)
        // /cmpose:sharing
        append(
            "file",
            bytes,
            Headers.build {
                append(HttpHeaders.ContentType, type)
                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
            },
        )
    },
) { bearerAuth(token) }
