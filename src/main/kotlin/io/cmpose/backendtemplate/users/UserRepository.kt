package io.cmpose.backendtemplate.users

import io.cmpose.backendtemplate.auth.VerifiedIdentity
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** Our own id for a user; a provider's subject (the Firebase uid) is only mapped to it, never used in its place. */
@JvmInline
value class UserId(val value: UUID) {
    override fun toString() = value.toString()
}

// Mirror db/migration/V1__core.sql; Flyway owns the schema, these objects only describe it to Exposed.

object Users : Table("users") {
    val id = uuid("id")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object UserIdentities : Table("user_identities") {
    val provider = text("provider")
    val subject = text("subject")
    val userId = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val email = text("email").nullable()
    override val primaryKey = PrimaryKey(provider, subject)
}

class UserRepository(
    private val db: Database,
    // cmpose:sharing
    /** Runs in the transaction that creates a user, so a new user never exists without it (the personal space). */
    private val onCreated: (UserId) -> Unit = {},
    // /cmpose:sharing
) {
    /**
     * The user [identity] maps to, created with a new id on first sight. Only a verified email is stored: the stored
     * email follows a token whose email is verified, a token with an unverified email clears it (nobody can claim an
     * address they do not control), and a token without one keeps the stored value.
     *
     * Safe under concurrent first requests: the identity insert is `ON CONFLICT DO NOTHING`, and the loser of the race
     * drops the user row it created and reads the winner's mapping.
     */
    fun resolveOrCreate(identity: VerifiedIdentity): UserId = transaction(db) {
        val verifiedEmail = identity.email?.takeIf { identity.emailVerified }
        existing(identity)?.let { (user, email) ->
            if (identity.email != null && verifiedEmail != email) updateEmail(identity, verifiedEmail)
            return@transaction user
        }
        val user = UUID.randomUUID()
        Users.insert {
            it[id] = user
            it[createdAt] = System.currentTimeMillis()
        }
        val mapped = UserIdentities.insertIgnore {
            it[provider] = identity.provider
            it[subject] = identity.subject
            it[userId] = user
            it[email] = verifiedEmail
        }.insertedCount > 0
        if (mapped) {
            // cmpose:sharing
            onCreated(UserId(user))
            // /cmpose:sharing
            UserId(user)
        } else {
            Users.deleteWhere { id eq user }
            checkNotNull(existing(identity)) { "identity mapping vanished during a concurrent insert" }.first
        }
    }

    /** The email of [user]'s identities (the first by provider), or null when none carries one. */
    fun email(user: UserId): String? = transaction(db) {
        UserIdentities.selectAll()
            .where { (UserIdentities.userId eq user.value) and UserIdentities.email.isNotNull() }
            .orderBy(UserIdentities.provider to SortOrder.ASC)
            .firstOrNull()
            ?.get(UserIdentities.email)
    }

    /**
     * Removes the user row; ON DELETE CASCADE removes its identity mappings and whatever else points at it. The next
     * valid token of the same subject then creates a new user. A user that does not exist is a no-op.
     */
    fun delete(user: UserId) {
        delete(user) { }
    }

    /**
     * Like [delete], but first runs [collect] (e.g. reading the paths of the user's files) in the same transaction,
     * with the user row locked: a row that points at the user and is added concurrently is either seen by [collect] or
     * fails its foreign key afterwards. Returns what [collect] returned.
     */
    fun <T> delete(user: UserId, collect: () -> T): T = transaction(db) {
        Users.selectAll().where { Users.id eq user.value }.forUpdate().firstOrNull()
        val collected = collect()
        Users.deleteWhere { id eq user.value }
        collected
    }

    private fun existing(identity: VerifiedIdentity): Pair<UserId, String?>? = UserIdentities.selectAll()
        .where { (UserIdentities.provider eq identity.provider) and (UserIdentities.subject eq identity.subject) }
        .firstOrNull()
        ?.let { UserId(it[UserIdentities.userId]) to it[UserIdentities.email] }

    private fun updateEmail(identity: VerifiedIdentity, verifiedEmail: String?) {
        UserIdentities.update({
            (UserIdentities.provider eq identity.provider) and (UserIdentities.subject eq identity.subject)
        }) { it[email] = verifiedEmail }
    }
}
