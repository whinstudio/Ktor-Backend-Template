package io.cmpose.backendtemplate

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.time.Duration.Companion.seconds

/**
 * Holds [tables] locked against writes (reads still pass) on a database connection of its own until [close]: a
 * concurrent request then stops at its first insert into one of them, keeping the row locks it took before. That
 * lines requests up deterministically at the point where a race or a lock cycle would happen.
 */
class InsertGate(vararg tables: String) : AutoCloseable {
    private val connection = TestDb.configFor("postgres").let {
        DriverManager.getConnection(it.databaseUrl, it.databaseUser, it.databasePassword)
    }.apply { autoCommit = false }

    init {
        try {
            connection.createStatement().use { it.execute("LOCK TABLE ${tables.joinToString()} IN EXCLUSIVE MODE") }
        } catch (e: SQLException) {
            connection.close()
            throw e
        }
    }

    /** Waits (10 s at most) until [count] database sessions wait for a lock, held by the gate or by each other. */
    suspend fun awaitWaiting(count: Int) = withTimeout(10.seconds) {
        while (waiting() < count) delay(10)
    }

    private fun waiting(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted").use {
            it.next()
            it.getInt(1)
        }
    }

    /** Opens the gate: the held inserts go on. Closing it again does nothing. */
    override fun close() {
        if (connection.isClosed) return
        connection.use { it.commit() }
    }
}
