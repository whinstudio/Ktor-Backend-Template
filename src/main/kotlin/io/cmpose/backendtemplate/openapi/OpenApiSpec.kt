package io.cmpose.backendtemplate.openapi

import io.cmpose.backendtemplate.feature.sample.MAX_NOTE
import io.cmpose.backendtemplate.feature.sample.MAX_TITLE
import io.cmpose.backendtemplate.sharing.MAX_SPACE_NAME
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Conflict
import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.http.HttpStatusCode.Companion.NoContent
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.HttpStatusCode.Companion.PayloadTooLarge
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.http.HttpStatusCode.Companion.TooManyRequests
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import io.ktor.http.HttpStatusCode.Companion.UnsupportedMediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The API description (OpenAPI 3.0), built in code so no YAML parser ships. Keep it in step with the routes: paths
 * are relative to the `/api/v1` server, and `OpenApiTest` checks that every `$ref` resolves.
 */

private const val MAX_PAGE = 200
private const val DEFAULT_PAGE = 50

private fun ref(kind: String, name: String) = buildJsonObject { put("\$ref", "#/components/$kind/$name") }

private fun schema(name: String) = ref("schemas", name)

private fun json(schema: JsonElement) = buildJsonObject {
    putJsonObject("application/json") { put("schema", schema) }
}

private fun jsonBody(schema: JsonElement) = buildJsonObject {
    put("required", true)
    put("content", json(schema))
}

private fun arrayOf(items: JsonElement) = buildJsonObject {
    put("type", "array")
    put("items", items)
}

private fun string(format: String? = null, description: String? = null) = buildJsonObject {
    put("type", "string")
    format?.let { put("format", it) }
    description?.let { put("description", it) }
}

private fun uuid(description: String? = null) = string("uuid", description)

private fun int64(description: String? = null) = buildJsonObject {
    put("type", "integer")
    put("format", "int64")
    description?.let { put("description", it) }
}

private fun JsonObject.nullable() = JsonObject(this + ("nullable" to JsonPrimitive(true)))

/** An object schema: [required] names, [properties] from the block; unknown fields are refused when [closed]. */
private fun objectOf(
    required: List<String>,
    closed: Boolean = false,
    properties: JsonObjectBuilder.() -> Unit,
) = buildJsonObject {
    put("type", "object")
    if (closed) put("additionalProperties", false)
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach(::add) }
    putJsonObject("properties", properties)
}

/** One operation; [responses] maps each status to a description and schema ([returns]) or an error ([fails]). */
private fun operation(
    summary: String,
    requestBody: JsonObject? = null,
    parameters: JsonArray? = null,
    responses: JsonObjectBuilder.() -> Unit,
) = buildJsonObject {
    put("summary", summary)
    parameters?.let { put("parameters", it) }
    requestBody?.let { put("requestBody", it) }
    putJsonObject("responses", responses)
}

private fun JsonObjectBuilder.returns(status: HttpStatusCode, description: String, body: JsonElement? = null) {
    putJsonObject(status.value.toString()) {
        put("description", description)
        body?.let { put("content", json(it)) }
    }
}

/** The shared error responses for [statuses]: 400, 401 and 404 have their own; anything else is the plain one. */
private fun JsonObjectBuilder.fails(vararg statuses: HttpStatusCode) {
    statuses.forEach { status ->
        val name = when (status) {
            BadRequest -> "BadRequest"
            Unauthorized -> "Unauthorized"
            NotFound -> "NotFound"
            else -> "Error"
        }
        put(status.value.toString(), ref("responses", name))
    }
}

private fun pathParameters(vararg names: String) = buildJsonArray { names.forEach { add(ref("parameters", it)) } }

private fun JsonObjectBuilder.accountPaths() {
    putJsonObject("/me") {
        put("get", operation("The caller, created on the first valid token") {
            returns(OK, "The caller", schema("Me"))
            fails(Unauthorized)
        })
        put("delete", operation("Deletes the account and everything it owns, for good") {
            returns(NoContent, "Deleted (also when called again)")
            fails(Unauthorized, TooManyRequests)
        })
    }
}

