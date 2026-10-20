package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.plugins.Quota
import io.cmpose.backendtemplate.plugins.quotaExceeded
import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.Users
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
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

// The pending-invite list's own limit, whatever the quota: an owner sees (and can revoke) them after it is lowered.
private const val MAX_LISTED_INVITES = 200

// What an invite token looks like: TOKEN_BYTES bytes in URL-safe base64 without padding.
private val TOKEN_FORMAT = Regex("^[A-Za-z0-9_-]{43}$")

// Mirrors db/migration/V2__sharing.sql and V5__invite_revoke.sql.
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
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * A new invite: the [token] is shown once and never stored; [id] names it in the pending list and to revoke it;
 * [expiresAt] is epoch milliseconds.
 */
@Serializable
data class InviteResponse(val id: String, val token: String, val expiresAt: Long)

/**
 * Whether an invite can still be accepted. The first that applies wins: once used it stays [USED], once revoked
 * [REVOKED] (even after it expires), then [EXPIRED].
 */
@Serializable
enum class InviteState {
    @SerialName("valid")
    VALID,

    @SerialName("used")
    USED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("revoked")
    REVOKED,
}

/** A valid invite as the space's owner lists it: never its token. Times are epoch milliseconds. */
@Serializable
data class PendingInvite(val id: String, val role: Role, val createdAt: Long, val expiresAt: Long)

/** How a revoke ended: done (also when it was revoked or had expired already), refused as used, or no such invite. */
enum class RevokeResult { REVOKED, USED, NOT_FOUND }

/** What an invite offers, and from whom: [inviterName] is its creator's display name, null when they have none. */
@Serializable
data class InvitePreview(
    val spaceName: String,
    val inviterName: String?,
    val role: Role,
    val expiresAt: Long,
    val state: InviteState,
)

/**
 * How an accept ended: the caller is in the space, or the invite was already used, has expired or was revoked. A full
 * space is not a result: it throws a 409 `QUOTA_EXCEEDED` (`members`).
 */
sealed interface AcceptResult {
    data class Joined(val spaceId: UUID) : AcceptResult

    data object Used : AcceptResult

    data object Expired : AcceptResult

    data object Revoked : AcceptResult
}

/**
 * Single-use invite links that expire [ttlDays] after creation. The token (32 random bytes, URL-safe base64) is
 * handed out once; only its SHA-256 hex digest is stored, and every lookup goes through that digest. An unknown or
 * malformed token is null to callers; a known one reports its [InviteState]. A space has at most [maxPendingPerSpace]
 * pending (valid) invites; `0` turns the limit off. A revoked invite keeps its row, so its preview says so.
 */
