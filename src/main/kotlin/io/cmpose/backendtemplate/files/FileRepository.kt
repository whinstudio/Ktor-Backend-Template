package io.cmpose.backendtemplate.files

import io.cmpose.backendtemplate.plugins.Quota
import io.cmpose.backendtemplate.plugins.quotaExceeded
import io.cmpose.backendtemplate.sharing.Spaces
import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.Users
import org.jetbrains.exposed.sql.AndOp
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.ForUpdateOption
import java.util.UUID

// Mirrors db/migration/V1__core.sql; Flyway owns the schema.
object StoredFiles : Table("files") {
    val id = uuid("id")
    // cmpose:sharing-off: val ownerId = uuid("owner_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val spaceId = uuid("space_id")
    // cmpose:sharing
    val uploadedBy = uuid("uploaded_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    // /cmpose:sharing
    val path = text("path")
    val mime = text("mime")
    val size = long("size")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** A file row: [path] is the [FileStore] path; [mime] is the type sniffed from the bytes. */
data class StoredFile(
    val id: UUID,
    // cmpose:sharing-off: val ownerId: UserId,
    val spaceId: UUID,
    // cmpose:sharing
    val uploadedBy: UserId?,
    // /cmpose:sharing
    val path: String,
    val mime: String,
    val size: Long,
)

// cmpose:sharing-off: /** File rows; every lookup takes the caller: another user's file is not found. */
/** File rows. Lookups check nothing: callers check the caller's role in the file's space first (`requireRole`). */
class FileRepository(private val db: Database, private val maxStorageBytesPerUser: Long = 0) {
    /** Adds the row, or throws a 409 `QUOTA_EXCEEDED` (`storage`) when it would take its user past the quota. */
    fun add(file: StoredFile) {
        transaction(db) {
            if (maxStorageBytesPerUser > 0) checkStorage(file)
            StoredFiles.insert {
                it[id] = file.id
                // cmpose:sharing-off: it[ownerId] = file.ownerId.value
                it[spaceId] = file.spaceId
                // cmpose:sharing
                it[uploadedBy] = file.uploadedBy?.value
                // /cmpose:sharing
                it[path] = file.path
                it[mime] = file.mime
                it[size] = file.size
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Refuses [file] when its user's stored bytes plus its own would pass [maxStorageBytesPerUser]. That user's row is
     * locked first, so two uploads cannot both pass on the same total, and account deletion (which locks it `FOR
     * UPDATE`) waits. The lock is `FOR NO KEY UPDATE`: it does not block the key-share lock a foreign key takes on the
     * row, so an upload whose insert points at a user another upload has locked cannot deadlock with it.
     */
    private fun checkStorage(file: StoredFile) {
        // cmpose:sharing
        // The space's owner pays, whoever uploaded (an editor's file counts against them), over every space they own.
        // /cmpose:sharing
        // cmpose:sharing-off: val charged = file.ownerId.value
        val charged = Spaces.select(Spaces.ownerId).where { Spaces.id eq file.spaceId }.single()[Spaces.ownerId]
        Users.select(Users.id).where { Users.id eq charged }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
            .toList()
        val total = StoredFiles.size.sum()
        // cmpose:sharing-off: val used = StoredFiles.select(total)
        val used = StoredFiles.join(Spaces, JoinType.INNER, StoredFiles.spaceId, Spaces.id).select(total)
            // cmpose:sharing-off: .where { StoredFiles.ownerId eq charged }
            .where { Spaces.ownerId eq charged }
            .single()[total] ?: 0L
        if (used + file.size > maxStorageBytesPerUser) quotaExceeded(Quota.STORAGE)
    }

    // cmpose:sharing-off: /** The file [id] if [owner] owns it, else null. */
    /** The file [id], or null. */
    // cmpose:sharing-off: fun get(id: UUID, owner: UserId): StoredFile? = transaction(db) {
    fun get(id: UUID): StoredFile? = transaction(db) {
        // cmpose:sharing-off: StoredFiles.selectAll().where(row(id, owner)).singleOrNull()?.toStoredFile()
        StoredFiles.selectAll().where(row(id)).singleOrNull()?.toStoredFile()
    }

    // cmpose:sharing-off: /** False when there was no such file of [owner]'s. */
    /** False when there was no such file. */
    // cmpose:sharing-off: fun delete(id: UUID, owner: UserId): Boolean =
    fun delete(id: UUID): Boolean =
        // cmpose:sharing-off: transaction(db) { StoredFiles.deleteWhere { row(id, owner) } > 0 }
        transaction(db) { StoredFiles.deleteWhere { row(id) } > 0 }

    // cmpose:sharing-off: /** The store paths of every file [owner] owns (account deletion). */
    /** The store paths of every file in [spaces] (account deletion). */
    // cmpose:sharing-off: fun pathsOf(owner: UserId): List<String> = transaction(db) {
    fun pathsIn(spaces: List<UUID>): List<String> = transaction(db) {
        StoredFiles.select(StoredFiles.path)
            // cmpose:sharing-off: .where { StoredFiles.ownerId eq owner.value }
            .where { StoredFiles.spaceId inList spaces }
            .map { it[StoredFiles.path] }
    }

    // cmpose:sharing-off: private fun row(id: UUID, owner: UserId) =
    private fun row(id: UUID) =
        // cmpose:sharing-off: AndOp(listOf(StoredFiles.id eq id, StoredFiles.ownerId eq owner.value))
        AndOp(listOf(StoredFiles.id eq id))

    private fun ResultRow.toStoredFile() = StoredFile(
        id = this[StoredFiles.id],
        // cmpose:sharing-off: ownerId = UserId(this[StoredFiles.ownerId]),
        spaceId = this[StoredFiles.spaceId],
        // cmpose:sharing
        uploadedBy = this[StoredFiles.uploadedBy]?.let(::UserId),
        // /cmpose:sharing
        path = this[StoredFiles.path],
        mime = this[StoredFiles.mime],
        size = this[StoredFiles.size],
    )
}
