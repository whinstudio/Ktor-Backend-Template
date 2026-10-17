package io.cmpose.backendtemplate.local

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File

private const val DATABASE = "app"
private const val SUPERUSER = "postgres"

/** Embedded PostgreSQL 16 whose data stays in `<dir>/postgres` between runs (no Docker needed). */
class LocalDatabase private constructor(private val postgres: EmbeddedPostgres) : AutoCloseable {
    // zonky's getJdbcUrl appends "?user=…"; the credentials travel separately, so the URL ends at the database name.
    val jdbcUrl: String = "jdbc:postgresql://localhost:${postgres.port}/$DATABASE"
    val user = SUPERUSER
    val password = SUPERUSER

    override fun close() = postgres.close()

    companion object {
        fun start(dir: File): LocalDatabase {
            val postgres = EmbeddedPostgres.builder()
                .setDataDirectory(dir.resolve("postgres"))
                .setCleanDataDirectory(false)
                .start()
            postgres.postgresDatabase.connection.use { c ->
                val exists = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
                    .apply { setString(1, DATABASE) }
                    .executeQuery()
                    .next()
                if (!exists) c.createStatement().execute("CREATE DATABASE $DATABASE")
            }
            return LocalDatabase(postgres)
        }
    }
}
