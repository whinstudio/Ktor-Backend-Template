package io.cmpose.backendtemplate

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun healthOk() = testApplication {
        application { module(testDeps()) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok","version":"test-1"}""", response.bodyAsText())
    }

    @Test
    fun readyWithoutDb503() = testApplication {
        application { module(testDeps(db = null)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
    }

    @Test
    fun versionHeaderOnEveryResponse() = testApplication {
        application { module(testDeps()) }
        assertEquals("test-1", client.get("/health").headers["X-App-Version"])
        val missing = client.get("/nope")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("test-1", missing.headers["X-App-Version"])
    }
}
