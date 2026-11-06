package io.cmpose.backendtemplate

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.time.Duration.Companion.seconds

// By session, not by pg_locks.database: a wait on another transaction's row lock has no database of its own.
private const val WAITING_SESSIONS = """
    SELECT count(DISTINCT l.pid) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
    WHERE NOT l.granted AND a.datname = current_database()
"""

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

    /**
     * Waits (10 s at most) until [count] sessions of this test's database wait for a lock, held by the gate or by each
     * other; sessions of other databases on the shared server do not count.
     */
    suspend fun awaitWaiting(count: Int) = withTimeout(10.seconds) {
        while (waiting() < count) delay(10)
    }

    private fun waiting(): Int = connection.createStatement().use { statement ->
        statement.executeQuery(WAITING_SESSIONS).use {
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
