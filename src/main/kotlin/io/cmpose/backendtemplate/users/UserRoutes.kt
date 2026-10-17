@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.users

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.files.FileRepository
import io.cmpose.backendtemplate.files.FileStore
import io.cmpose.backendtemplate.files.removeFiles
import io.cmpose.backendtemplate.plugins.SENSITIVE_LIMIT
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** The caller as this server knows them: our own [id], never the provider's subject, and a verified [email]. */
@Serializable
data class MeResponse(val id: String, val email: String?)

fun Route.userRoutes(
    users: UserRepository,
    files: FileRepository,
    store: FileStore,
    // cmpose:sharing
    spaces: SpaceRepository,
    // /cmpose:sharing
) {
    get("/api/v1/me") {
        val user = call.requireUser()
        call.respond(MeResponse(user.toString(), users.email(user)))
    }
    /*
     * Account deletion (App Store rule 5.1.1(v)) is permanent: the user row goes, and ON DELETE CASCADE takes the
     * identity mappings and every row that points at the user; then the user's files leave the disk. The client
     * deletes the Firebase user afterwards; until then the same token creates a new, empty user. Calling it again is
     * still a 204.
     */
    // cmpose:sharing
    // The spaces the user owns go with everything in them; in other people's spaces only their membership goes.
    // /cmpose:sharing
    rateLimit(SENSITIVE_LIMIT) {
        delete("/api/v1/me") {
            val user = call.requireUser()
            // cmpose:sharing-off: val paths = users.delete(user) { files.pathsOf(user) }
            val paths = users.delete(user) { files.pathsIn(spaces.ownedBy(user)) }
            // The rows are gone; a file left on disk is only logged, so the deletion still succeeds.
            call.removeFiles(store, paths)
            // Audit trail: the request log line carries the (now deleted) user id.
            call.application.log.info("Account deleted")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
