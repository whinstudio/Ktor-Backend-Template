@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.plugins.ApiException
import io.cmpose.backendtemplate.plugins.badRequest
import io.cmpose.backendtemplate.plugins.notFound
import io.cmpose.backendtemplate.users.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** The body of a role change. */
@Serializable
data class RoleChange(val role: Role)

/** The `{userId}` path parameter; anything but a UUID names no member (404). */
private fun ApplicationCall.targetUser(): UserId = UserId(uuidOrNull(parameters["userId"].orEmpty()) ?: notFound())

/** Answers a refused or applied [MemberChange]; the last OWNER is a 409 `LAST_OWNER`. */
private suspend fun ApplicationCall.respondChange(change: MemberChange) = when (change) {
    MemberChange.DONE -> respond(HttpStatusCode.NoContent)
    MemberChange.NOT_MEMBER -> notFound()
    MemberChange.LAST_OWNER ->
        throw ApiException(HttpStatusCode.Conflict, "LAST_OWNER", "A space needs at least one owner")
}

/**
 * Members of a space: any member lists them and may leave; an OWNER changes roles and removes members. The OWNER role
 * is never granted, and the last OWNER can neither leave, be removed nor lose the role (409 `LAST_OWNER`). Anyone
 * whose role is too low gets 404, the same as a non-member.
 */
fun Route.memberRoutes(spaces: SpaceRepository) {
    get("/api/v1/spaces/{id}/members") {
        val membership = call.requireRole(spaces, call.parameters["id"].orEmpty(), Role.VIEWER)
        call.respond(spaces.members(membership.spaceId))
    }
    patch("/api/v1/spaces/{id}/members/{userId}") {
        val membership = call.requireRole(spaces, call.parameters["id"].orEmpty(), Role.OWNER)
        val target = call.targetUser()
        val role = call.receive<RoleChange>().role
        if (role == Role.OWNER) badRequest("The owner role cannot be granted")
        call.respondChange(spaces.setRole(membership.spaceId, target, role))
    }
    delete("/api/v1/spaces/{id}/members/{userId}") {
        val membership = call.requireRole(spaces, call.parameters["id"].orEmpty(), Role.OWNER)
        call.respondChange(spaces.removeMember(membership.spaceId, call.targetUser()))
    }
    post("/api/v1/spaces/{id}/leave") {
        val membership = call.requireRole(spaces, call.parameters["id"].orEmpty(), Role.VIEWER)
        call.respondChange(spaces.removeMember(membership.spaceId, membership.userId))
    }
}
