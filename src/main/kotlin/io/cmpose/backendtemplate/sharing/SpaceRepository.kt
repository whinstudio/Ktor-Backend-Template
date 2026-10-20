package io.cmpose.backendtemplate.sharing

import io.cmpose.backendtemplate.files.StoredFiles
import io.cmpose.backendtemplate.plugins.Quota
import io.cmpose.backendtemplate.plugins.quotaExceeded
import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.UserIdentities
import io.cmpose.backendtemplate.users.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import java.util.UUID

/** Longest space name after trimming. */
const val MAX_SPACE_NAME = 80

/** The name a personal space starts with; its owner may rename it. */
const val PERSONAL_SPACE_NAME = "Personal"

// Mirror db/migration/V2__sharing.sql; Flyway owns the schema, these objects only describe it to Exposed.

object Spaces : Table("spaces") {
    val id = uuid("id")
    val ownerId = uuid("owner_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val isPersonal = bool("is_personal")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** `role` holds a [Role]'s wire name. */
object SpaceMembers : Table("space_members") {
    val spaceId = uuid("space_id").references(Spaces.id, onDelete = ReferenceOption.CASCADE)
    val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val role = text("role")
    val joinedAt = long("joined_at")
    override val primaryKey = PrimaryKey(spaceId, userId)
}

/** A space as one member sees it: their [role] in it and how many members it has. */
@Serializable
data class SpaceResponse(
    val id: String,
    val name: String,
    val role: Role,
    val personal: Boolean,
    val memberCount: Int,
)

/** A member of a space; [displayName] is the name they chose to show and [email] their verified email, if any. */
@Serializable
data class MemberResponse(
    val userId: String,
    val displayName: String?,
    val email: String?,
    val role: Role,
    val joinedAt: Long,
)

/** What a role change or a removal did. */
enum class MemberChange { DONE, NOT_MEMBER, LAST_OWNER }

/**
 * Locks the space's row until the transaction ends; needs a transaction. Whatever counts against a space (its members,
 * its pending invites) is counted after this, so two writes cannot both pass on the same count, and space deletion
 * (which locks the row `FOR UPDATE`) waits. The lock is `FOR NO KEY UPDATE`: it leaves free the key-share lock that a
 * foreign key takes on the row, so an insert pointing at the space (an upload, a member) is not blocked by it.
 */
internal fun lockSpace(spaceId: UUID) {
    Spaces.select(Spaces.id).where { Spaces.id eq spaceId }
        .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
        .toList()
}

/**
 * Spaces and their members. Callers check roles first (`requireRole`); nothing here checks who is asking. A user owns
 * at most [maxSpacesPerUser] spaces besides their personal one, and a space has at most [maxMembersPerSpace] members,
 * its owners included; `0` turns a limit off.
 */
class SpaceRepository(
    private val db: Database,
    private val maxSpacesPerUser: Int = 0,
    private val maxMembersPerSpace: Int = 0,
) {
    fun membership(spaceId: UUID, user: UserId): Membership? = transaction(db) {
        SpaceMembers.selectAll()
            .where { (SpaceMembers.spaceId eq spaceId) and (SpaceMembers.userId eq user.value) }
            .singleOrNull()
            ?.let { Membership(spaceId, user, roleOf(it[SpaceMembers.role])) }
    }

    /**
     * [user]'s personal space, created with them as its OWNER on first use. Safe under concurrent first calls: the
     * insert is `ON CONFLICT DO NOTHING` on the one-personal-space index, and the loser reads the winner's space.
     */
    fun personalSpace(user: UserId): UUID = transaction(db) {
        personalOf(user)?.let { return@transaction it }
        val spaceId = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val created = Spaces.insertIgnore {
            it[id] = spaceId
            it[ownerId] = user.value
            it[name] = PERSONAL_SPACE_NAME
            it[isPersonal] = true
            it[createdAt] = now
        }.insertedCount > 0
        if (created) {
            addMember(spaceId, user, Role.OWNER, now)
            spaceId
        } else {
            checkNotNull(personalOf(user)) { "personal space vanished during a concurrent insert" }
        }
    }

    /** The spaces [user] belongs to: their personal space first, then by name. */
    fun listFor(user: UserId): List<SpaceResponse> = transaction(db) {
        val rows = (SpaceMembers innerJoin Spaces).selectAll().where { SpaceMembers.userId eq user.value }.toList()
        val counts = memberCounts(rows.map { it[Spaces.id] })
        rows.sortedWith(
            compareByDescending<ResultRow> { it[Spaces.isPersonal] && it[Spaces.ownerId] == user.value }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it[Spaces.name] }
                .thenBy { it[Spaces.id] },
        ).map { it.toSpace(counts[it[Spaces.id]] ?: 1) }
    }

    /** The space as [user] sees it, or null when they are not a member. */
    fun get(spaceId: UUID, user: UserId): SpaceResponse? = transaction(db) {
        (SpaceMembers innerJoin Spaces).selectAll()
            .where { (SpaceMembers.spaceId eq spaceId) and (SpaceMembers.userId eq user.value) }
            .singleOrNull()
            ?.toSpace(memberCounts(listOf(spaceId))[spaceId] ?: 1)
    }

    /**
     * The ids of the spaces [user] owns, their personal space included, locked (`FOR UPDATE`) until the transaction
     * ends: account deletion calls this inside its transaction, so no file can be added to them meanwhile.
     */
    fun ownedBy(user: UserId): List<UUID> = transaction(db) {
        Spaces.select(Spaces.id).where { Spaces.ownerId eq user.value }.forUpdate().map { it[Spaces.id] }
    }

    fun isPersonal(spaceId: UUID): Boolean = transaction(db) {
        Spaces.select(Spaces.isPersonal).where { Spaces.id eq spaceId }.singleOrNull()?.get(Spaces.isPersonal) ?: false
    }

    /** A new shared space; [user] becomes its OWNER. Throws a 409 `QUOTA_EXCEEDED` (`spaces`) at the limit. */
    fun create(user: UserId, name: String): SpaceResponse = transaction(db) {
        if (maxSpacesPerUser > 0) checkSpaces(user)
        val now = System.currentTimeMillis()
        val spaceId = UUID.randomUUID()
        Spaces.insert {
            it[id] = spaceId
            it[ownerId] = user.value
            it[Spaces.name] = name
            it[isPersonal] = false
            it[createdAt] = now
        }
        addMember(spaceId, user, Role.OWNER, now)
        SpaceResponse(spaceId.toString(), name, Role.OWNER, personal = false, memberCount = 1)
    }

    /**
     * Refuses a new space when [user] already owns [maxSpacesPerUser] shared ones. Their user row is locked first, so
     * two creates cannot both pass on the same count, and account deletion (which locks it `FOR UPDATE`) waits. The
     * lock is `FOR NO KEY UPDATE`, as for the storage quota: the foreign keys' key-share locks on the row stay free.
     */
    private fun checkSpaces(user: UserId) {
        Users.select(Users.id).where { Users.id eq user.value }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
            .toList()
        val owned = Spaces.selectAll()
            .where { (Spaces.ownerId eq user.value) and (Spaces.isPersonal eq false) }
            .count()
        if (owned >= maxSpacesPerUser) quotaExceeded(Quota.SPACES)
    }

    fun rename(spaceId: UUID, name: String) {
        transaction(db) { Spaces.update({ Spaces.id eq spaceId }) { it[Spaces.name] = name } }
    }

    /**
     * Deletes the space; its members, invites and file rows go with it (ON DELETE CASCADE). The space row is locked
     * first, so no file is added while the paths are read. Returns the paths of its files, for the caller to remove.
     */
    fun delete(spaceId: UUID): List<String> = transaction(db) {
        Spaces.select(Spaces.id).where { Spaces.id eq spaceId }.forUpdate().toList()
        val paths = StoredFiles.select(StoredFiles.path).where { StoredFiles.spaceId eq spaceId }
            .map { it[StoredFiles.path] }
        Spaces.deleteWhere { id eq spaceId }
        paths
    }

    /** The members: owners first, then in the order they joined. */
    fun members(spaceId: UUID): List<MemberResponse> = transaction(db) {
        val rows = SpaceMembers.selectAll().where { SpaceMembers.spaceId eq spaceId }.toList()
        val userIds = rows.map { it[SpaceMembers.userId] }
        val names = displayNamesOf(userIds)
        val emails = emailsOf(userIds)
        rows.map {
            val user = it[SpaceMembers.userId]
            MemberResponse(
                userId = user.toString(),
                displayName = names[user],
                email = emails[user],
                role = roleOf(it[SpaceMembers.role]),
                joinedAt = it[SpaceMembers.joinedAt],
            )
        }.sortedWith(
            compareByDescending<MemberResponse> { it.role == Role.OWNER }.thenBy { it.joinedAt }.thenBy { it.userId },
        )
    }

    /** Gives [user] [role]; refused when that would leave the space without an OWNER. */
    fun setRole(spaceId: UUID, user: UserId, role: Role): MemberChange = transaction(db) {
        changeMember(spaceId, user, keepsOwner = role == Role.OWNER) {
            SpaceMembers.update({ member(spaceId, user) }) { it[SpaceMembers.role] = role.wire() }
        }
    }

    /** Removes [user] from the space; refused when they are its last OWNER. */
    fun removeMember(spaceId: UUID, user: UserId): MemberChange = transaction(db) {
        changeMember(spaceId, user, keepsOwner = false) { SpaceMembers.deleteWhere { member(spaceId, user) } }
    }

    /**
     * Throws a 409 `QUOTA_EXCEEDED` (`members`) when the space already has [maxMembersPerSpace] members. Needs a
     * transaction with the space locked ([lockSpace]), so two joins cannot both pass on the same count.
     */
    internal fun requireMemberRoom(spaceId: UUID) {
        if (maxMembersPerSpace <= 0) return
        val members = SpaceMembers.selectAll().where { SpaceMembers.spaceId eq spaceId }.count()
        if (members >= maxMembersPerSpace) quotaExceeded(Quota.MEMBERS)
    }

    /** Adds [user] with [role]; a no-op when they are a member already (their role stays). Needs a transaction. */
    internal fun addMember(spaceId: UUID, user: UserId, role: Role, at: Long) {
        SpaceMembers.insertIgnore {
            it[SpaceMembers.spaceId] = spaceId
            it[userId] = user.value
            it[SpaceMembers.role] = role.wire()
            it[joinedAt] = at
        }
    }

    /**
     * Runs [change] on [user]'s membership unless it would remove the space's last OWNER. Every member row of the
     * space is locked first (`FOR UPDATE`), so two concurrent changes cannot each remove one of two owners.
     */
    private fun changeMember(spaceId: UUID, user: UserId, keepsOwner: Boolean, change: () -> Unit): MemberChange {
        val roles = SpaceMembers.selectAll().where { SpaceMembers.spaceId eq spaceId }.forUpdate()
            .associate { it[SpaceMembers.userId] to roleOf(it[SpaceMembers.role]) }
        val current = roles[user.value] ?: return MemberChange.NOT_MEMBER
        val lastOwner = current == Role.OWNER && roles.values.count { it == Role.OWNER } == 1
        if (lastOwner && !keepsOwner) return MemberChange.LAST_OWNER
        change()
        return MemberChange.DONE
    }

    private fun member(spaceId: UUID, user: UserId) =
        (SpaceMembers.spaceId eq spaceId) and (SpaceMembers.userId eq user.value)

    private fun personalOf(user: UserId): UUID? = Spaces.select(Spaces.id)
        .where { (Spaces.ownerId eq user.value) and (Spaces.isPersonal eq true) }
        .singleOrNull()
        ?.get(Spaces.id)

    private fun memberCounts(spaceIds: List<UUID>): Map<UUID, Int> {
        if (spaceIds.isEmpty()) return emptyMap()
        val count = SpaceMembers.userId.count()
        return SpaceMembers.select(SpaceMembers.spaceId, count)
            .where { SpaceMembers.spaceId inList spaceIds }
            .groupBy(SpaceMembers.spaceId)
            .associate { it[SpaceMembers.spaceId] to it[count].toInt() }
    }

    // The display names that are set, as UserRepository.displayName reports them.
    private fun displayNamesOf(users: List<UUID>): Map<UUID, String> {
        if (users.isEmpty()) return emptyMap()
        return Users.select(Users.id, Users.displayName)
            .where { (Users.id inList users) and Users.displayName.isNotNull() }
            .associate { it[Users.id] to it[Users.displayName]!! }
    }

    // The first email by provider, as UserRepository.email reports it.
    private fun emailsOf(users: List<UUID>): Map<UUID, String> {
        if (users.isEmpty()) return emptyMap()
        return UserIdentities.selectAll()
            .where { (UserIdentities.userId inList users) and UserIdentities.email.isNotNull() }
            .sortedBy { it[UserIdentities.provider] }
            .groupBy { it[UserIdentities.userId] }
            .mapValues { (_, rows) -> rows.first()[UserIdentities.email]!! }
    }

    private fun ResultRow.toSpace(memberCount: Int) = SpaceResponse(
        id = this[Spaces.id].toString(),
        name = this[Spaces.name],
        role = roleOf(this[SpaceMembers.role]),
        personal = this[Spaces.isPersonal],
        memberCount = memberCount,
    )
}
