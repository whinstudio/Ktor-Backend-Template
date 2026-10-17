package io.cmpose.backendtemplate.feature.sample

import io.cmpose.backendtemplate.users.UserId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

// cmpose:sharing-off: /** Item rows; every query filters on the caller: another user's item is not found. */
/** Item rows. Nothing here takes a [UserId] or checks one: callers check the role in the space (`requireRole`). */
class ItemRepository(private val db: Database) {
    // cmpose:sharing-off: /** Up to [limit] of [owner]'s items, newest first, after [after] (null: from the start). */
    /** Up to [limit] items of [spaceId], newest first, starting after [after] (from the start when null). */
    // cmpose:sharing-off: fun list(owner: UserId, limit: Int, after: ItemCursor?): List<Item> = transaction(db) {
    fun list(spaceId: UUID, limit: Int, after: ItemCursor?): List<Item> = transaction(db) {
        // cmpose:sharing-off: Items.selectAll().where { Items.ownerId eq owner.value }
        Items.selectAll().where { Items.spaceId eq spaceId }
            .apply { if (after != null) andWhere { before(after) } }
            .orderBy(Items.createdAt to SortOrder.DESC, Items.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toItem() }
    }

    // cmpose:sharing-off: fun add(item: Item, owner: UserId) {
    fun add(item: Item) {
        transaction(db) {
            Items.insert {
                it[id] = item.id
                // cmpose:sharing-off: it[ownerId] = owner.value
                it[spaceId] = item.spaceId
                it[title] = item.title
                it[note] = item.note
                it[dueDate] = item.dueDate
                it[createdAt] = item.createdAt
                it[updatedAt] = item.updatedAt
            }
        }
    }

    // cmpose:sharing-off: /** The item [id] if [owner] owns it, else null. */
    /** The item [id], or null. */
    // cmpose:sharing-off: fun get(id: UUID, owner: UserId): Item? = transaction(db) {
    fun get(id: UUID): Item? = transaction(db) {
        // cmpose:sharing-off: Items.selectAll().where(row(id, owner)).singleOrNull()?.toItem()
        Items.selectAll().where(row(id)).singleOrNull()?.toItem()
    }

    // cmpose:sharing-off: /** Writes [item]'s fields and update time; false when [owner] has no such item. */
    /** Writes [item]'s fields and update time; false when the item is gone. */
    // cmpose:sharing-off: fun update(item: Item, owner: UserId): Boolean = transaction(db) {
    fun update(item: Item): Boolean = transaction(db) {
        // cmpose:sharing-off: Items.update({ row(item.id, owner) }) {
        Items.update({ row(item.id) }) {
            it[title] = item.title
            it[note] = item.note
            it[dueDate] = item.dueDate
            it[updatedAt] = item.updatedAt
        } > 0
    }

    // cmpose:sharing-off: /** False when [owner] had no such item. */
    /** False when there was no such item. */
    // cmpose:sharing-off: fun delete(id: UUID, owner: UserId): Boolean =
    fun delete(id: UUID): Boolean =
        // cmpose:sharing-off: transaction(db) { Items.deleteWhere { row(id, owner) } > 0 }
        transaction(db) { Items.deleteWhere { row(id) } > 0 }

    // cmpose:sharing-off: private fun row(id: UUID, owner: UserId): Op<Boolean> =
    private fun row(id: UUID): Op<Boolean> =
        // cmpose:sharing-off: (Items.id eq id) and (Items.ownerId eq owner.value)
        Items.id eq id

    // Strictly after [cursor] in the newest-first order; the id breaks ties between items created in the same ms.
    private fun before(cursor: ItemCursor): Op<Boolean> =
        (Items.createdAt less cursor.createdAt) or
            ((Items.createdAt eq cursor.createdAt) and (Items.id less cursor.id))

    private fun ResultRow.toItem() = Item(
        id = this[Items.id],
        // cmpose:sharing
        spaceId = this[Items.spaceId],
        // /cmpose:sharing
        title = this[Items.title],
        note = this[Items.note],
        dueDate = this[Items.dueDate],
        createdAt = this[Items.createdAt],
        updatedAt = this[Items.updatedAt],
    )
}
