package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.sharing.InviteRequest
import io.cmpose.backendtemplate.sharing.InviteResponse
import io.cmpose.backendtemplate.sharing.InviteToken
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.SpaceDraft
import io.cmpose.backendtemplate.sharing.SpaceResponse
import io.cmpose.backendtemplate.users.MeResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Our own user id for [token] (creating the user on first sight). */
suspend fun HttpClient.userId(token: String): String = me(token).body<MeResponse>().id

suspend fun HttpClient.spaces(token: String): List<SpaceResponse> =
    get("/api/v1/spaces") { bearerAuth(token) }.body()

suspend fun HttpClient.createSpace(token: String, name: String): SpaceResponse {
    val response = post("/api/v1/spaces") { jsonBody(token, SpaceDraft(name)) }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

suspend fun HttpClient.personalSpace(token: String): SpaceResponse = spaces(token).single { it.personal }

suspend fun HttpClient.inviteResponse(token: String, spaceId: String, role: Role): HttpResponse =
    post("/api/v1/spaces/$spaceId/invites") { jsonBody(token, InviteRequest(role)) }

suspend fun HttpClient.invite(token: String, spaceId: String, role: Role): InviteResponse {
    val response = inviteResponse(token, spaceId, role)
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

suspend fun HttpClient.preview(token: String, inviteToken: String): HttpResponse =
    post("/api/v1/invites/preview") { jsonBody(token, InviteToken(inviteToken)) }

suspend fun HttpClient.accept(token: String, inviteToken: String): HttpResponse =
    post("/api/v1/invites/accept") { jsonBody(token, InviteToken(inviteToken)) }

suspend fun HttpClient.pendingInvites(token: String, spaceId: String): HttpResponse =
    get("/api/v1/spaces/$spaceId/invites") { bearerAuth(token) }

suspend fun HttpClient.revoke(token: String, spaceId: String, inviteId: String): HttpResponse =
    delete("/api/v1/spaces/$spaceId/invites/$inviteId") { bearerAuth(token) }

/** [owner] creates a space named [name]; each of [members] joins it through an invite with its role. */
suspend fun HttpClient.sharedSpace(owner: String, name: String, vararg members: Pair<String, Role>): SpaceResponse {
    val space = createSpace(owner, name)
    members.forEach { (token, role) ->
        assertEquals(HttpStatusCode.OK, accept(token, invite(owner, space.id, role).token).status)
    }
    return space
}

/** A 409 `QUOTA_EXCEEDED` naming [quota]. */
suspend fun HttpResponse.assertQuotaExceeded(quota: String) {
    assertEquals(HttpStatusCode.Conflict, status)
    val error = Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject
    assertEquals("QUOTA_EXCEEDED", error["code"]!!.jsonPrimitive.content)
    assertEquals(quota, error["quota"]?.jsonPrimitive?.content)
}

/**
 * Holds [tables] locked against writes (reads still pass) on a database connection of its own until [close]: a
 * concurrent request then stops at its first insert into one of them, keeping the row locks it took before. That
 * lines requests up deterministically at the point where a race or a lock cycle would happen.
 */
class InsertGate(vararg tables: String) : AutoCloseable {
    private val connection = TestDb.configFor("postgres").let {
        DriverManager.getConnection(it.databaseUrl, it.databaseUser, it.databasePassword)
    }.apply { autoCommit = false }

    init {
        try {
            connection.createStatement().use { it.execute("LOCK TABLE ${tables.joinToString()} IN EXCLUSIVE MODE") }
        } catch (e: SQLException) {
            connection.close()
            throw e
        }
    }

    /** Waits (10 s at most) until [count] database sessions wait for a lock, held by the gate or by each other. */
    suspend fun awaitWaiting(count: Int) = withTimeout(10.seconds) {
        while (waiting() < count) delay(10)
    }

    private fun waiting(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted").use {
            it.next()
            it.getInt(1)
        }
    }

    /** Opens the gate: the held inserts go on. Closing it again does nothing. */
    override fun close() {
        if (connection.isClosed) return
        connection.use { it.commit() }
    }
}