class InviteRepository(
    private val db: Database,
    ttlDays: Int,
    private val maxPendingPerSpace: Int = 0,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val ttlMillis = ttlDays.days.inWholeMilliseconds

    /**
     * A new invite to [spaceId] with [role] (EDITOR or VIEWER; the schema refuses OWNER). Throws a 409
     * `QUOTA_EXCEEDED` (`invites`) when the space already has [maxPendingPerSpace] valid invites; the space is locked
     * ([lockSpace]) before they are counted.
     */
    fun create(spaceId: UUID, createdBy: UserId, role: Role): InviteResponse {
        require(role != Role.OWNER) { "Owner cannot be invited" }
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val createdAt = now()
        val expiresAt = createdAt + ttlMillis
        val inviteId = UUID.randomUUID()
        transaction(db) {
            if (maxPendingPerSpace > 0) {
                lockSpace(spaceId)
                val pending = Invites.selectAll().where { (Invites.spaceId eq spaceId) and validAt(createdAt) }.count()
                if (pending >= maxPendingPerSpace) quotaExceeded(Quota.INVITES)
            }
            Invites.insert {
                it[id] = inviteId
                it[Invites.spaceId] = spaceId
                it[tokenHash] = hash(token)
                it[Invites.role] = role.wire()
                it[Invites.createdBy] = createdBy.value
                it[Invites.createdAt] = createdAt
                it[Invites.expiresAt] = expiresAt
            }
        }
        return InviteResponse(inviteId.toString(), token, expiresAt)
    }

    /** The space's valid invites, newest first, at most [MAX_LISTED_INVITES] of them. */
    fun pending(spaceId: UUID): List<PendingInvite> = transaction(db) {
        Invites.selectAll()
            .where { (Invites.spaceId eq spaceId) and validAt(now()) }
            .orderBy(Invites.createdAt to SortOrder.DESC, Invites.id to SortOrder.DESC)
            .limit(MAX_LISTED_INVITES)
            .map {
                PendingInvite(
                    id = it[Invites.id].toString(),
                    role = roleOf(it[Invites.role]),
                    createdAt = it[Invites.createdAt],
                    expiresAt = it[Invites.expiresAt],
                )
            }
    }

    /**
     * Revokes [spaceId]'s invite [inviteId]: a valid one gets its revocation time; a revoked or expired one is left as
     * it is and reported [RevokeResult.REVOKED] too, so a repeat does the same. A used invite is refused
     * ([RevokeResult.USED]); one of another space, or none, is [RevokeResult.NOT_FOUND]. Locks the space row
     * ([lockSpace]), then the invite row (`FOR UPDATE`), the order an accept keeps: the two cannot both pass.
     */
    fun revoke(spaceId: UUID, inviteId: UUID): RevokeResult = transaction(db) {
        lockSpace(spaceId)
        val row = Invites.selectAll().where { (Invites.id eq inviteId) and (Invites.spaceId eq spaceId) }
            .forUpdate()
            .singleOrNull()
            ?: return@transaction RevokeResult.NOT_FOUND
        val at = now()
        when (row.state(at)) {
            InviteState.USED -> RevokeResult.USED
            InviteState.VALID -> {
                Invites.update({ Invites.id eq inviteId }) { it[revokedAt] = at }
                RevokeResult.REVOKED
            }
            InviteState.REVOKED, InviteState.EXPIRED -> RevokeResult.REVOKED
        }
    }

    /**
     * What the invite offers and its state, used, revoked and expired ones too; null when [token] is unknown or
     * malformed.
     */
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
     * Joins [user] to the invite's space; null when [token] is unknown or malformed, [AcceptResult.Used],
     * [AcceptResult.Revoked] or [AcceptResult.Expired] when the invite cannot be accepted any more, and a 409
     * `QUOTA_EXCEEDED` (`members`) when the space is full (the invite stays valid). One transaction that locks the
     * space row ([lockSpace]), then the invite row (`FOR UPDATE`): two concurrent accepts can neither both use one
     * invite nor both take the last place, nor can an accept and a [revoke] both pass.
     * A caller who already is a member gets the space whatever the invite's state (someone reopening their own used
     * link) and whether or not it is full, keeps their role and leaves the invite as it was.
     */
    fun accept(token: String, user: UserId, spaces: SpaceRepository): AcceptResult? = transaction(db) {
        if (!TOKEN_FORMAT.matches(token)) return@transaction null
        val byToken = Invites.tokenHash eq hash(token)
        // The space is locked before the invite, the order every write that counts against a space keeps.
        val spaceId = Invites.select(Invites.spaceId).where(byToken).singleOrNull()?.get(Invites.spaceId)
            ?: return@transaction null
        lockSpace(spaceId)
        val row = Invites.selectAll().where(byToken).forUpdate().singleOrNull() ?: return@transaction null
        if (spaces.membership(spaceId, user) != null) return@transaction AcceptResult.Joined(spaceId)
        val at = now()
        when (row.state(at)) {
            InviteState.USED -> AcceptResult.Used
            InviteState.EXPIRED -> AcceptResult.Expired
            InviteState.REVOKED -> AcceptResult.Revoked
            InviteState.VALID -> {
                spaces.requireMemberRoom(spaceId)
                spaces.addMember(spaceId, user, roleOf(row[Invites.role]), at)
                Invites.update({ (Invites.id eq row[Invites.id]) and Invites.usedAt.isNull() }) {
                    it[usedBy] = user.value
                    it[usedAt] = at
                }
                AcceptResult.Joined(spaceId)
            }
        }
    }

    // An invite that can still be accepted at [at]: what counts as pending. Matches state() == VALID.
    private fun validAt(at: Long): Op<Boolean> =
        Invites.usedAt.isNull() and Invites.revokedAt.isNull() and (Invites.expiresAt greater at)

    // used_at, not used_by: used_by is cleared when the user who used it deletes their account.
    private fun ResultRow.state(at: Long = now()) = when {
        this[Invites.usedAt] != null -> InviteState.USED
        this[Invites.revokedAt] != null -> InviteState.REVOKED
        this[Invites.expiresAt] <= at -> InviteState.EXPIRED
        else -> InviteState.VALID
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
