@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.files.FileStore
import io.cmpose.backendtemplate.files.removeFiles
import io.cmpose.backendtemplate.plugins.FieldError
import io.cmpose.backendtemplate.plugins.badRequest
import io.cmpose.backendtemplate.plugins.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** The body of a space creation or rename. */
@Serializable
data class SpaceDraft(val name: String)

/** [name] trimmed, or 400 when it is blank or longer than [MAX_SPACE_NAME]. */
internal fun spaceName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_SPACE_NAME) {
        badRequest("Invalid name", listOf(FieldError("name", "must be 1 to $MAX_SPACE_NAME characters")))
    }
    return trimmed
}

/**
 * List, create, rename and delete spaces. Any signed-in user lists theirs and creates one; only an OWNER renames or
 * deletes it. A personal space cannot be deleted. Deleting a space takes its members, invites and files with it.
 */
fun Route.spaceRoutes(spaces: SpaceRepository, store: FileStore) {
    get("/api/v1/spaces") {
        call.respond(spaces.listFor(call.requireUser()))
    }
    post("/api/v1/spaces") {
        val user = call.requireUser()
        val name = spaceName(call.receive<SpaceDraft>().name)
        call.respond(HttpStatusCode.Created, spaces.create(user, name))
    }
    patch("/api/v1/spaces/{id}") {
        val membership = call.requireRole(spaces, call.parameters["id"].orEmpty(), Role.OWNER)
        val name = spaceName(call.receive<SpaceDraft>().name)
        spaces.rename(membership.spaceId, name)
        call.respond(spaces.get(membership.spaceId, membership.userId) ?: notFound())
    }
    delete("/api/v1/spaces/{id}") {
        val membership = call.requireRole(spaces, call.parameters["id"].orEmpty(), Role.OWNER)
        if (spaces.isPersonal(membership.spaceId)) badRequest("A personal space cannot be deleted")
        call.removeFiles(store, spaces.delete(membership.spaceId))
        call.respond(HttpStatusCode.NoContent)
    }
}
