package io.cmpose.backendtemplate

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.cmpose.backendtemplate.auth.VerifiedIdentity
import io.cmpose.backendtemplate.users.UserIdentities
import io.cmpose.backendtemplate.users.UserRepository
import io.cmpose.backendtemplate.users.Users
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The first valid token creates our own user (a UUID) and maps the Firebase uid to it; later tokens reuse it. */
class UserBootstrapTest {
    @Serializable
    private data class Me(val id: String, val email: String?)

    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private fun counts() = transaction(TestDb.database) {
        Users.selectAll().count() to UserIdentities.selectAll().count()
    }

    @Test
    fun firstRequestCreatesUserOnce() = testApplication {
        application { module(authDeps()) }
        val client = jsonClient()
        val first = client.me(TestKeys.token(sub = "alice")).body<Me>()
        val second = client.me(TestKeys.token(sub = "alice")).body<Me>()
        assertEquals(first.id, second.id)
        assertEquals(1L to 1L, counts())
    }

    @Test
    fun ownIdNotFirebaseUid() = testApplication {
        application { module(authDeps()) }
        val response = jsonClient().me(TestKeys.token(sub = "alice", email = "alice@example.com"))
        assertEquals(HttpStatusCode.OK, response.status)
        val me = response.body<Me>()
        assertNotEquals("alice", me.id)
        UUID.fromString(me.id)
        assertEquals("alice@example.com", me.email)
    }

    @Test
    fun differentSubjectsGetDifferentUsers() = testApplication {
        application { module(authDeps()) }
        val client = jsonClient()
        val alice = client.me(TestKeys.token(sub = "alice")).body<Me>()
        val bob = client.me(TestKeys.token(sub = "bob")).body<Me>()
        assertNotEquals(alice.id, bob.id)
        assertEquals(2L to 2L, counts())
    }

    @Test
    fun emailFollowsTheLatestToken() = testApplication {
        application { module(authDeps()) }
        val client = jsonClient()
        client.me(TestKeys.token(sub = "alice", email = "old@example.com"))
        val me = client.me(TestKeys.token(sub = "alice", email = "new@example.com")).body<Me>()
        assertEquals("new@example.com", me.email)
    }

    @Test
    fun unverifiedEmailIsNeverShownUntilVerified() = testApplication {
        application { module(authDeps()) }
        val client = jsonClient()
        val unverified = TestKeys.token(sub = "alice", email = "alice@example.com", emailVerified = false)
        assertNull(client.me(unverified).body<Me>().email)
        val verified = client.me(TestKeys.token(sub = "alice", email = "alice@example.com")).body<Me>()
        assertEquals("alice@example.com", verified.email)
    }

    @Test
    fun unverifiedEmailClearsTheStoredOne() = testApplication {
        application { module(authDeps()) }
        val client = jsonClient()
        client.me(TestKeys.token(sub = "alice", email = "old@example.com"))
        val me = client.me(TestKeys.token(sub = "alice", email = "taken@example.com", emailVerified = false)).body<Me>()
        assertNull(me.email)
        assertNull(transaction(TestDb.database) { UserIdentities.selectAll().single()[UserIdentities.email] })
    }

    @Test
    fun concurrentFirstRequestsCreateOneUser() {
        val users = UserRepository(TestDb.database)
        val identity = VerifiedIdentity("firebase", "racer", email = null, emailVerified = false, name = null)
        val pool = Executors.newFixedThreadPool(8)
        val ids = try {
            pool.invokeAll(List(8) { Callable { users.resolveOrCreate(identity) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, ids.toSet().size)
        assertEquals(1L to 1L, counts())
    }

    @Test
    fun requestLogCarriesOwnUserId() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        lateinit var me: Me
        try {
            testApplication {
                application { module(authDeps()) }
                me = jsonClient().get("/api/v1/me") {
                    bearerAuth(TestKeys.token(sub = "alice"))
                    header("X-Request-Id", "req-me")
                }.body()
            }
        } finally {
            root.detachAppender(appender)
        }
        val line = appender.list.single { it.mdcPropertyMap["requestId"] == "req-me" }
        assertEquals(me.id, line.mdcPropertyMap["userId"])
    }
}
