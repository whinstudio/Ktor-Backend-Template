@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.feature.sample

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.plugins.FieldError
import io.cmpose.backendtemplate.plugins.badRequest
import io.cmpose.backendtemplate.plugins.notFound
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.cmpose.backendtemplate.sharing.requireRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

/** How many items `GET /api/v1/items/recent` returns at most; the API description states it too. */
internal const val RECENT_LIMIT = 20

/** The `{id}` path parameter as an item id; anything but a UUID names no item (404). */
private fun ApplicationCall.itemId(): UUID =
    runCatching { UUID.fromString(parameters["id"].orEmpty()) }.getOrNull() ?: notFound()

/** `limit` and `cursor` from the query; a bad one of either is a 400 naming it. */
private fun ApplicationCall.page(): Pair<Int, ItemCursor?> {
    val limitText = request.queryParameters["limit"]
    val limit = if (limitText == null) DEFAULT_LIMIT else limitText.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
    val cursorText = request.queryParameters["cursor"]
    val cursor = cursorText?.let(ItemCursor::decode)
    val errors = listOfNotNull(
        FieldError("limit", "must be a number from 1 to $MAX_LIMIT").takeIf { limit == null },
        FieldError("cursor", "not a cursor from this server").takeIf { cursorText != null && cursor == null },
    )
    if (limit == null || errors.isNotEmpty()) badRequest("Invalid query", errors)
    return limit to cursor
}

private fun invalid(errors: List<FieldError>) {
    if (errors.isNotEmpty()) badRequest("Invalid item", errors)
}

/** [current] with the fields of [patch] that the body named in [present]; a `null` title is reported as missing. */
private fun patched(current: Item, patch: ItemPatch, present: Set<String>, now: Long) = current.copy(
    title = if ("title" in present) patch.title?.trim().orEmpty() else current.title,
    note = if ("note" in present) patch.note else current.note,
    dueDate = if ("dueDate" in present) patch.dueDate else current.dueDate,
    updatedAt = now,
)

// cmpose:sharing
/** The item named by `{id}` if the caller's role in its space is at least [atLeast]; otherwise 404. */
private suspend fun ApplicationCall.requireItem(items: ItemRepository, spaces: SpaceRepository, atLeast: Role) =
    (items.get(itemId()) ?: notFound()).also { requireRole(spaces, it.spaceId.toString(), atLeast) }
// /cmpose:sharing

/**
 * The sample feature: CRUD on `/api/v1/items`, the pattern to copy for a new record type. Every handler calls
 * `requireUser()` first; an item the caller may not see is a 404, the same as a missing one. The list pages newest
 * first with `limit` (1..200, default 50) and an opaque `cursor`; `/recent` is the newest [RECENT_LIMIT] in one array.
 */
fun Route.itemRoutes(
    items: ItemRepository,
    // cmpose:sharing
    spaces: SpaceRepository,
    // /cmpose:sharing
) {
    // cmpose:sharing-off: // Each item belongs to the user who created it; every query filters on the caller.
    // Items live in a space (the `spaceId` query or field, else the personal space): VIEWER reads, EDITOR writes.
    route("/api/v1/items") {
        // Two list shapes to copy: a list that can grow answers a page at a time (`limit`, `cursor`, `nextCursor`);
        // a small list with a fixed upper bound answers in one plain array (`/recent`). Never an unbounded list.
        get {
            val user = call.requireUser()
            val (limit, after) = call.page()
            // cmpose:sharing
            val spaceId = call.request.queryParameters["spaceId"] ?: spaces.personalSpace(user).toString()
            val membership = call.requireRole(spaces, spaceId, Role.VIEWER)
            // /cmpose:sharing
            // One extra row tells whether another page follows.
            // cmpose:sharing-off: val rows = items.list(user, limit + 1, after)
            val rows = items.list(membership.spaceId, limit + 1, after)
            val page = rows.take(limit)
            val next = if (rows.size > limit) page.last().cursor().encode() else null
            call.respond(ItemPage(page.map { it.toResponse() }, next))
        }
        // A fixed path segment wins over `/{id}`, so `recent` is never read as an item id.
        get("/recent") {
            val user = call.requireUser()
            // cmpose:sharing
            val spaceId = call.request.queryParameters["spaceId"] ?: spaces.personalSpace(user).toString()
            val membership = call.requireRole(spaces, spaceId, Role.VIEWER)
            // /cmpose:sharing
            // cmpose:sharing-off: val recent = items.list(user, RECENT_LIMIT, after = null)
            val recent = items.list(membership.spaceId, RECENT_LIMIT, after = null)
            call.respond(recent.map { it.toResponse() })
        }
        post {
            val user = call.requireUser()
            val draft = call.receive<ItemDraft>()
            val title = draft.title.trim()
            invalid(itemErrors(title, draft.note, draft.dueDate))
            // cmpose:sharing
            val spaceId = draft.spaceId ?: spaces.personalSpace(user).toString()
            val membership = call.requireRole(spaces, spaceId, Role.EDITOR)
            // /cmpose:sharing
            val now = System.currentTimeMillis()
            // cmpose:sharing-off: val item = Item(UUID.randomUUID(), title, draft.note, draft.dueDate, now, now)
            val item = Item(UUID.randomUUID(), membership.spaceId, title, draft.note, draft.dueDate, now, now)
            // cmpose:sharing-off: items.add(item, user)
            items.add(item)
            call.respond(HttpStatusCode.Created, item.toResponse())
        }
        get("/{id}") {
            // cmpose:sharing-off: val user = call.requireUser()
            call.requireUser()
            // cmpose:sharing-off: val item = items.get(call.itemId(), user) ?: notFound()
            val item = call.requireItem(items, spaces, Role.VIEWER)
            call.respond(item.toResponse())
        }
        patch("/{id}") {
            // cmpose:sharing-off: val user = call.requireUser()
            call.requireUser()
            // Read as an object first: which fields the body names decides what changes (a `null` note clears it).
            val body = call.receive<JsonObject>()
            val patch = Json.decodeFromJsonElement(ItemPatch.serializer(), body)
            // cmpose:sharing-off: val current = items.get(call.itemId(), user) ?: notFound()
            val current = call.requireItem(items, spaces, Role.EDITOR)
            val item = patched(current, patch, body.keys, System.currentTimeMillis())
            invalid(itemErrors(item.title, item.note, item.dueDate))
            // cmpose:sharing-off: if (!items.update(item, user)) notFound()
            if (!items.update(item)) notFound()
            call.respond(item.toResponse())
        }
        delete("/{id}") {
            // cmpose:sharing-off: val user = call.requireUser()
            call.requireUser()
            // cmpose:sharing-off: if (!items.delete(call.itemId(), user)) notFound()
            if (!items.delete(call.requireItem(items, spaces, Role.EDITOR).id)) notFound()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
