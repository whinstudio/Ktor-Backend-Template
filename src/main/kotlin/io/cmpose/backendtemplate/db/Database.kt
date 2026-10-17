package io.cmpose.backendtemplate.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.cmpose.backendtemplate.config.AppConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.sql.Database

/** A Hikari pool of `dbPoolSize` connections, migrated by Flyway (`db/migration`) before anything uses it. */
fun connectDatabase(config: AppConfig): Database {
    val source = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            maximumPoolSize = config.dbPoolSize
        }
    )
    try {
        Flyway.configure().dataSource(source).load().migrate()
    } catch (e: FlywayException) {
        source.close() // a failed migration must not leave the pool's connections open
        throw e
    }
    return Database.connect(source)
}
