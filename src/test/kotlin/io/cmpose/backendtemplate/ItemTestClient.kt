package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.feature.sample.ItemDraft
import io.cmpose.backendtemplate.feature.sample.ItemPage
import io.cmpose.backendtemplate.feature.sample.ItemResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.assertEquals

suspend fun HttpClient.postItem(token: String, draft: ItemDraft): HttpResponse =
    post("/api/v1/items") { jsonBody(token, draft) }

/** Creates an item and checks the 201. */
suspend fun HttpClient.createItem(token: String, draft: ItemDraft = ItemDraft("Milk")): ItemResponse {
    val response = postItem(token, draft)
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

/** `GET /api/v1/items` with [query] (`limit=2&cursor=…`) appended as is. */
suspend fun HttpClient.listItems(token: String, query: String = ""): HttpResponse =
    get("/api/v1/items" + if (query.isEmpty()) "" else "?$query") { bearerAuth(token) }

suspend fun HttpClient.itemPage(token: String, query: String = ""): ItemPage {
    val response = listItems(token, query)
    assertEquals(HttpStatusCode.OK, response.status)
    return response.body()
}

suspend fun HttpClient.getItem(token: String, id: String): HttpResponse =
    get("/api/v1/items/$id") { bearerAuth(token) }

/** `PATCH /api/v1/items/{id}` with [json] sent verbatim, so a test can send `null`s and unknown fields. */
suspend fun HttpClient.patchItem(token: String, id: String, json: String): HttpResponse =
    patch("/api/v1/items/$id") {
        bearerAuth(token)
        setBody(TextContent(json, ContentType.Application.Json))
    }

/** `POST /api/v1/items` with [json] sent verbatim. */
suspend fun HttpClient.postItemJson(token: String, json: String): HttpResponse =
    post("/api/v1/items") {
        bearerAuth(token)
        setBody(TextContent(json, ContentType.Application.Json))
    }

suspend fun HttpClient.deleteItem(token: String, id: String): HttpResponse =
    delete("/api/v1/items/$id") { bearerAuth(token) }
