package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.config.AppConfig
import io.cmpose.backendtemplate.plugins.ClientVersion
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientVersionTest {
    private fun withApp(minVersion: String?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(testDeps(config = testConfig().copy(minClientVersion = minVersion)))
            testRoutes()
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.ping(version: String?, path: String = "/test/ping"): HttpResponse =
        client.get(path) { if (version != null) header("X-Client-Version", version) }

    @Test
    fun olderClientGets426() = withApp("2.0.0") {
        val response = client.get("/test/ping") {
            header("X-Client-Version", "1.9.9")
            header("X-Request-Id", "req-426")
        }
        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("CLIENT_UPGRADE_REQUIRED", error["code"]!!.jsonPrimitive.content)
        assertEquals("2.0.0", error["minVersion"]!!.jsonPrimitive.content)
        assertEquals("req-426", error["requestId"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun sameOrNewerVersionPasses() = withApp("2.0.0") {
        assertEquals(HttpStatusCode.OK, ping("2.0.0").status)
        assertEquals(HttpStatusCode.OK, ping("2.0.1").status)
        assertEquals(HttpStatusCode.OK, ping("10.0.0").status)
    }

    @Test
    fun missingHeaderPasses() = withApp("2.0.0") {
        assertEquals(HttpStatusCode.OK, ping(null).status)
    }

    @Test
    fun malformedVersionIs400() = withApp("2.0.0") {
        listOf("abc", "2.0", "2.0.0.1", "v2.0.0", "02.0.0", "-1.0.0", "").forEach { bad ->
            val response = ping(bad)
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
            assertTrue(response.bodyAsText().contains("BAD_REQUEST"), bad)
        }
    }

    @Test
    fun healthAndReadyAreExempt() = withApp("2.0.0") {
        assertEquals(HttpStatusCode.OK, ping("1.0.0", "/health").status)
        // No database here, so /ready is 503 — but not 426.
        assertEquals(HttpStatusCode.ServiceUnavailable, ping("1.0.0", "/ready").status)
    }

    @Test
    fun noMinimumMeansNoCheck() = withApp(null) {
        assertEquals(HttpStatusCode.OK, ping("0.0.1").status)
        assertEquals(HttpStatusCode.OK, ping("abc").status)
        assertEquals(HttpStatusCode.OK, ping(null).status)
    }

    @Test
    fun comparisonIsNumeric() = withApp("1.10.0") {
        assertEquals(HttpStatusCode.UpgradeRequired, ping("1.9.9").status)
        assertEquals(HttpStatusCode.OK, ping("1.10.0").status)
        assertEquals(HttpStatusCode.OK, ping("1.11.0").status)
    }

    @Test
    fun versionParsing() {
        assertEquals(ClientVersion(1, 2, 3), ClientVersion.parse("1.2.3"))
        assertNull(ClientVersion.parse("1.2.3-beta"))
        assertNull(ClientVersion.parse("99999999999.0.0"))
        assertTrue(ClientVersion(1, 10, 0) > ClientVersion(1, 9, 99))
    }

    @Test
    fun malformedMinimumFailsAtStartup() {
        val config: AppConfig = testConfig().copy(minClientVersion = "two")
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application { module(testDeps(config = config)) }
                client.get("/health")
            }
        }
    }
}
