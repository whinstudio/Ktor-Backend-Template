package io.cmpose.backendtemplate.local

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalSettingsTest {
    private val dir = createTempDirectory("local-settings").toFile()

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun `missing config file names the file and the key to add`() {
        val error = assertFailsWith<LocalSetupException> { LocalSettings.read(dir) }
        assertContains(error.message!!, "config.properties")
        assertContains(error.message!!, "firebaseProjectId")
    }

    @Test fun `blank project id is rejected`() {
        dir.resolve("config.properties").writeText("firebaseProjectId=\n")
        assertFailsWith<LocalSetupException> { LocalSettings.read(dir) }
    }

    @Test fun `port defaults to 18001`() {
        dir.resolve("config.properties").writeText("firebaseProjectId=template-test\n")
        assertEquals(LocalSettings(port = 18001, firebaseProjectId = "template-test"), LocalSettings.read(dir))
    }

    @Test fun `port can be overridden`() {
        dir.resolve("config.properties").writeText("firebaseProjectId=p\nport=18005\n")
        assertEquals(18005, LocalSettings.read(dir).port)
    }

    @Test fun `a port outside 1 to 65535 is rejected with its value`() {
        listOf("0", "65536", "99999", "-1").forEach { value ->
            dir.resolve("config.properties").writeText("firebaseProjectId=p\nport=$value\n")
            val error = assertFailsWith<LocalSetupException>(value) { LocalSettings.read(dir) }
            assertContains(error.message!!, "port=$value")
        }
    }

    @Test fun `a non-numeric port is rejected with its value`() {
        dir.resolve("config.properties").writeText("firebaseProjectId=p\nport=abc\n")
        val error = assertFailsWith<LocalSetupException> { LocalSettings.read(dir) }
        assertContains(error.message!!, "port=abc")
    }

    @Test fun `the edge ports are accepted`() {
        listOf(1, 65535).forEach { port ->
            dir.resolve("config.properties").writeText("firebaseProjectId=p\nport=$port\n")
            assertEquals(port, LocalSettings.read(dir).port)
        }
    }
}