private fun JsonObjectBuilder.filePaths() {
    val upload = objectOf(listOf("file")) {
        put("file", string("binary"))
        // cmpose:sharing
        put("spaceId", uuid("The space to upload to; the personal space when left out (EDITOR or above)"))
        // /cmpose:sharing
    }
    val form = buildJsonObject {
        put("required", true)
        putJsonObject("content") { putJsonObject("multipart/form-data") { put("schema", upload) } }
    }
    putJsonObject("/files") {
        put("post", operation("Uploads a JPEG, PNG, WebP or PDF file; the type is read from the bytes", form) {
            returns(Created, "Stored", schema("File"))
            fails(BadRequest, Unauthorized, PayloadTooLarge, UnsupportedMediaType, TooManyRequests, ServiceUnavailable)
            // cmpose:sharing
            fails(NotFound)
            // /cmpose:sharing
        })
    }
    putJsonObject("/files/{id}") {
        put("parameters", pathParameters("Id"))
        put("get", operation("Downloads a file (always as an attachment)") {
            putJsonObject(OK.value.toString()) {
                put("description", "The file's bytes")
                putJsonObject("content") {
                    putJsonObject("application/octet-stream") { put("schema", string("binary")) }
                }
            }
            fails(Unauthorized, NotFound)
        })
        put("delete", operation("Deletes a file") {
            returns(NoContent, "Deleted")
            fails(Unauthorized, NotFound)
        })
    }
}

private fun queryParameter(name: String, schema: JsonObject, description: String? = null) = buildJsonObject {
    put("name", name)
    put("in", "query")
    description?.let { put("description", it) }
    put("schema", schema)
}

private fun JsonObjectBuilder.itemPaths() {
    val listParameters = buildJsonArray {
        add(queryParameter("limit", buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", MAX_PAGE)
            put("default", DEFAULT_PAGE)
        }))
        add(queryParameter("cursor", string(), "The nextCursor of the previous page"))
        // cmpose:sharing
        add(queryParameter("spaceId", uuid(), "The space to list; else the personal space (VIEWER or above)"))
        // /cmpose:sharing
    }
    putJsonObject("/items") {
        put("get", operation("Lists items newest first, a page at a time", parameters = listParameters) {
            returns(OK, "One page", schema("ItemPage"))
            fails(BadRequest, Unauthorized)
            // cmpose:sharing
            fails(NotFound)
            // /cmpose:sharing
        })
        put("post", operation("Creates an item", jsonBody(schema("ItemDraft"))) {
            returns(Created, "Created", schema("Item"))
            fails(BadRequest, Unauthorized)
            // cmpose:sharing
            fails(NotFound)
            // /cmpose:sharing
        })
    }
    putJsonObject("/items/{id}") {
        put("parameters", pathParameters("Id"))
        put("get", operation("Reads an item") {
            returns(OK, "The item", schema("Item"))
            fails(Unauthorized, NotFound)
        })
        val patch = jsonBody(schema("ItemPatch"))
        put("patch", operation("Changes the fields the body names; null clears note or dueDate", patch) {
            returns(OK, "The changed item", schema("Item"))
            fails(BadRequest, Unauthorized, NotFound)
        })
        put("delete", operation("Deletes an item") {
            returns(NoContent, "Deleted")
            fails(Unauthorized, NotFound)
        })
    }
}

private fun JsonObjectBuilder.errorSchema() {
    val field = objectOf(listOf("field", "message")) {
        put("field", string())
        put("message", string())
    }
    val detail = objectOf(listOf("code", "message", "requestId")) {
        put("code", string(description = "A constant such as NOT_FOUND or BAD_REQUEST"))
        put("message", string())
        put("requestId", string())
        put("fields", arrayOf(field))
        put("minVersion", string(description = "Only on 426 CLIENT_UPGRADE_REQUIRED"))
    }
    put("Error", objectOf(listOf("error")) { put("error", detail) })
}

private fun JsonObjectBuilder.accountSchemas() {
    put("Me", objectOf(listOf("id")) {
        put("id", uuid())
        put("email", string(description = "The verified email, else null").nullable())
    })
    put("File", objectOf(listOf("id", "mime", "size")) {
        put("id", uuid())
        put("mime", string())
        put("size", int64())
    })
}

