@file:Suppress("MatchingDeclarationName") // Named after its part of the feature, like the other files beside it.

package io.cmpose.backendtemplate.feature.sample

import org.jetbrains.exposed.sql.Table

// Mirrors db/migration/V3__sample.sql; Flyway owns the schema, this object only describes it to Exposed.
object Items : Table("items") {
    val id = uuid("id")
    // cmpose:sharing-off: val ownerId = uuid("owner_id")
    val spaceId = uuid("space_id")
    val title = text("title")
    val note = text("note").nullable()
    val dueDate = long("due_date").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}
