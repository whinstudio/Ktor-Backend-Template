package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.plugins.Cidr
import io.cmpose.backendtemplate.plugins.parseIp
import io.cmpose.backendtemplate.users.Users
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The test engine's socket peer is the loopback address (it reports `localhost`). */
class RateLimitTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private fun limited(perMinute: Int = 3, trusted: List<String> = listOf("172.16.0.0/12")) =
        testConfig().copy(rateLimitPerMinute = perMinute, trustedProxies = trusted)

    @Test
    fun fourthAnonymousCallIs429() = testApplication {
        application { module(testDeps(config = limited())) }
        repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/health").status) }
        val response = client.get("/health") { header("X-Request-Id", "req-429") }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val retryAfter = assertNotNull(response.headers[HttpHeaders.RetryAfter])
        assertTrue(retryAfter.toLong() > 0)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("RATE_LIMITED", error["code"]!!.jsonPrimitive.content)
        assertEquals("req-429", error["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun spoofedForwardedForIgnored() = testApplication {
        application { module(testDeps(config = limited(trusted = listOf("10.9.9.9/32")))) }
        val statuses = (1..4).map { i -> client.get("/health") { header("X-Forwarded-For", "203.0.113.$i") }.status }
        assertEquals(List(3) { HttpStatusCode.OK } + HttpStatusCode.TooManyRequests, statuses)
    }

    @Test
    fun trustedProxyForwardedForUsed() = testApplication {
        application { module(testDeps(config = limited(trusted = listOf("127.0.0.1/32")))) }
        val statuses = (1..4).map { i -> client.get("/health") { header("X-Forwarded-For", "203.0.113.$i") }.status }
        assertEquals(List(4) { HttpStatusCode.OK }, statuses)
        // One of those clients, again: its own bucket fills up.
        repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/health") { header("X-Forwarded-For", "203.0.113.1") }.status) }
        val fourth = client.get("/health") { header("X-Forwarded-For", "203.0.113.1") }
        assertEquals(HttpStatusCode.TooManyRequests, fourth.status)
    }

    @Test
    fun forgedLeftmostHopIgnoredBehindTrustedProxy() = testApplication {
        application { module(testDeps(config = limited(trusted = listOf("127.0.0.1/32")))) }
        // The proxy appends the real client (198.51.100.7) to whatever the client claimed.
        val statuses = (1..4).map { i ->
            client.get("/health") { header("X-Forwarded-For", "203.0.113.$i, 198.51.100.7") }.status
        }
        assertEquals(List(3) { HttpStatusCode.OK } + HttpStatusCode.TooManyRequests, statuses)
    }

    @Test
    fun authenticatedCallerIsKeyedByIdentity() = testApplication {
        application { module(testDeps(TestDb.database, limited(), TestKeys.jwkProvider)) }
        val alice = TestKeys.token(sub = "alice")
        repeat(3) { assertEquals(HttpStatusCode.OK, client.me(alice).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.me(alice).status)
        // Same address, another user: a bucket of their own, and the anonymous bucket is untouched too.
        assertEquals(HttpStatusCode.OK, client.me(TestKeys.token(sub = "bob")).status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    /** The limiter keys on the verified token alone: a refused request never reaches the database. */
    @Test
    fun limitIsReachedWithoutTouchingTheDatabase() = testApplication {
        application { module(testDeps(TestDb.database, limited(), TestKeys.jwkProvider)) }
        val carol = TestKeys.token(sub = "carol")
        // /health never resolves the user, so only the limiter sees the token.
        repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/health") { bearerAuth(carol) }.status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.me(carol).status)
        assertEquals(0L, transaction(TestDb.database) { Users.selectAll().count() })
        // The token's own bucket, not the address: an anonymous call still passes.
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun invalidTokenFallsBackToTheAddress() = testApplication {
        application { module(testDeps(TestDb.database, limited(), TestKeys.jwkProvider)) }
        repeat(3) { i -> assertEquals(HttpStatusCode.OK, client.get("/health") { bearerAuth("garbage-$i") }.status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/health").status)
    }

    @Test
    fun rateLimitKeyNeverLogsTheSubject() {
        val subject = "secret-uid-77"
        val logs = capturedLogs {
            testApplication {
                application { module(testDeps(TestDb.database, limited(perMinute = 1), TestKeys.jwkProvider)) }
                val token = TestKeys.token(sub = subject)
                client.me(token)
                client.me(token)
            }
        }
        logs.forEach { assertFalse(it.fullText().contains(subject), "log leaks the subject: ${it.fullText()}") }
    }

    @Test
    fun sensitiveLimiterIsStricter() = testApplication {
        application {
            module(testDeps(config = testConfig().copy(rateLimitSensitivePerMinute = 2)))
            testRoutes()
        }
        repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/test/sensitive").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/test/sensitive").status)
        assertEquals(HttpStatusCode.OK, client.get("/test/ping").status)
    }

    @Test
    fun cidrMatching() {
        val docker = Cidr.parse("172.16.0.0/12")
        assertTrue(docker.contains(parseIp("172.16.0.1")!!))
        assertTrue(docker.contains(parseIp("172.31.255.255")!!))
        assertFalse(docker.contains(parseIp("172.32.0.1")!!))
        assertFalse(docker.contains(parseIp("::1")!!))
        assertTrue(Cidr.parse("fd00::/8").contains(parseIp("fd12::1")!!))
        assertTrue(Cidr.parse("10.0.0.5").contains(parseIp("10.0.0.5")!!))
        assertTrue(Cidr.parse("0.0.0.0/0").contains(parseIp("8.8.8.8")!!))
    }

    @Test
    fun ipParsingNeverResolvesNames() {
        assertNull(parseIp("example.com"))
        assertNull(parseIp("localhost"))
        assertNull(parseIp("256.1.1.1"))
        assertNull(parseIp("1.2.3"))
        assertNull(parseIp("unknown"))
        assertNotNull(parseIp("localhost", allowLocalhost = true))
        assertEquals("2001:db8:0:0:0:0:0:1", parseIp("[2001:db8::1]")!!.hostAddress)
    }

    @Test
    fun invalidTrustedProxyFailsAtStartup() {
        listOf("10.0.0.0/33", "proxy.internal", "10.0.0.0/x", "1.2.3.4/8/9").forEach { bad ->
            assertTrue(runCatching { Cidr.parse(bad) }.exceptionOrNull() is IllegalArgumentException, bad)
        }
    }
}
