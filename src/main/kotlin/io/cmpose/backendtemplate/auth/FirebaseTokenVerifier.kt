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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The [VerifiedIdentity.provider] of a Firebase user. */
const val FIREBASE_PROVIDER = "firebase"

private const val ALGORITHM = "RS256"
private const val MILLIS_PER_SECOND = 1_000L

// The longest sign-in provider id a refusal quotes; Firebase's own ids are far shorter.
private const val MAX_LOGGED_PROVIDER_CHARS = 32

/** How long after a lookup of an unknown `kid` the next one may reach the key provider. */
internal const val UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS = 60_000L

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
 * refused during a flood. A request for an unknown kid that arrives while that fetch is in flight waits for it and then
 * uses what it learned (the wait is bounded by the key provider's timeouts). Without it only the token's own kid is
 * looked up, and during a flood a new real kid may be refused for up to one interval.
 */
class FirebaseTokenVerifier(
    private val jwks: JwkProvider,
    private val projectId: String,
    private val leewaySeconds: Long,
    private val allowedSignInProviders: Set<String>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val publishedKeyIds: PublishedKeyIds? = null,
) : TokenVerifier {
    // Only kids Google's key set really contains get in, so this stays as small as that set's history.
    private val knownKeyIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastUnknownLookup = AtomicLong(now() - UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS)

    // Held by an unknown kid's lookup, so a request arriving during a fetch waits for it instead of being refused.
    private val unknownKidLock = Any()

    override suspend fun verify(token: String): VerifiedIdentity? {
        val keyId = acceptedKeyId(token) ?: return null
        // The provider may fetch over the network (a blocking call) when the kid is not cached.
        val key = withContext(dispatcher) { publicKey(keyId) } ?: return null
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

    // Runs on [dispatcher], so blocking on [unknownKidLock] while another request fetches is allowed.
    private fun publicKey(keyId: String): RSAPublicKey? {
        if (keyId in knownKeyIds) return knownKey(keyId)
        return synchronized(unknownKidLock) {
            // The fetch this request waited for may have learned its kid.
            if (keyId in knownKeyIds) knownKey(keyId) else lookUpUnknown(keyId)
        }
    }

    // Called under [unknownKidLock]: one global slot per interval for a kid not yet known.
    private fun lookUpUnknown(keyId: String): RSAPublicKey? {
        val previous = lastUnknownLookup.get()
        val at = now()
        val slotFree = at - previous >= UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS
        if (!slotFree || !lastUnknownLookup.compareAndSet(previous, at)) return rejectToken("unknown kid")
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
