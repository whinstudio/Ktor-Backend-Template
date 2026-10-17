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
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals

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

/** [owner] creates a space named [name]; each of [members] joins it through an invite with its role. */
suspend fun HttpClient.sharedSpace(owner: String, name: String, vararg members: Pair<String, Role>): SpaceResponse {
    val space = createSpace(owner, name)
    members.forEach { (token, role) ->
        assertEquals(HttpStatusCode.OK, accept(token, invite(owner, space.id, role).token).status)
    }
    return space
}
