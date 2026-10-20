package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.Users
import kotlinx.serialization.SerialName
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

/** Whether an invite can still be accepted: once used it stays [USED], even after it expires. */
@Serializable
enum class InviteState {
    @SerialName("valid")
    VALID,

    @SerialName("used")
    USED,

    @SerialName("expired")
    EXPIRED,
}

/** What an invite offers, and from whom: [inviterName] is its creator's display name, null when they have none. */
@Serializable
data class InvitePreview(
    val spaceName: String,
    val inviterName: String?,
    val role: Role,
    val expiresAt: Long,
    val state: InviteState,
)

/** How an accept ended: the caller is in the space, or the invite was already used or has expired. */
sealed interface AcceptResult {
    data class Joined(val spaceId: UUID) : AcceptResult

    data object Used : AcceptResult

    data object Expired : AcceptResult
}

/**
 * Single-use invite links that expire [ttlDays] after creation. The token (32 random bytes, URL-safe base64) is
 * handed out once; only its SHA-256 hex digest is stored, and every lookup goes through that digest. An unknown or
 * malformed token is null to callers; a known one reports its [InviteState].
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

    /** What the invite offers and its state, used and expired ones too; null when [token] is unknown or malformed. */
    fun preview(token: String): InvitePreview? = transaction(db) {
        if (!TOKEN_FORMAT.matches(token)) return@transaction null
        Invites.join(Spaces, JoinType.INNER, Invites.spaceId, Spaces.id)
            .join(Users, JoinType.INNER, Invites.createdBy, Users.id)
            .selectAll()
            .where { Invites.tokenHash eq hash(token) }
            .singleOrNull()
            ?.let {
                InvitePreview(
                    spaceName = it[Spaces.name],
                    inviterName = it[Users.displayName],
                    role = roleOf(it[Invites.role]),
                    expiresAt = it[Invites.expiresAt],
                    state = it.state(),
                )
            }
    }

    /**
     * Joins [user] to the invite's space; null when [token] is unknown or malformed, [AcceptResult.Used] or
     * [AcceptResult.Expired] when the invite cannot be accepted any more. One transaction with the invite row locked
     * (`FOR UPDATE`), so two concurrent accepts cannot both use it. A caller who already is a member gets the space
     * whatever the invite's state (someone reopening their own used link), keeps their role and leaves the invite as
     * it was.
     */
    fun accept(token: String, user: UserId, spaces: SpaceRepository): AcceptResult? = transaction(db) {
        if (!TOKEN_FORMAT.matches(token)) return@transaction null
        val row = Invites.selectAll().where { Invites.tokenHash eq hash(token) }.forUpdate().singleOrNull()
            ?: return@transaction null
        val spaceId = row[Invites.spaceId]
        if (spaces.membership(spaceId, user) != null) return@transaction AcceptResult.Joined(spaceId)
        val at = now()
        when (row.state(at)) {
            InviteState.USED -> AcceptResult.Used
            InviteState.EXPIRED -> AcceptResult.Expired
            InviteState.VALID -> {
                spaces.addMember(spaceId, user, roleOf(row[Invites.role]), at)
                Invites.update({ (Invites.id eq row[Invites.id]) and Invites.usedAt.isNull() }) {
                    it[usedBy] = user.value
                    it[usedAt] = at
                }
                AcceptResult.Joined(spaceId)
            }
        }
    }

    // used_at, not used_by: used_by is cleared when the user who used it deletes their account.
    private fun ResultRow.state(at: Long = now()) = when {
        this[Invites.usedAt] != null -> InviteState.USED
        this[Invites.expiresAt] <= at -> InviteState.EXPIRED
        else -> InviteState.VALID
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
