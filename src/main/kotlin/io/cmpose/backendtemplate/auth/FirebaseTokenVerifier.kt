package io.cmpose.backendtemplate.auth

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.NetworkException
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.AlgorithmMismatchException
import com.auth0.jwt.exceptions.IncorrectClaimException
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.MissingClaimException
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The [VerifiedIdentity.provider] of a Firebase user. */
const val FIREBASE_PROVIDER = "firebase"

private const val ALGORITHM = "RS256"
private const val MILLIS_PER_SECOND = 1_000L

// The longest sign-in provider id a refusal quotes; Firebase's own ids are far shorter.
private const val MAX_LOGGED_PROVIDER_CHARS = 32

/** How long after a lookup of an unknown `kid` the next one may reach the key provider. */
internal const val UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS = 60_000L

// Above the key provider's 3 s connect + 5 s read timeouts, so a waiter outlasts any fetch that ends on its own.
private const val DEFAULT_UNKNOWN_KID_WAIT_MILLIS = 10_000L

/** Logs why a token was refused (reason only: never the token, its subject or its email). */
internal val authLog = LoggerFactory.getLogger("io.cmpose.backendtemplate.auth")

/** Logs a refusal and returns null, the verifier's "invalid token". */
internal fun rejectToken(reason: String): Nothing? {
    authLog.warn("Token rejected: {}", reason)
    return null
}

/**
 * Verifies Firebase ID tokens with Google's published securetoken keys ([jwks]): an RS256 signature by the key the
 * `kid` names, `iss` and `aud` bound to [projectId], `exp`/`iat`/`auth_time` with [leewaySeconds] of clock skew and a
 * non-empty `sub` and a `firebase.sign_in_provider` in [allowedSignInProviders] (a token without one is refused, so
 * anonymous, phone or custom sign-ins get no account unless listed). No Admin SDK, no service account. Every refusal
 * is logged at WARN as "Token rejected: <reason>", so a 401 on a device can be explained from the log.
 *
 * A `kid` not yet seen in Google's key set may reach Google at most once per [UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS] (one
 * global slot, not one per kid); any other is refused as "unknown kid" without a lookup. So a flood of made-up kids
 * cannot spend the provider's fetch budget, and real users keep getting their keys instead of a 503. A lookup that
 * finds the key frees the slot again, so new real kids (a cold start, a key rotation) resolve back to back. With
 * [publishedKeyIds] that lookup fetches Google's whole key set and learns every kid in it, so a flood's one fetch per
 * interval also learns a newly published real key (Google publishes a key before it signs with it): a real kid is not
 * refused during a flood. Without it only the token's own kid is looked up, and during a flood a new real kid may be
 * refused for up to one interval.
 *
 * At most one such lookup is in flight. A request for an unknown kid that arrives during it suspends (it holds no
 * thread of [dispatcher]) until the lookup ends, for at most [unknownKidWaitMillis], and then shares its outcome
 * instead of fetching again: if the lookup learned the kid, the token is checked with that key; if it did not, the kid
 * is refused as "unknown kid" (with [publishedKeyIds] the whole key set was just fetched; without it the kid may still
 * take the slot by the rule above); if the lookup failed, or the wait ran out, the request gets
 * [AuthUnavailableException] (503). So however many requests wait, Google is asked once and every waiter has an
 * answer within the bound.
 */
