@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.plugins.SENSITIVE_LIMIT
import io.cmpose.backendtemplate.plugins.badRequest
import io.cmpose.backendtemplate.plugins.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** The body of an invite creation: the role the invited user gets (`editor` or `viewer`). */
@Serializable
data class InviteRequest(val role: Role)

/** The body of an invite preview or accept; the token travels in the body, never in the path or the query. */
@Serializable
data class InviteToken(val token: String)

/**
 * An OWNER creates single-use invite links; any signed-in user previews and accepts one. An unknown, used or expired
 * token is a 404 alike. Every invite route counts against [SENSITIVE_LIMIT]: tokens cannot be guessed at speed, nor
 * invites minted in bulk.
 */
fun Route.inviteRoutes(spaces: SpaceRepository, invites: InviteRepository) {
    rateLimit(SENSITIVE_LIMIT) {
        post("/api/v1/spaces/{id}/invites") {
            val membership = call.requireRole(spaces, call.parameters["id"].orEmpty(), Role.OWNER)
            val role = call.receive<InviteRequest>().role
            if (role == Role.OWNER) badRequest("The owner role cannot be granted")
            call.respond(HttpStatusCode.Created, invites.create(membership.spaceId, membership.userId, role))
        }
        post("/api/v1/invites/preview") {
            call.requireUser()
            call.respond(invites.preview(call.receive<InviteToken>().token) ?: notFound())
        }
        post("/api/v1/invites/accept") {
            val user = call.requireUser()
            val spaceId = invites.accept(call.receive<InviteToken>().token, user, spaces) ?: notFound()
            call.respond(spaces.get(spaceId, user) ?: notFound())
        }
    }
}
