package io.cmpose.backendtemplate.openapi

import io.cmpose.backendtemplate.feature.sample.itemPaths
import io.cmpose.backendtemplate.feature.sample.itemSchemas
import io.cmpose.backendtemplate.sharing.MAX_SPACE_NAME
import io.cmpose.backendtemplate.users.MAX_DISPLAY_NAME
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Conflict
import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.http.HttpStatusCode.Companion.Gone
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

private fun ref(kind: String, name: String) = buildJsonObject { put("\$ref", "#/components/$kind/$name") }

internal fun schema(name: String) = ref("schemas", name)

private fun json(schema: JsonElement) = buildJsonObject {
    putJsonObject("application/json") { put("schema", schema) }
}

internal fun jsonBody(schema: JsonElement) = buildJsonObject {
    put("required", true)
    put("content", json(schema))
}

internal fun arrayOf(items: JsonElement) = buildJsonObject {
    put("type", "array")
    put("items", items)
}

internal fun string(format: String? = null, description: String? = null) = buildJsonObject {
    put("type", "string")
    format?.let { put("format", it) }
    description?.let { put("description", it) }
}

internal fun uuid(description: String? = null) = string("uuid", description)

internal fun int64(description: String? = null) = buildJsonObject {
    put("type", "integer")
    put("format", "int64")
    description?.let { put("description", it) }
}

internal fun JsonObject.nullable() = JsonObject(this + ("nullable" to JsonPrimitive(true)))

/** An object schema: [required] names, [properties] from the block; unknown fields are refused when [closed]. */
internal fun objectOf(
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
internal fun operation(
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

internal fun JsonObjectBuilder.returns(status: HttpStatusCode, description: String, body: JsonElement? = null) {
    putJsonObject(status.value.toString()) {
        put("description", description)
        body?.let { put("content", json(it)) }
    }
}

/** The shared error responses for [statuses]: 400, 401 and 404 have their own; anything else is the plain one. */
internal fun JsonObjectBuilder.fails(vararg statuses: HttpStatusCode) {
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

internal fun pathParameters(vararg names: String) = buildJsonArray { names.forEach { add(ref("parameters", it)) } }

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
    putJsonObject("/me/profile") {
        put("put", operation("Sets the caller's profile", jsonBody(schema("ProfileUpdate"))) {
            returns(NoContent, "Saved")
            fails(BadRequest, Unauthorized)
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
        // cmpose:sharing-off: put("quota", string(description = "Only on 409 QUOTA_EXCEEDED: storage"))
        put("quota", string(description = "Only on 409 QUOTA_EXCEEDED: storage, spaces, members or invites"))
    }
    put("Error", objectOf(listOf("error")) { put("error", detail) })
}

private fun JsonObjectBuilder.accountSchemas() {
    put("Me", objectOf(listOf("id", "email", "displayName")) {
        put("id", uuid())
        put("email", string(description = "The verified email, else null").nullable())
        put("displayName", string(description = "The name the user chose to show, else null").nullable())
    })
    put("ProfileUpdate", objectOf(emptyList(), closed = true) {
        put("displayName", buildJsonObject {
            put("type", "string")
            put("maxLength", MAX_DISPLAY_NAME)
            put("description", "Trimmed; null or blank clears the display name")
        }.nullable())
        // cmpose:sharing
        put("personalSpaceName", buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", MAX_SPACE_NAME)
            put("description", "Trimmed; renames the personal space, null leaves it as it is")
        }.nullable())
        // /cmpose:sharing
    })
    put("File", objectOf(listOf("id", "mime", "size")) {
        put("id", uuid())
        put("mime", string())
        put("size", int64())
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
        // cmpose:sample-off: put("delete", operation("Deletes a space with its members, invites and files (OWNER)") {
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
        put("get", operation("The space's valid invites, newest first, at most 200; never their tokens (OWNER)") {
            returns(OK, "The pending invites", arrayOf(schema("PendingInvite")))
            fails(Unauthorized, NotFound, TooManyRequests)
        })
        put("post", operation("Creates a single-use invite link (OWNER)", jsonBody(schema("InviteRequest"))) {
            returns(Created, "The invite; its token is shown only here", schema("Invite"))
            fails(BadRequest, Unauthorized, NotFound, TooManyRequests)
        })
    }
    putJsonObject("/spaces/{id}/invites/{inviteId}") {
        put("parameters", pathParameters("Id", "InviteId"))
        put("delete", operation("Revokes an invite (OWNER); a used one is 409 INVITE_USED") {
            returns(NoContent, "Revoked (also when called again, or when it had expired)")
            fails(Unauthorized, NotFound, Conflict, TooManyRequests)
        })
    }
    putJsonObject("/invites/preview") {
        put("post", operation("What an invite offers", jsonBody(schema("InviteToken"))) {
            returns(OK, "The offer", schema("InvitePreview"))
            fails(BadRequest, Unauthorized, NotFound, TooManyRequests)
        })
    }
    putJsonObject("/invites/accept") {
        val summary = "Joins the invite's space; a used invite is 409 INVITE_USED, a revoked one 410 INVITE_REVOKED, " +
            "an expired one 410 INVITE_EXPIRED"
        put("post", operation(summary, jsonBody(schema("InviteToken"))) {
            returns(OK, "The joined space", schema("Space"))
            fails(BadRequest, Unauthorized, NotFound, Conflict, Gone, TooManyRequests)
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
    put("Member", objectOf(listOf("userId", "displayName", "email", "role", "joinedAt")) {
        put("userId", uuid())
        put("displayName", string(description = "The name the member chose to show, else null").nullable())
        put("email", string(description = "The verified email, else null").nullable())
        put("role", schema("Role"))
        put("joinedAt", int64())
    })
    put("RoleChange", objectOf(listOf("role"), closed = true) { put("role", schema("Role")) })
    put("InviteRequest", objectOf(listOf("role"), closed = true) { put("role", schema("Role")) })
    put("Invite", objectOf(listOf("id", "token", "expiresAt")) {
        put("id", uuid())
        put("token", string())
        put("expiresAt", int64())
    })
    put("PendingInvite", objectOf(listOf("id", "role", "createdAt", "expiresAt")) {
        put("id", uuid())
        put("role", schema("Role"))
        put("createdAt", int64())
        put("expiresAt", int64())
    })
    put("InviteToken", objectOf(listOf("token"), closed = true) { put("token", string()) })
    put("InviteState", buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { listOf("valid", "used", "expired", "revoked").forEach(::add) }
    })
    put("InvitePreview", objectOf(listOf("spaceName", "inviterName", "role", "expiresAt", "state")) {
        put("spaceName", string())
        put("inviterName", string(description = "The inviter's display name, else null").nullable())
        put("role", schema("Role"))
        put("expiresAt", int64())
        put("state", schema("InviteState"))
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
        // cmpose:sharing-off: listOf("Id" to "id", "UserId" to "userId").forEach { (name, parameter) ->
        listOf("Id" to "id", "UserId" to "userId", "InviteId" to "inviteId").forEach { (name, parameter) ->
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
        // cmpose:sample
        itemSchemas()
        // /cmpose:sample
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
        // cmpose:sample
        itemPaths()
        // /cmpose:sample
        // cmpose:sharing
        sharingPaths()
        // /cmpose:sharing
    }
    putJsonObject("components") { components() }
}