class FirebaseTokenVerifier(
    private val jwks: JwkProvider,
    private val projectId: String,
    private val leewaySeconds: Long,
    private val allowedSignInProviders: Set<String>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val publishedKeyIds: PublishedKeyIds? = null,
    private val unknownKidWaitMillis: Long = DEFAULT_UNKNOWN_KID_WAIT_MILLIS,
) : TokenVerifier {
    // Only kids Google's key set really contains get in, so this stays as small as that set's history.
    private val knownKeyIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastUnknownLookup = AtomicLong(now() - UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS)

    // Guards [fetchInFlight] and the taking of the unknown-kid slot; held only briefly, never during a fetch.
    private val fetchLock = Any()

    // The unknown kid's lookup in flight, completed with true when it ended without a failure.
    private var fetchInFlight: CompletableDeferred<Boolean>? = null

    init {
        require(unknownKidWaitMillis > 0) { "unknownKidWaitMillis must be positive" }
    }

    override suspend fun verify(token: String): VerifiedIdentity? {
        val keyId = acceptedKeyId(token) ?: return null
        val key = publicKey(keyId) ?: return null
        val jwt = try {
            verifierFor(key).verify(token)
        } catch (e: JWTVerificationException) {
            return rejectToken(rejectionReason(e))
        }
        claimsRefusal(jwt)?.let { return rejectToken(it) }
        return VerifiedIdentity(
            provider = FIREBASE_PROVIDER,
            subject = jwt.subject,
            email = jwt.getClaim("email").asString(),
            emailVerified = jwt.getClaim("email_verified").asBoolean() == true,
            name = jwt.getClaim("name").asString(),
        )
    }

    // Why a verified token still gets no account, or null when its subject and sign-in provider are acceptable.
    private fun claimsRefusal(jwt: DecodedJWT): String? {
        if (jwt.subject.isNullOrBlank()) return "no subject"
        val signInProvider = jwt.getClaim("firebase").asMap()?.get("sign_in_provider") as? String
            ?: return "no sign-in provider"
        // One of Firebase's fixed ids, not personal data; capped in case a token carries something else.
        return "sign-in provider not allowed: ${signInProvider.take(MAX_LOGGED_PROVIDER_CHARS)}"
            .takeIf { signInProvider !in allowedSignInProviders }
    }

    // Checked before any key fetch, so `none`, HS256 and kid-less tokens never reach Google's endpoint.
    private fun acceptedKeyId(token: String): String? {
        val decoded = try {
            JWT.decode(token)
        } catch (_: JWTDecodeException) {
            return rejectToken("malformed token")
        }
        if (decoded.algorithm != ALGORITHM) return rejectToken("algorithm mismatch")
        return decoded.keyId ?: rejectToken("missing kid")
    }

    // The provider's calls block (a network fetch when the kid is not cached), so they run on [dispatcher]; a wait for
    // another request's lookup suspends instead, bounded by [unknownKidWaitMillis] in all.
    private suspend fun publicKey(keyId: String): RSAPublicKey? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(unknownKidWaitMillis)
        var afterFetch = false
        while (true) {
            val fetch = when (val step = withContext(dispatcher) { keyOrFetchInFlight(keyId, afterFetch) }) {
                is KeyStep.Resolved -> return step.key
                is KeyStep.Wait -> step.fetch
            }
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            val succeeded = if (remainingMillis > 0) withTimeoutOrNull(remainingMillis) { fetch.await() } else null
            if (succeeded != true) {
                val reason = if (succeeded == null) "key fetch timed out" else "JWKS unavailable"
                authLog.warn("Token not checked: {}", reason)
                throw AuthUnavailableException()
            }
            afterFetch = true
        }
    }

    // Blocking. Resolves a known kid, joins the lookup in flight, or takes the slot and runs the lookup itself.
    private fun keyOrFetchInFlight(keyId: String, afterFetch: Boolean): KeyStep {
        if (keyId in knownKeyIds) return KeyStep.Resolved(knownKey(keyId))
        val fetch = CompletableDeferred<Boolean>()
        return when (val next = synchronized(fetchLock) { nextStep(keyId, afterFetch, fetch) }) {
            Next.Known -> KeyStep.Resolved(knownKey(keyId))
            Next.Refuse -> KeyStep.Resolved(rejectToken("unknown kid"))
            is Next.Join -> KeyStep.Wait(next.fetch)
            is Next.LookUp -> KeyStep.Resolved(runLookup(keyId, next.slot, fetch))
        }
    }

    // Called under [fetchLock]; on [Next.LookUp] the slot is taken and [fetch] is the lookup in flight.
    private fun nextStep(keyId: String, afterFetch: Boolean, fetch: CompletableDeferred<Boolean>): Next {
        // A lookup that just ended may have learned the kid.
        if (keyId in knownKeyIds) return Next.Known
        fetchInFlight?.let { return Next.Join(it) }
        // The lookup just waited for fetched Google's whole key set, and the kid is not in it.
        if (afterFetch && publishedKeyIds != null) return Next.Refuse
        val slot = takeSlot() ?: return Next.Refuse
        fetchInFlight = fetch
        return Next.LookUp(slot)
    }

    // Publishes the lookup's outcome to its waiters (false when it threw) once it is no longer in flight.
    private fun runLookup(keyId: String, slot: Slot, fetch: CompletableDeferred<Boolean>): RSAPublicKey? {
        var succeeded = false
        try {
            return lookUpUnknown(keyId, slot).also { succeeded = true }
        } finally {
            synchronized(fetchLock) { fetchInFlight = null }
            fetch.complete(succeeded)
        }
    }

    // One global slot per interval for a kid not yet known; null when it is taken.
    private fun takeSlot(): Slot? {
        val previous = lastUnknownLookup.get()
        val at = now()
        val slotFree = at - previous >= UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS
        return if (slotFree && lastUnknownLookup.compareAndSet(previous, at)) Slot(at, previous) else null
    }

    // Holds [slot]: gives it back on a failed fetch, frees it once [keyId]'s key resolves.
    private fun lookUpUnknown(keyId: String, slot: Slot): RSAPublicKey? {
        val (at, previous) = slot
        if (publishedKeyIds != null) return learnPublishedKeys(publishedKeyIds, keyId, at, previous)
        val key = try {
            lookUp(keyId)
        } catch (e: AuthUnavailableException) {
            lastUnknownLookup.compareAndSet(at, previous)
            throw e
        }
        if (key != null) {
            knownKeyIds += keyId
            lastUnknownLookup.compareAndSet(at, previous)
        }
        return key
    }

    // A known kid that no longer resolves is dropped, so it goes through the slot again.
    private fun knownKey(keyId: String): RSAPublicKey? = lookUp(keyId) ?: null.also { knownKeyIds -= keyId }

    // Holds the slot taken at [at]; gives it back on a failed fetch, frees it once [keyId]'s key resolves.
    private fun learnPublishedKeys(published: PublishedKeyIds, keyId: String, at: Long, previous: Long): RSAPublicKey? {
        val keyIds = try {
            published.fetch()
        } catch (e: JwkException) {
            authLog.warn("Token not checked: JWKS unavailable")
            lastUnknownLookup.compareAndSet(at, previous)
            throw AuthUnavailableException(e)
        }
        knownKeyIds += keyIds
        // The slot stays taken for a kid Google does not publish or that does not resolve: one fetch per interval.
        if (keyId !in keyIds) return rejectToken("unknown kid")
        val key = try {
            knownKey(keyId)
        } catch (e: AuthUnavailableException) {
            lastUnknownLookup.compareAndSet(at, previous)
            throw e
        }
        if (key != null) lastUnknownLookup.compareAndSet(at, previous)
        return key
    }

    // An unknown kid is the caller's problem (401); a key fetch that failed is ours (503).
    private fun lookUp(keyId: String): RSAPublicKey? {
        val jwk = try {
            jwks.get(keyId)
        } catch (e: JwkException) {
            if (isUnknownKid(e)) return rejectToken("unknown kid")
            authLog.warn("Token not checked: JWKS unavailable")
            throw AuthUnavailableException(e)
        }
        return jwk.publicKey as? RSAPublicKey ?: rejectToken("signing key is not RSA")
    }

    private fun verifierFor(key: RSAPublicKey) = JWT.require(Algorithm.RSA256(key, null))
        .withIssuer("https://securetoken.google.com/$projectId")
        .withAudience(projectId)
        .acceptLeeway(leewaySeconds)
        .withClaim("auth_time") { claim, _ -> isPast(claim.asLong()) }
        .build()

    private fun isPast(epochSeconds: Long?) =
        epochSeconds != null && epochSeconds <= System.currentTimeMillis() / MILLIS_PER_SECOND + leewaySeconds
}

