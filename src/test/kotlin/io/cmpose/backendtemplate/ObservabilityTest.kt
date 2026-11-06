package io.cmpose.backendtemplate

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.cmpose.backendtemplate.plugins.redactedPath
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ObservabilityTest {
    @Test
    fun requestIdEchoed() = testApplication {
        application { module(testDeps()) }
        val response = client.get("/health") { header("X-Request-Id", "abc") }
        assertEquals("abc", response.headers["X-Request-Id"])
    }

    @Test
    fun requestIdGenerated() = testApplication {
        application { module(testDeps()) }
        val id = client.get("/health").headers["X-Request-Id"]
        assertNotNull(id)
        assertFalse(id.isBlank())
    }

    @Test
    fun requestIdWithUnsafeCharactersReplaced() = testApplication {
        application { module(testDeps()) }
        val forged = "abc\"}{\"level\":\"ERROR"
        val id = client.get("/health") { header("X-Request-Id", forged) }.headers["X-Request-Id"]
        assertNotNull(id)
        assertNotEquals(forged, id)
        assertFalse(id.isBlank())
    }

    @Test
    fun requestLogCarriesMdcFields() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            testApplication {
                application { module(testDeps()) }
                // cmpose:sharing-off: client.get("/api/v1/nothing?x=secret-code") { header("X-Request-Id", "req-1") }
                client.get("/api/v1/invites/secret-code?x=1") { header("X-Request-Id", "req-1") }
            }
        } finally {
            root.detachAppender(appender)
        }
        val line = appender.list.single { it.mdcPropertyMap["requestId"] == "req-1" }
        val mdc = line.mdcPropertyMap
        assertEquals("GET", mdc["method"])
        // cmpose:sharing-off: assertEquals("/api/v1/nothing", mdc["path"])
        assertEquals("/api/v1/invites/{invite}", mdc["path"])
        assertEquals("404", mdc["status"])
        assertNotNull(mdc["durationMs"]?.toLongOrNull())
        assertFalse(line.formattedMessage.contains("secret-code"))
    }

    @Test
    fun pathRedaction() {
        // cmpose:sharing
        assertEquals("/api/v1/invites/{invite}/accept", redactedPath("/api/v1/invites/t1/accept"))
        assertEquals("/api/v1/spaces/abc/members/{userId}", redactedPath("/api/v1/spaces/abc/members/u1"))
        assertEquals("/api/v1/spaces/abc/members", redactedPath("/api/v1/spaces/abc/members"))
        assertEquals("/api/v1/invites/preview", redactedPath("/api/v1/invites/preview"))
        assertEquals("/api/v1/invites/accept", redactedPath("/api/v1/invites/accept"))
        val spaceId = "0b6f3c1e-8a52-4d7b-9f0e-2c4d6e8a1b3c"
        assertEquals(
            "/api/v1/spaces/$spaceId/invites/{invite}",
            redactedPath("/api/v1/spaces/$spaceId/invites/5e7a9c2b-1d3f-4a6b-8c0e-7f9a1b3d5c7e"),
        )
        assertEquals(
            "/api/v1/spaces/$spaceId/members/{userId}",
            redactedPath("/api/v1/spaces/$spaceId/members/u-123"),
        )
        // /cmpose:sharing
        assertEquals("/api/v1/files/abc", redactedPath("/api/v1/files/abc"))
        assertEquals("/health", redactedPath("/health"))
    }
}
