package io.cmpose.backendtemplate.local

import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalDatabaseTest {
    private val dir = createTempDirectory("local-db").toFile()

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun `rows survive a restart on the same folder`() {
        LocalDatabase.start(dir).use { db ->
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { c ->
                c.createStatement().execute("CREATE TABLE marker(v text); INSERT INTO marker VALUES ('kept')")
            }
        }
        LocalDatabase.start(dir).use { db ->
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { c ->
                val rs = c.createStatement().executeQuery("SELECT v FROM marker")
                assertTrue(rs.next())
                assertEquals("kept", rs.getString(1))
            }
        }
    }

    @Test fun `the app database is used and creating it twice is harmless`() {
        LocalDatabase.start(dir).use { }
        LocalDatabase.start(dir).use { db -> assertTrue(db.jdbcUrl.endsWith("/app")) }
    }
}
