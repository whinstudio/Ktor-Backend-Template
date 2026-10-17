@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.plugins.notFound
import io.cmpose.backendtemplate.users.UserId
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/** A member's role in a space; each role may do everything the ones before it may. On the wire in lower case. */
@Serializable
enum class Role {
    @SerialName("viewer")
    VIEWER,

    @SerialName("editor")
    EDITOR,

    @SerialName("owner")
    OWNER,
}

/** True when this role may do what [other] may. */
fun Role.atLeast(other: Role): Boolean = ordinal >= other.ordinal

/** The value stored in a `role` column; the schema's CHECK constraint allows nothing else. */
internal fun Role.wire(): String = name.lowercase(Locale.ROOT)

internal fun roleOf(wire: String): Role = Role.valueOf(wire.uppercase(Locale.ROOT))

data class Membership(val spaceId: UUID, val userId: UserId, val role: Role)

/** [id] as a UUID, or null when it is not one (a path id that is no UUID answers 404, not 500). */
internal fun uuidOrNull(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

/**
 * The caller's membership in [spaceId] if their role is at least [atLeast]; otherwise 404, never 403: a space the
 * caller may not use looks the same as one that does not exist. Without a valid token it is the usual 401.
 */
suspend fun ApplicationCall.requireRole(spaces: SpaceRepository, spaceId: String, atLeast: Role): Membership {
    val user = requireUser()
    val id = uuidOrNull(spaceId) ?: notFound()
    val membership = spaces.membership(id, user) ?: notFound()
    if (!membership.role.atLeast(atLeast)) notFound()
    return membership
}
