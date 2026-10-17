package io.cmpose.backendtemplate.local

import io.cmpose.backendtemplate.TestKeys
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalServerTest {
    private val dir = createTempDirectory("local-server").toFile()

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun `local server answers health on loopback and announces readiness`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val output = StringBuilder()
        LocalServer.start(dir, LocalSettings(port, "template-test"), jwks = TestKeys.jwkProvider, out = output::appendLine).use {
            val health = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
            assertEquals(200, health.responseCode)
            assertEquals("""{"status":"ok","version":"local"}""", health.inputStream.bufferedReader().readText())
            assertContains(output, "LOCAL_SERVER_READY port=$port")
        }
    }

    @Test fun `the local config serves files from the folder, as version local, with no CORS or client minimum`() {
        val config = localConfig(LocalSettings(18_001, "template-test"), "jdbc:postgresql://localhost:1/app", "u", "p", dir)
        assertEquals(dir.resolve("files").absolutePath, config.filesDir)
        assertEquals("local", config.appVersion)
        assertEquals(emptyList(), config.corsAllowedOrigins)
        assertEquals(null, config.minClientVersion)
        assertEquals(18_001, config.port)
        assertEquals("template-test", config.firebaseProjectId)
    }

    @Test fun `busy port stops with the holder in the message`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { held ->
            val error = assertFailsWith<LocalSetupException> {
                LocalServer.start(dir, LocalSettings(held.localPort, "p"), jwks = TestKeys.jwkProvider, out = {})
            }
            assertContains(error.message!!, "${held.localPort}")
        }
    }

    @Test fun `a failure after the database started releases it and is reported as a setup error`() {
        // A table Flyway does not know about makes migrate() refuse the non-empty schema.
        LocalDatabase.start(dir).use { db ->
            DriverManager.getConnection(db.jdbcUrl, db.user, db.password).use { c ->
                c.createStatement().execute("CREATE TABLE stray(v text)")
            }
        }
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val error = assertFailsWith<LocalSetupException> {
            LocalServer.start(dir, LocalSettings(port, "p"), jwks = TestKeys.jwkProvider, out = {})
        }
        assertTrue(error.message!!.isNotBlank())
        // The embedded PostgreSQL was stopped and its folder lock released, so the same folder starts again.
        LocalDatabase.start(dir).use { db -> assertTrue(db.jdbcUrl.endsWith("/app")) }
        assertTrue(PortCheck.isFree(port))
    }
}
