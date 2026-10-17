package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.db.connectDatabase
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    @Test
    fun flywayCleanMigrate() {
        TestDb.createEmptyDatabase("migration_clean")
        val db = connectDatabase(TestDb.configFor("migration_clean"))
        val tables = transaction(db) {
            val names = mutableListOf<String>()
            exec("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename") { rs ->
                while (rs.next()) names += rs.getString(1)
            }
            names
        }
        val core = listOf("files", "flyway_schema_history", "items", "user_identities", "users")
        // cmpose:sharing-off: val expected = core
        val expected = (core + listOf("invites", "space_members", "spaces")).sorted()
        assertEquals(expected, tables)
    }
}
