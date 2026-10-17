package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.db.connectDatabase
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nothing the server logs — messages, MDC or exceptions, from any logger — carries a bearer token, an email, a
 * secret path segment or SQL text. Ported from the CMP server's CallLogRedactionTest and DbErrorLogTest. The embedded
 * PostgreSQL's own server log (forwarded under `io.zonky`) is the database's log, not the app's, and exists only in
 * tests; it is left out.
 */
class LogRedactionTest {
    private val subject = "secret-uid-42"
    private val email = "person@private.example"

    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private fun appLogs(block: () -> Unit) = capturedLogs(block).filterNot { it.loggerName.startsWith("io.zonky") }

    private fun assertNotLogged(logs: List<String>, vararg secrets: String) {
        logs.forEach { line -> secrets.forEach { assertFalse(line.contains(it), "log leaks [$it]: $line") } }
    }

    @Test
    fun tokensAndEmailsNeverReachTheLog() {
        val valid = TestKeys.token(sub = subject, email = email)
        val expired = TestKeys.token(sub = subject, email = email, expiresInSec = -120)
        val logs = appLogs {
            testApplication {
                application { module(authDeps()) }
                assertEquals(HttpStatusCode.OK, client.me(valid).status)
                assertEquals(HttpStatusCode.Unauthorized, client.me(expired).status)
                assertEquals(HttpStatusCode.Unauthorized, client.me("$valid.tampered").status)
            }
        }.map { it.fullText() }
        assertTrue(logs.any { it.contains("request completed") }, "the requests are still logged")
        assertNotLogged(logs, valid, expired, email, subject)
    }

    // cmpose:sharing
    @Test
    fun inviteCodesNeverReachTheLog() {
        val code = "invite-code-s3cr3t"
        val logs = appLogs {
            testApplication {
                application { module(authDeps()) }
                client.get("/api/v1/invites/$code")
                client.post("/api/v1/invites/$code/accept") { bearerAuth(TestKeys.token()) }
            }
        }.map { it.fullText() }
        assertTrue(logs.any { it.contains("/api/v1/invites/{token}/accept") }, logs.joinToString("\n"))
        assertNotLogged(logs, code)
    }
    // /cmpose:sharing

    @Test
    fun databaseErrorLogsNeitherSqlNorIdentity() {
        lateinit var body: String
        lateinit var status: HttpStatusCode
        val events = appLogs {
            testApplication {
                application { module(testDeps(RejectingDb.database, jwkProvider = TestKeys.jwkProvider)) }
                val response = client.me(TestKeys.token(sub = subject, email = email))
                status = response.status
                body = response.bodyAsText()
            }
        }
        assertEquals(HttpStatusCode.InternalServerError, status)
        assertFalse(body.contains(subject) || body.contains(email) || body.contains("INSERT"), body)
        val logs = events.map { it.fullText() }
        assertTrue(logs.any { it.startsWith("Unhandled error") && it.contains("SQLState 23514") }, logs.joinToString("\n"))
        assertNotLogged(logs, subject, email, "INSERT", "user_identities", "Failing row")
    }

    /**
     * A migrated database whose `user_identities` refuses every row. The CHECK violation's detail quotes the whole row
     * ("Failing row contains (...)"), so the error carries the caller's subject and email.
     */
    private object RejectingDb {
        val database: Database by lazy {
            TestDb.createEmptyDatabase("rejecting_identities")
            connectDatabase(TestDb.configFor("rejecting_identities")).also { db ->
                transaction(db) { exec("ALTER TABLE user_identities ADD CONSTRAINT refuse_all CHECK (false)") }
            }
        }
    }
}
