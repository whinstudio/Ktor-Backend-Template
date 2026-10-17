package io.cmpose.backendtemplate

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

/** A local RSA key that stands in for Google's securetoken keys in tests; no network call is ever made. */
object TestKeys {
    const val PROJECT_ID = "template-test"
    const val KID = "test-key-1"
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** Publishes [keyPair]'s public key under [KID]; any other kid is unknown. */
    val jwkProvider = object : JwkProvider {
        override fun get(keyId: String): Jwk {
            if (keyId != KID) throw SigningKeyNotFoundException("unknown kid", null)
            val key = keyPair.public as RSAPublicKey
            val b64 = Base64.getUrlEncoder().withoutPadding()
            return Jwk.fromValues(
                mapOf(
                    "kid" to KID, "kty" to "RSA", "alg" to "RS256", "use" to "sig",
                    "n" to b64.encodeToString(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()),
                    "e" to b64.encodeToString(key.publicExponent.toByteArray()),
                ),
            )
        }
    }

    /**
     * A Firebase-shaped ID token. [iss] defaults to this project's securetoken issuer; [alg] is `RS256` (signed with
     * [signWith]), `none` (unsigned) or `HS256` (an HMAC secret, the classic key-confusion attempt). An [email] comes
     * with `email_verified` set to [emailVerified].
     */
    fun token(
        sub: String = "u1",
        aud: String = PROJECT_ID,
        iss: String? = null,
        expiresInSec: Long = 3600,
        kid: String = KID,
        alg: String = "RS256",
        email: String? = null,
        emailVerified: Boolean = true,
        signWith: PrivateKey = keyPair.private,
    ): String {
        val now = System.currentTimeMillis()
        val algorithm = when (alg) {
            "RS256" -> Algorithm.RSA256(null, signWith as RSAPrivateKey)
            "none" -> Algorithm.none()
            "HS256" -> Algorithm.HMAC256("not-a-google-key")
            else -> error("unsupported alg $alg")
        }
        return JWT.create()
            .withKeyId(kid)
            .withIssuer(iss ?: "https://securetoken.google.com/$PROJECT_ID")
            .withAudience(aud)
            .withSubject(sub)
            .withIssuedAt(Date(now - 1_000))
            .withClaim("auth_time", (now - 1_000) / 1_000)
            .withExpiresAt(Date(now + expiresInSec * 1_000))
            .apply { if (email != null) withClaim("email", email).withClaim("email_verified", emailVerified) }
            .sign(algorithm)
    }
}
