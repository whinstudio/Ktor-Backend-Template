package io.cmpose.backendtemplate

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorsTest {
    private suspend fun ApplicationTestBuilder.preflight(origin: String, method: String = "GET"): HttpResponse =
        client.options("/health") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, method)
            header(HttpHeaders.AccessControlRequestHeaders, "authorization")
        }

    @Test
    fun emptyOriginsMeansNoCors() = testApplication {
        application { module(testDeps()) }
        val response = preflight("https://a.test")
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertFalse(response.status.isSuccess(), response.status.toString())
        assertNull(client.get("/health") { header(HttpHeaders.Origin, "https://a.test") }
            .headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun allowedOriginPassesPreflight() = testApplication {
        application { module(testDeps(config = testConfig().copy(corsAllowedOrigins = listOf("https://a.test")))) }
        val response = preflight("https://a.test")
        assertTrue(response.status.isSuccess(), response.status.toString())
        assertEquals("https://a.test", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(response.headers[HttpHeaders.AccessControlAllowHeaders]!!.contains(HttpHeaders.Authorization))
        val simple = client.get("/health") { header(HttpHeaders.Origin, "https://a.test") }
        assertEquals("https://a.test", simple.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(simple.headers[HttpHeaders.AccessControlAllowCredentials])
        assertTrue(simple.headers[HttpHeaders.AccessControlExposeHeaders]!!.contains("X-App-Version"))
        assertTrue(simple.headers[HttpHeaders.AccessControlExposeHeaders]!!.contains(HttpHeaders.RetryAfter))
    }

    @Test
    fun schemeMustMatch() = testApplication {
        application { module(testDeps(config = testConfig().copy(corsAllowedOrigins = listOf("https://a.test")))) }
        assertNull(preflight("http://a.test").headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun otherOriginIsRefused() = testApplication {
        application { module(testDeps(config = testConfig().copy(corsAllowedOrigins = listOf("https://a.test")))) }
        val response = preflight("https://b.test")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        val simple = client.get("/health") { header(HttpHeaders.Origin, "https://b.test") }
        assertNull(simple.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun unlistedMethodIsRefused() = testApplication {
        application { module(testDeps(config = testConfig().copy(corsAllowedOrigins = listOf("https://a.test")))) }
        val response = preflight("https://a.test", method = "TRACE")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
