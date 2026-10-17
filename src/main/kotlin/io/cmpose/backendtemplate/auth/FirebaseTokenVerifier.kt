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
 * non-empty `sub`. No Admin SDK, no service account. Every refusal is logged at WARN as "Token rejected: <reason>", so
 * a 401 on a device can be explained from the log.
 *
 * A `kid` that has never resolved may reach [jwks] at most once per [UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS] (one global
 * slot, not one per kid); any other is refused as "unknown kid" without a lookup. So a flood of made-up kids cannot
 * spend the provider's fetch budget, and real users keep getting their keys instead of a 503. A lookup that finds the
 * key frees the slot again, so new real kids (a cold start, a key rotation) resolve back to back; only during such a
 * flood may a new real kid be refused for up to one interval.
 */
class FirebaseTokenVerifier(
    private val jwks: JwkProvider,
    private val projectId: String,
    private val leewaySeconds: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : TokenVerifier {
    // Only kids Google's key set really contains get in, so this stays as small as that set's history.
    private val knownKeyIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastUnknownLookup = AtomicLong(now() - UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS)

    override suspend fun verify(token: String): VerifiedIdentity? {
        val keyId = acceptedKeyId(token) ?: return null
        // The provider may fetch over the network (a blocking call) when the kid is not cached.
        val key = withContext(dispatcher) { publicKey(keyId) } ?: return null
        val jwt = try {
            verifierFor(key).verify(token)
        } catch (e: JWTVerificationException) {
            return rejectToken(rejectionReason(e))
        }
        val subject = jwt.subject?.takeIf { it.isNotBlank() } ?: return rejectToken("no subject")
        return VerifiedIdentity(
            provider = FIREBASE_PROVIDER,
            subject = subject,
            email = jwt.getClaim("email").asString(),
            emailVerified = jwt.getClaim("email_verified").asBoolean() == true,
            name = jwt.getClaim("name").asString(),
        )
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

    private fun publicKey(keyId: String): RSAPublicKey? {
        if (keyId in knownKeyIds) return lookUp(keyId) ?: null.also { knownKeyIds -= keyId }
        val previous = lastUnknownLookup.get()
        val at = now()
        val slotFree = at - previous >= UNKNOWN_KID_LOOKUP_INTERVAL_MILLIS
        if (!slotFree || !lastUnknownLookup.compareAndSet(previous, at)) return rejectToken("unknown kid")
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
