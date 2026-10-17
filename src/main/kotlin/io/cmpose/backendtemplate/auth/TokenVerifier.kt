package io.cmpose.backendtemplate.auth

/**
 * Who a valid token says the caller is: the sign-in [provider] and its [subject] (for Firebase the uid), plus the
 * optional profile claims. An [email] that is not [emailVerified] is never stored nor shown (see
 * `UserRepository.resolveOrCreate`).
 */
data class VerifiedIdentity(
    val provider: String,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
)

/** Checks a bearer token. Null means the token is invalid (a 401). */
fun interface TokenVerifier {
    /** @throws AuthUnavailableException when the signing keys cannot be fetched and none is cached (a 503). */
    suspend fun verify(token: String): VerifiedIdentity?
}

/** The token could not be checked because the signing keys are unreachable: the caller's fault is unknown. */
class AuthUnavailableException(cause: Throwable? = null) : RuntimeException("Signing keys unavailable", cause)