private fun title() = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAX_TITLE)
    put("description", "Trimmed before the length check")
}

private fun note() = buildJsonObject {
    put("type", "string")
    put("maxLength", MAX_NOTE)
    put("nullable", true)
}

private fun dueDate() = buildJsonObject {
    put("type", "integer")
    put("format", "int64")
    put("minimum", 0)
    put("nullable", true)
    put("description", "Epoch milliseconds")
}

private fun JsonObjectBuilder.itemSchemas() {
    val itemFields = buildList {
        add("id")
        // cmpose:sharing
        add("spaceId")
        // /cmpose:sharing
        addAll(listOf("title", "note", "dueDate", "createdAt", "updatedAt"))
    }
    put("Item", objectOf(itemFields) {
        put("id", uuid())
        // cmpose:sharing
        put("spaceId", uuid())
        // /cmpose:sharing
        put("title", string())
        put("note", string().nullable())
        put("dueDate", int64("Epoch milliseconds").nullable())
        put("createdAt", int64())
        put("updatedAt", int64())
    })
    put("ItemPage", objectOf(listOf("items", "nextCursor")) {
        put("items", arrayOf(schema("Item")))
        put("nextCursor", string(description = "Null on the last page").nullable())
    })
    put("ItemDraft", objectOf(listOf("title"), closed = true) {
        put("title", title())
        put("note", note())
        put("dueDate", dueDate())
        // cmpose:sharing
        put("spaceId", uuid("The space to create in; the personal space when left out (EDITOR or above)"))
        // /cmpose:sharing
    })
    put("ItemPatch", objectOf(emptyList(), closed = true) {
        put("title", title())
        put("note", note())
        put("dueDate", dueDate())
    })
}

// cmpose:sharing
private fun JsonObjectBuilder.sharingPaths() {
    putJsonObject("/spaces") {
        put("get", operation("The caller's spaces, with their role in each") {
            returns(OK, "The spaces", arrayOf(schema("Space")))
            fails(Unauthorized)
        })
        put("post", operation("Creates a space with the caller as its OWNER", jsonBody(schema("SpaceDraft"))) {
            returns(Created, "Created", schema("Space"))
            fails(BadRequest, Unauthorized)
        })
    }
    putJsonObject("/spaces/{id}") {
        put("parameters", pathParameters("Id"))
        put("patch", operation("Renames a space (OWNER)", jsonBody(schema("SpaceDraft"))) {
            returns(OK, "The renamed space", schema("Space"))
            fails(BadRequest, Unauthorized, NotFound)
        })
        put("delete", operation("Deletes a space with its members, invites, files and items (OWNER)") {
            returns(NoContent, "Deleted")
            fails(BadRequest, Unauthorized, NotFound)
        })
    }
    putJsonObject("/spaces/{id}/members") {
        put("parameters", pathParameters("Id"))
        put("get", operation("The members of a space (VIEWER or above)") {
            returns(OK, "The members", arrayOf(schema("Member")))
            fails(Unauthorized, NotFound)
        })
    }
    putJsonObject("/spaces/{id}/members/{userId}") {
        put("parameters", pathParameters("Id", "UserId"))
        put("patch", operation("Sets a member's role to editor or viewer (OWNER)", jsonBody(schema("RoleChange"))) {
            returns(NoContent, "Changed")
            fails(BadRequest, Unauthorized, NotFound, Conflict)
        })
        put("delete", operation("Removes a member (OWNER)") {
            returns(NoContent, "Removed")
            fails(Unauthorized, NotFound, Conflict)
        })
    }
    putJsonObject("/spaces/{id}/leave") {
        put("parameters", pathParameters("Id"))
        put("post", operation("The caller leaves the space; the last OWNER cannot") {
            returns(NoContent, "Left")
            fails(Unauthorized, NotFound, Conflict)
        })
    }
    putJsonObject("/spaces/{id}/invites") {
        put("parameters", pathParameters("Id"))
        put("post", operation("Creates a single-use invite link (OWNER)", jsonBody(schema("InviteRequest"))) {
            returns(Created, "The invite; its token is shown only here", schema("Invite"))
            fails(BadRequest, Unauthorized, NotFound, TooManyRequests)
        })
    }
    putJsonObject("/invites/preview") {
        put("post", operation("What an invite offers", jsonBody(schema("InviteToken"))) {
            returns(OK, "The offer", schema("InvitePreview"))
            fails(BadRequest, Unauthorized, NotFound, TooManyRequests)
        })
    }
    putJsonObject("/invites/accept") {
        put("post", operation("Joins the invite's space", jsonBody(schema("InviteToken"))) {
            returns(OK, "The joined space", schema("Space"))
            fails(BadRequest, Unauthorized, NotFound, TooManyRequests)
        })
    }
}

