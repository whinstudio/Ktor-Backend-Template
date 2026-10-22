package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.users.MeResponse
import io.cmpose.backendtemplate.users.ProfileUpdate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileRoutesTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    private val alice = TestKeys.token(sub = "alice")

    private fun withApp(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { module(authDeps()) }
        block(jsonClient())
    }

    private suspend fun HttpClient.profile(token: String, update: ProfileUpdate): HttpResponse =
        put("/api/v1/me/profile") { jsonBody(token, update) }

    private suspend fun HttpClient.displayName(token: String): String? = me(token).body<MeResponse>().displayName

    /** The `field` names of an error response's `fields`. */
    private suspend fun HttpResponse.errorFields(): List<String> =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["fields"]!!.jsonArray
            .map { it.jsonObject["field"]!!.jsonPrimitive.content }

    @Test
    fun profileSetsTheDisplayNameShownByMe() = withApp { client ->
        assertNull(client.displayName(alice))
        assertEquals(HttpStatusCode.NoContent, client.profile(alice, ProfileUpdate(displayName = " Ada ")).status)
        assertEquals("Ada", client.displayName(alice))
    }

    @Test
    fun blankDisplayNameClearsIt() = withApp { client ->
        client.profile(alice, ProfileUpdate(displayName = "Ada"))
        assertEquals(HttpStatusCode.NoContent, client.profile(alice, ProfileUpdate(displayName = "   ")).status)
        assertNull(client.displayName(alice))
    }

    @Test
    fun aMissingDisplayNameClearsIt() = withApp { client ->
        client.profile(alice, ProfileUpdate(displayName = "Ada"))
        val response = client.put("/api/v1/me/profile") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(client.displayName(alice))
    }

    @Test
    fun tooLongDisplayNameIs400WithFieldError() = withApp { client ->
        client.profile(alice, ProfileUpdate(displayName = "Ada"))
        val response = client.profile(alice, ProfileUpdate(displayName = "a".repeat(81)))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.errorCode())
        assertEquals(listOf("displayName"), response.errorFields())
        assertEquals("Ada", client.displayName(alice))
        assertEquals(HttpStatusCode.NoContent, client.profile(alice, ProfileUpdate(displayName = "a".repeat(80))).status)
    }

    @Test
    fun profileNeedsSignIn() = withApp { client ->
        val response = client.put("/api/v1/me/profile") {
            contentType(ContentType.Application.Json)
            setBody(ProfileUpdate(displayName = "Ada"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun unknownFieldIs400() = withApp { client ->
        val response = client.put("/api/v1/me/profile") {
            bearerAuth(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Ada","nickname":"A"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(client.displayName(alice))
    }
    // cmpose:sharing

    @Test
    fun profileRenamesThePersonalSpace() = withApp { client ->
        assertEquals(HttpStatusCode.NoContent, client.profile(alice, ProfileUpdate(personalSpaceName = " Ev ")).status)
        assertEquals("Ev", client.personalSpace(alice).name)
    }

    @Test
    fun invalidPersonalSpaceNameIs400() = withApp { client ->
        val response = client.profile(alice, ProfileUpdate(displayName = "Ada", personalSpaceName = ""))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.errorCode())
        assertEquals(listOf("personalSpaceName"), response.errorFields())
        assertNull(client.displayName(alice))
        val tooLong = client.profile(alice, ProfileUpdate(personalSpaceName = "a".repeat(81)))
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertEquals(listOf("personalSpaceName"), tooLong.errorFields())
    }

    @Test
    fun nullPersonalSpaceNameKeepsTheName() = withApp { client ->
        client.profile(alice, ProfileUpdate(personalSpaceName = "Ev"))
        assertEquals(HttpStatusCode.NoContent, client.profile(alice, ProfileUpdate(displayName = "Ada")).status)
        assertEquals("Ev", client.personalSpace(alice).name)
    }

    @Test
    fun aFailedPersonalSpaceRenameLeavesTheDisplayNameAsItWas() = withApp { client ->
        client.profile(alice, ProfileUpdate(displayName = "Old", personalSpaceName = "Ev"))
        // The database refuses this one name, so the rename fails after the display name was written.
        TestDb.execute("ALTER TABLE spaces ADD CONSTRAINT test_refused_name CHECK (name <> 'Refused') NOT VALID")
        try {
            val response = client.profile(alice, ProfileUpdate(displayName = "New", personalSpaceName = "Refused"))
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("INTERNAL", response.errorCode())
        } finally {
            TestDb.execute("ALTER TABLE spaces DROP CONSTRAINT test_refused_name")
        }
        assertEquals("Old", client.displayName(alice))
        assertEquals("Ev", client.personalSpace(alice).name)
    }
    // /cmpose:sharing
}
