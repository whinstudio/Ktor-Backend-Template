package io.cmpose.backendtemplate

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.NetworkException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.security.KeyPairGenerator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real Firebase verification path, fed by a local key instead of Google's: every refusal is a 401 with the shared
 * error body and one "Token rejected: <reason>" log line that never quotes the token.
 */
class AuthTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private class Outcome(val response: HttpResponse, val body: String, val warnings: List<String>)

    private fun call(token: String?, jwkProvider: JwkProvider = TestKeys.jwkProvider): Outcome {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        lateinit var response: HttpResponse
        lateinit var body: String
        try {
            testApplication {
                application { module(authDeps(jwkProvider)) }
                response = client.me(token)
                body = response.bodyAsText()
            }
        } finally {
            root.detachAppender(appender)
        }
        val warnings = appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
        return Outcome(response, body, warnings)
    }

    private fun errorCode(body: String) =
        Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    /** A 401 with code UNAUTHORIZED, logged once with [reason] and without the token text. */
    private fun assertRejected(token: String, reason: String) {
        val outcome = call(token)
        assertEquals(HttpStatusCode.Unauthorized, outcome.response.status)
        assertEquals("UNAUTHORIZED", errorCode(outcome.body))
        assertEquals("Bearer", outcome.response.headers[HttpHeaders.WWWAuthenticate])
        assertEquals(listOf("Token rejected: $reason"), outcome.warnings.filter { it.startsWith("Token rejected") })
        outcome.warnings.forEach { assertFalse(it.contains(token), "log leaks the token: $it") }
    }

    @Test
    fun validTokenGives200() {
        val outcome = call(TestKeys.token(sub = "abc"))
        assertEquals(HttpStatusCode.OK, outcome.response.status)
        assertTrue(outcome.warnings.none { it.startsWith("Token rejected") })
    }

    /** An anonymous request is normal: a 401, but no warning (it is logged at DEBUG only). */
    @Test
    fun missingHeaderIs401WithoutWarning() {
        val outcome = call(null)
        assertEquals(HttpStatusCode.Unauthorized, outcome.response.status)
        assertEquals("UNAUTHORIZED", errorCode(outcome.body))
        assertEquals("Bearer", outcome.response.headers[HttpHeaders.WWWAuthenticate])
        assertEquals(emptyList(), outcome.warnings)
    }

    @Test
    fun nonBearerSchemeIs401() {
        val warnings = capturedLogs {
            testApplication {
                application { module(authDeps()) }
                val response = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Basic dTpw") }
                assertEquals(HttpStatusCode.Unauthorized, response.status)
            }
        }.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
        assertEquals(listOf("Token rejected: not a bearer token"), warnings.filter { it.startsWith("Token rejected") })
        warnings.forEach { assertFalse(it.contains("dTpw"), "log leaks the credentials: $it") }
    }

    @Test
    fun wrongAudienceIs401() = assertRejected(TestKeys.token(aud = "someone-else"), "wrong audience")

    @Test
    fun wrongIssuerIs401() =
        assertRejected(TestKeys.token(iss = "https://evil.example/${TestKeys.PROJECT_ID}"), "wrong issuer")

    @Test
    fun expiredIs401() = assertRejected(TestKeys.token(expiresInSec = -120), "expired")

    @Test
    fun unknownKidIs401() = assertRejected(TestKeys.token(kid = "gone"), "unknown kid")

    @Test
    fun algNoneIs401() = assertRejected(TestKeys.token(alg = "none"), "algorithm mismatch")

    @Test
    fun algHs256Is401() = assertRejected(TestKeys.token(alg = "HS256"), "algorithm mismatch")

    @Test
    fun emptySubIs401() = assertRejected(TestKeys.token(sub = ""), "no subject")

    @Test
    fun foreignSignatureIs401() {
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertRejected(TestKeys.token(signWith = stranger.private), "bad signature")
    }

    @Test
    fun garbageIs401() = assertRejected("not-a-jwt", "malformed token")

    @Test
    fun jwksUnreachableIs503() {
        val offline = object : JwkProvider {
            override fun get(keyId: String): Jwk = throw NetworkException("Cannot obtain jwks", IOException("down"))
        }
        val outcome = call(TestKeys.token(), offline)
        assertEquals(HttpStatusCode.ServiceUnavailable, outcome.response.status)
        assertEquals("AUTH_UNAVAILABLE", errorCode(outcome.body))
    }

    @Test
    fun errorBodyCarriesRequestId() = testApplication {
        application { module(authDeps()) }
        val response = client.get("/api/v1/me") { header("X-Request-Id", "req-401") }
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("req-401", error["requestId"]!!.jsonPrimitive.content)
        assertEquals("Unauthorized", error["message"]!!.jsonPrimitive.content)
    }
}
