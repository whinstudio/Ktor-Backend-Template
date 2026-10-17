package io.cmpose.backendtemplate

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecurityHeadersTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private fun assertSecurityHeaders(response: HttpResponse) {
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("default-src 'none'", response.headers["Content-Security-Policy"])
    }

    @Test
    fun everyResponseHasSecurityHeaders() = testApplication {
        application { module(testDeps()) }
        assertSecurityHeaders(client.get("/health"))
        assertSecurityHeaders(client.get("/nope"))
        assertNull(client.get("/health").headers[HttpHeaders.CacheControl])
    }

    @Test
    fun authenticatedResponseIsNotStored() = testApplication {
        application { module(authDeps()) }
        val response = client.me(TestKeys.token())
        assertEquals(HttpStatusCode.OK, response.status)
        assertSecurityHeaders(response)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun refusedTokenStillGetsSecurityHeaders() = testApplication {
        application { module(authDeps()) }
        val response = client.me("not-a-jwt")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertSecurityHeaders(response)
    }

    @Test
    fun noServerVersionHeader() = testApplication {
        application { module(testDeps()) }
        assertNull(client.get("/health").headers[HttpHeaders.Server])
    }
}
