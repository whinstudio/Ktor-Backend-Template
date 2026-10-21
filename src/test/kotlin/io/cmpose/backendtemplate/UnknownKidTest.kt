package io.cmpose.backendtemplate

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.NetworkException
import com.auth0.jwk.SigningKeyNotFoundException
import io.cmpose.backendtemplate.auth.AuthUnavailableException
import io.cmpose.backendtemplate.auth.FirebaseTokenVerifier
import io.cmpose.backendtemplate.auth.PublishedKeyIds
import io.cmpose.backendtemplate.auth.UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    /** Google's published kids for [CountingProvider]: counts its fetches and fails while [fail] is set. */
    private class CountingKeySet(private val kids: Set<String>) : PublishedKeyIds {
        @Volatile var fetches = 0
        @Volatile var fail = false

        override fun fetch(): Set<String> {
            fetches++
            if (fail) throw NetworkException("down", null)
            return kids
        }
    }

    private fun verifier(jwks: JwkProvider, keySet: PublishedKeyIds? = null, clock: () -> Long) =
        FirebaseTokenVerifier(jwks, TestKeys.PROJECT_ID, 60, Dispatchers.Unconfined, clock, keySet)

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

    @Test
    fun aNewRealKidResolvesDuringAFlood() = runBlocking {
        val jwks = CountingProvider(setOf(TestKeys.KID, "rotated"))
        val keySet = CountingKeySet(setOf(TestKeys.KID, "rotated"))
        val verifier = verifier(jwks, keySet) { 1_000_000L }
        assertNull(verifier.verify(TestKeys.token(kid = "fake-1")))
        assertNotNull(verifier.verify(TestKeys.token(kid = "rotated")))
        assertEquals(1, keySet.fetches)
    }

    @Test
    fun aFloodFetchesTheKeySetOncePerInterval() = runBlocking {
        val jwks = CountingProvider()
        val keySet = CountingKeySet(setOf(TestKeys.KID))
        val verifier = verifier(jwks, keySet) { 1_000_000L }
        repeat(20) { i -> assertNull(verifier.verify(TestKeys.token(kid = "fake-$i"))) }
        assertEquals(1, keySet.fetches)
        assertTrue(jwks.asked.none { it.startsWith("fake-") }, "${jwks.asked}")
    }

    @Test
    fun aKeySetFetchFailureIs503AndGivesTheSlotBack() = runBlocking {
        val keySet = CountingKeySet(setOf(TestKeys.KID)).apply { fail = true }
        val verifier = verifier(CountingProvider(), keySet) { 1_000_000L }
        assertFailsWith<AuthUnavailableException> { verifier.verify(TestKeys.token(kid = "fake-1")) }
        keySet.fail = false
        assertNull(verifier.verify(TestKeys.token(kid = "fake-2")))
        assertEquals(2, keySet.fetches)
    }

    @Test
    fun aListedKidTheProviderCannotResolveIsUnknown() = runBlocking {
        val keySet = CountingKeySet(setOf(TestKeys.KID, "ghost"))
        val jwks = CountingProvider()
        val verifier = verifier(jwks, keySet) { 1_000_000L }
        assertNull(verifier.verify(TestKeys.token(kid = "ghost")))
        assertNull(verifier.verify(TestKeys.token(kid = "ghost")))
        assertEquals(1, keySet.fetches)
        assertEquals(listOf("ghost"), jwks.asked.toList())
    }

    @Test
    fun theAppLearnsANewRealKidDuringAFlood() = testApplication {
        val keySet = CountingKeySet(setOf(TestKeys.KID, "rotated"))
        val jwks = CountingProvider(setOf(TestKeys.KID, "rotated"))
        application { module(AppDeps(testConfig(), jwks, TestDb.database, jwkKeyIds = keySet)) }
        assertEquals(HttpStatusCode.Unauthorized, client.me(TestKeys.token(sub = "mallory", kid = "fake-1")).status)
        assertEquals(HttpStatusCode.OK, client.me(TestKeys.token(sub = "alice", kid = "rotated")).status)
        assertEquals(1, keySet.fetches)
    }
}