private fun JsonObjectBuilder.sharingSchemas() {
    put("Role", buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { listOf("viewer", "editor", "owner").forEach(::add) }
    })
    put("Space", objectOf(listOf("id", "name", "role", "personal", "memberCount")) {
        put("id", uuid())
        put("name", string())
        put("role", schema("Role"))
        put("personal", buildJsonObject { put("type", "boolean") })
        put("memberCount", buildJsonObject { put("type", "integer") })
    })
    put("SpaceDraft", objectOf(listOf("name"), closed = true) {
        put("name", buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", MAX_SPACE_NAME)
        })
    })
    put("Member", objectOf(listOf("userId", "email", "role", "joinedAt")) {
        put("userId", uuid())
        put("email", string(description = "The verified email, else null").nullable())
        put("role", schema("Role"))
        put("joinedAt", int64())
    })
    put("RoleChange", objectOf(listOf("role"), closed = true) { put("role", schema("Role")) })
    put("InviteRequest", objectOf(listOf("role"), closed = true) { put("role", schema("Role")) })
    put("Invite", objectOf(listOf("token", "expiresAt")) {
        put("token", string())
        put("expiresAt", int64())
    })
    put("InviteToken", objectOf(listOf("token"), closed = true) { put("token", string()) })
    put("InvitePreview", objectOf(listOf("spaceName", "role", "expiresAt")) {
        put("spaceName", string())
        put("role", schema("Role"))
        put("expiresAt", int64())
    })
}
// /cmpose:sharing

private fun JsonObjectBuilder.components() {
    putJsonObject("securitySchemes") {
        putJsonObject("bearer") {
            put("type", "http")
            put("scheme", "bearer")
            put("bearerFormat", "Firebase ID token")
        }
    }
    putJsonObject("parameters") {
        listOf("Id" to "id", "UserId" to "userId").forEach { (name, parameter) ->
            putJsonObject(name) {
                put("name", parameter)
                put("in", "path")
                put("required", true)
                put("schema", uuid())
            }
        }
    }
    putJsonObject("responses") {
        listOf(
            "Error" to "Error",
            "BadRequest" to "Invalid input; fields names each invalid field",
            "Unauthorized" to "No valid bearer token",
            "NotFound" to "Missing, or not the caller's (the two are not told apart)",
        ).forEach { (name, description) ->
            putJsonObject(name) {
                put("description", description)
                put("content", json(schema("Error")))
            }
        }
    }
    putJsonObject("schemas") {
        errorSchema()
        accountSchemas()
        itemSchemas()
        // cmpose:sharing
        sharingSchemas()
        // /cmpose:sharing
    }
}

/** The document without `servers`; [OpenApi] adds the one server. */
internal fun openApiSpec(): JsonObject = buildJsonObject {
    put("openapi", "3.0.3")
    putJsonObject("info") {
        put("title", "Cmpose Backend")
        put("version", "1")
        put("description", "Every error is {\"error\": {\"code\", \"message\", \"requestId\"}} (+ \"fields\").")
    }
    putJsonArray("security") { add(buildJsonObject { putJsonArray("bearer") {} }) }
    putJsonObject("paths") {
        accountPaths()
        filePaths()
        itemPaths()
        // cmpose:sharing
        sharingPaths()
        // /cmpose:sharing
    }
    putJsonObject("components") { components() }
}