// The unknown-kid slot taken at [at]; [previous] is the take it replaced, restored when the slot is given back.
private data class Slot(val at: Long, val previous: Long)

// What a request for a kid does next: use the key it resolved (null is a refusal), or wait for the lookup in flight.
private sealed interface KeyStep {
    class Resolved(val key: RSAPublicKey?) : KeyStep

    class Wait(val fetch: Deferred<Boolean>) : KeyStep
}

// The decision taken under the verifier's fetch lock for a kid that was not known.
private sealed interface Next {
    data object Known : Next

    data object Refuse : Next

    class Join(val fetch: Deferred<Boolean>) : Next

    class LookUp(val slot: Slot) : Next
}

// The cached provider wraps a fetch failure in SigningKeyNotFoundException; the cause chain tells them apart.
private fun isUnknownKid(e: JwkException): Boolean =
    e is SigningKeyNotFoundException && generateSequence<Throwable>(e) { it.cause }.none { it is NetworkException }

/** Why java-jwt refused a token, by exception type; the exception's message may quote the token and is not used. */
internal fun rejectionReason(e: JWTVerificationException): String = when (e) {
    is TokenExpiredException -> "expired"

    is IncorrectClaimException -> when (e.claimName) {
        "aud" -> "wrong audience"
        "iss" -> "wrong issuer"
        else -> "invalid claim ${e.claimName}"
    }

    is MissingClaimException -> "missing claim ${e.claimName}"

    is SignatureVerificationException -> "bad signature"

    is AlgorithmMismatchException -> "algorithm mismatch"

    is JWTDecodeException -> "malformed token"

    else -> "invalid token (${e::class.simpleName})"
}
