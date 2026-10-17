package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.days

private const val TOKEN_BYTES = 32

// What an invite token looks like: TOKEN_BYTES bytes in URL-safe base64 without padding.
private val TOKEN_FORMAT = Regex("^[A-Za-z0-9_-]{43}$")

// Mirrors db/migration/V2__sharing.sql.
object Invites : Table("invites") {
    val id = uuid("id")
    val spaceId = uuid("space_id").references(Spaces.id, onDelete = ReferenceOption.CASCADE)
    val tokenHash = text("token_hash")
    val role = text("role")
    val createdBy = uuid("created_by").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val usedBy = uuid("used_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val usedAt = long("used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** A new invite: the [token] is shown once and never stored; [expiresAt] is epoch milliseconds. */
@Serializable
data class InviteResponse(val token: String, val expiresAt: Long)

/** What a usable invite offers. */
@Serializable
data class InvitePreview(val spaceName: String, val role: Role, val expiresAt: Long)

/**
 * Single-use invite links that expire [ttlDays] after creation. The token (32 random bytes, URL-safe base64) is
 * handed out once; only its SHA-256 hex digest is stored, and every lookup goes through that digest. A used, expired
 * or unknown token is the same to callers: null.
 */
class InviteRepository(
    private val db: Database,
    ttlDays: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val ttlMillis = ttlDays.days.inWholeMilliseconds

    /** A new invite to [spaceId] with [role] (EDITOR or VIEWER; the schema refuses OWNER). */
    fun create(spaceId: UUID, createdBy: UserId, role: Role): InviteResponse {
        require(role != Role.OWNER) { "Owner cannot be invited" }
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val createdAt = now()
        val expiresAt = createdAt + ttlMillis
        transaction(db) {
            Invites.insert {
                it[id] = UUID.randomUUID()
                it[Invites.spaceId] = spaceId
                it[tokenHash] = hash(token)
                it[Invites.role] = role.wire()
                it[Invites.createdBy] = createdBy.value
                it[Invites.createdAt] = createdAt
                it[Invites.expiresAt] = expiresAt
            }
        }
        return InviteResponse(token, expiresAt)
    }

    /** What a usable invite offers; null when [token] is unknown, used or expired. */
    fun preview(token: String): InvitePreview? = transaction(db) {
        if (!TOKEN_FORMAT.matches(token)) return@transaction null
        Invites.join(Spaces, JoinType.INNER, Invites.spaceId, Spaces.id)
            .selectAll()
            .where { Invites.tokenHash eq hash(token) }
            .singleOrNull()
            ?.takeIf { it.isUsable() }
            ?.let { InvitePreview(it[Spaces.name], roleOf(it[Invites.role]), it[Invites.expiresAt]) }
    }

    /**
     * Joins [user] to the invite's space and returns its id; null when [token] is unknown, used or expired. One
     * transaction with the invite row locked (`FOR UPDATE`), so two concurrent accepts cannot both use it. A caller who
     * already is a member keeps their role and the invite stays unused.
     */
    fun accept(token: String, user: UserId, spaces: SpaceRepository): UUID? = transaction(db) {
        if (!TOKEN_FORMAT.matches(token)) return@transaction null
        val row = Invites.selectAll().where { Invites.tokenHash eq hash(token) }.forUpdate().singleOrNull()
            ?.takeIf { it.isUsable() }
            ?: return@transaction null
        val spaceId = row[Invites.spaceId]
        if (spaces.membership(spaceId, user) == null) {
            val at = now()
            spaces.addMember(spaceId, user, roleOf(row[Invites.role]), at)
            Invites.update({ (Invites.id eq row[Invites.id]) and Invites.usedAt.isNull() }) {
                it[usedBy] = user.value
                it[usedAt] = at
            }
        }
        spaceId
    }

    // used_at, not used_by: used_by is cleared when the user who used it deletes their account.
    private fun ResultRow.isUsable() = this[Invites.usedAt] == null && this[Invites.expiresAt] > now()

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
