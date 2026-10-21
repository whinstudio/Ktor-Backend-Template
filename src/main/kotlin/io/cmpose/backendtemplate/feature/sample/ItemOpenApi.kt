package io.cmpose.backendtemplate.feature.sample

import io.cmpose.backendtemplate.openapi.arrayOf
import io.cmpose.backendtemplate.openapi.fails
import io.cmpose.backendtemplate.openapi.int64
import io.cmpose.backendtemplate.openapi.jsonBody
import io.cmpose.backendtemplate.openapi.nullable
import io.cmpose.backendtemplate.openapi.objectOf
import io.cmpose.backendtemplate.openapi.operation
import io.cmpose.backendtemplate.openapi.pathParameters
import io.cmpose.backendtemplate.openapi.returns
import io.cmpose.backendtemplate.openapi.schema
import io.cmpose.backendtemplate.openapi.string
import io.cmpose.backendtemplate.openapi.uuid
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Created
import io.ktor.http.HttpStatusCode.Companion.NoContent
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.http.HttpStatusCode.Companion.Unauthorized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/* The sample feature's part of the API description (`openapi/OpenApiSpec.kt` puts it in the document). */

private const val MAX_PAGE = 200
private const val DEFAULT_PAGE = 50

private fun queryParameter(name: String, schema: JsonObject, description: String? = null) = buildJsonObject {
    put("name", name)
    put("in", "query")
    description?.let { put("description", it) }
    put("schema", schema)
}

internal fun JsonObjectBuilder.itemPaths() {
    // cmpose:sharing
    val space = queryParameter("spaceId", uuid(), "The space to list; else the personal space (VIEWER or above)")
    // /cmpose:sharing
    val listParameters = buildJsonArray {
        add(queryParameter("limit", buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", MAX_PAGE)
            put("default", DEFAULT_PAGE)
        }))
        add(queryParameter("cursor", string(), "The nextCursor of the previous page"))
        // cmpose:sharing
        add(space)
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
    putJsonObject("/items/recent") {
        // cmpose:sharing
        put("parameters", buildJsonArray { add(space) })
        // /cmpose:sharing
        val recent = buildJsonObject {
            put("type", "array")
            put("maxItems", RECENT_LIMIT)
            put("items", schema("Item"))
        }
        put("get", operation("Lists the newest items, at most $RECENT_LIMIT, in one response (not paged)") {
            returns(OK, "The newest items, newest first", recent)
            fails(Unauthorized)
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

internal fun JsonObjectBuilder.itemSchemas() {
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
