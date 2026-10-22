package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.db.connectDatabase
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** One embedded PostgreSQL 16 for the whole test run (real server binaries; no Docker required). */
object TestDb {
    private val postgres = EmbeddedPostgres.builder().start()

    /** The test database, migrated by the production [connectDatabase]. */
    val database: Database by lazy { connectDatabase(configFor("postgres")) }

    /** A config pointing at [databaseName] on the embedded server. */
    fun configFor(databaseName: String) = testConfig().copy(
        databaseUrl = postgres.getJdbcUrl("postgres", databaseName),
        databaseUser = "postgres",
        databasePassword = "postgres",
        dbPoolSize = 2,
    )

    /** Creates an empty database named [name] on the embedded server. */
    fun createEmptyDatabase(name: String) {
        require(name.all { it.isLetterOrDigit() || it == '_' }) { "plain identifiers only" }
        postgres.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
    }

    /** Runs one SQL [statement] (a test's own schema tweak) in a transaction of its own. */
    fun execute(statement: String) {
        transaction(database) { exec(statement) }
    }

    /** Empties every table Flyway created; a block body keeps the return type `Unit` for `@BeforeTest`. */
    fun reset() {
        transaction(database) {
            val tables = mutableListOf<String>()
            exec(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
            ) { rs -> while (rs.next()) tables += rs.getString(1) }
            if (tables.isNotEmpty()) exec("TRUNCATE ${tables.joinToString { "\"$it\"" }} CASCADE")
        }
    }
}
