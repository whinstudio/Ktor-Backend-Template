@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.users

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.files.FileRepository
import io.cmpose.backendtemplate.files.FileStore
import io.cmpose.backendtemplate.files.removeFiles
import io.cmpose.backendtemplate.plugins.FieldError
import io.cmpose.backendtemplate.plugins.SENSITIVE_LIMIT
import io.cmpose.backendtemplate.plugins.badRequest
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.cmpose.backendtemplate.sharing.spaceName
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/**
 * The caller as this server knows them: our own [id], never the provider's subject, a verified [email] and the
 * [displayName] they chose (null until they set one).
 */
@Serializable
data class MeResponse(val id: String, val email: String?, val displayName: String?)

/** The body of `PUT /api/v1/me/profile`. It replaces the display name: null or blank clears it. */
@Serializable
data class ProfileUpdate(
    val displayName: String? = null,
    // cmpose:sharing
    /** Renames the caller's personal space; null leaves it as it is. */
    val personalSpaceName: String? = null,
    // /cmpose:sharing
)

/** [name] trimmed, null when it is null or blank, or 400 when it is longer than [MAX_DISPLAY_NAME]. */
internal fun displayName(name: String?): String? {
    val trimmed = name?.trim()?.ifEmpty { null } ?: return null
    if (trimmed.length > MAX_DISPLAY_NAME) {
        badRequest(
            "Invalid display name",
            listOf(FieldError("displayName", "must be at most $MAX_DISPLAY_NAME characters")),
        )
    }
    return trimmed
}

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
        call.respond(MeResponse(user.toString(), users.email(user), users.displayName(user)))
    }
    // Every field is checked before anything is written, so a 400 changes nothing.
    put("/api/v1/me/profile") {
        val user = call.requireUser()
        val update = call.receive<ProfileUpdate>()
        val name = displayName(update.displayName)
        // cmpose:sharing
        val personalName = update.personalSpaceName?.let { spaceName(it, field = "personalSpaceName") }
        // Both writes in one transaction: when the rename fails, the display name stays as it was.
        // /cmpose:sharing
        // cmpose:sharing-off: users.setDisplayName(user, name)
        users.setDisplayName(user, name) { personalName?.let { spaces.rename(spaces.personalSpace(user), it) } }
        call.respond(HttpStatusCode.NoContent)
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
