package io.cmpose.backendtemplate

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import io.cmpose.backendtemplate.auth.FirebaseTokenVerifier
import io.cmpose.backendtemplate.auth.UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Made-up kids cannot spend the key provider's fetch budget: one lookup of an unknown kid per interval, globally. */
class UnknownKidTest {
    @BeforeTest
    fun clean() {
        TestDb.reset()
    }

    /** Knows [TestKeys]' key under each of [kids] and records every kid it is asked for. */
    private class CountingProvider(private val kids: Set<String> = setOf(TestKeys.KID)) : JwkProvider {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun get(keyId: String): Jwk {
            asked += keyId
            if (keyId !in kids) throw SigningKeyNotFoundException("unknown kid", null)
            return TestKeys.jwkProvider.get(TestKeys.KID)
        }
    }

    private fun verifier(jwks: JwkProvider, clock: () -> Long) =
        FirebaseTokenVerifier(jwks, TestKeys.PROJECT_ID, 60, Dispatchers.Unconfined, clock)

    @Test
    fun floodOfMadeUpKidsLooksUpOnceAndRealUsersStillGet200() = testApplication {
        val jwks = CountingProvider()
        application { module(authDeps(jwks)) }
        assertEquals(HttpStatusCode.OK, client.me(TestKeys.token(sub = "alice")).status)
        repeat(20) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.me(TestKeys.token(sub = "mallory", kid = "fake-$i")).status)
        }
        assertEquals(HttpStatusCode.OK, client.me(TestKeys.token(sub = "bob")).status)
        assertEquals(1, jwks.asked.count { it.startsWith("fake-") }, "${jwks.asked}")
    }

    @Test
    fun unknownKidIsLookedUpAgainAfterTheInterval() = runBlocking {
        val jwks = CountingProvider()
        var now = 1_000_000L
        val verifier = verifier(jwks) { now }
        assertNull(verifier.verify(TestKeys.token(kid = "fake-1")))
        assertNull(verifier.verify(TestKeys.token(kid = "fake-2")))
        now += UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS - 1
        assertNull(verifier.verify(TestKeys.token(kid = "fake-3")))
        assertEquals(listOf("fake-1"), jwks.asked.toList())
        now += 1
        assertNull(verifier.verify(TestKeys.token(kid = "fake-4")))
        assertEquals(listOf("fake-1", "fake-4"), jwks.asked.toList())
    }

    @Test
    fun aFoundKidFreesTheSlotForTheNextNewKid() = runBlocking {
        val jwks = CountingProvider(setOf(TestKeys.KID, "rotated"))
        val verifier = verifier(jwks) { 1_000_000L }
        assertNotNull(verifier.verify(TestKeys.token()))
        assertNotNull(verifier.verify(TestKeys.token(kid = "rotated")))
        assertNotNull(verifier.verify(TestKeys.token()))
        assertEquals(listOf(TestKeys.KID, "rotated", TestKeys.KID), jwks.asked.toList())
    }
}
