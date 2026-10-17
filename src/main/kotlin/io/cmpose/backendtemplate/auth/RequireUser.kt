@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.auth

import io.cmpose.backendtemplate.plugins.ApiException
import io.cmpose.backendtemplate.plugins.UserIdKey
import io.cmpose.backendtemplate.users.UserId
import io.cmpose.backendtemplate.users.UserRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.parsing.ParseException
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import java.security.MessageDigest

/** What [requireUser] needs: the token check and the user mapping. */
class UserAuth(val verifier: TokenVerifier, val users: UserRepository)

private val UserAuthKey = AttributeKey<UserAuth>("UserAuth")
private val AuthOutcomeKey = AttributeKey<AuthOutcome>("AuthOutcome")
private val ResolvedUserKey = AttributeKey<UserId>("ResolvedUser")

/** What the request's bearer token turned out to be; checked once per request, see [authOutcome]. */
private sealed interface AuthOutcome {
    data class Verified(val identity: VerifiedIdentity) : AuthOutcome
    data object Missing : AuthOutcome
    data object Invalid : AuthOutcome
    data object Unavailable : AuthOutcome
}

/** Makes [requireUser] available to every route of this application. */
fun Application.installUserAuth(auth: UserAuth) {
    attributes.put(UserAuthKey, auth)
}

/**
 * The caller's [UserId]. A missing, malformed or invalid bearer token is a 401 `UNAUTHORIZED`; signing keys that
 * cannot be fetched are a 503 `AUTH_UNAVAILABLE`. The first valid token of a subject creates its user. Every protected
 * route calls this first; the token is checked once per request, however often this (or [identityKeyOrNull]) is
 * called, and the user is resolved once.
 */
suspend fun ApplicationCall.requireUser(): UserId {
    attributes.getOrNull(ResolvedUserKey)?.let { return it }
    val identity = when (val outcome = authOutcome()) {
        is AuthOutcome.Verified -> outcome.identity

        AuthOutcome.Unavailable -> throw ApiException(
            HttpStatusCode.ServiceUnavailable,
            "AUTH_UNAVAILABLE",
            "Sign-in cannot be checked right now, try again later",
        )

        AuthOutcome.Missing, AuthOutcome.Invalid -> {
            response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Unauthorized")
        }
    }
    val user = application.attributes[UserAuthKey].users.resolveOrCreate(identity)
    attributes.put(ResolvedUserKey, user)
    attributes.put(UserIdKey, user.toString())
    return user
}

/**
 * A stable key for the caller when the request carries a valid token, else null (never a 401 or 503): a SHA-256 of
 * `provider:subject`, so the subject itself never appears in a key. Touches no database; the rate limiter keys on it.
 * Null as well when the application has no user auth installed (no database).
 */
suspend fun ApplicationCall.identityKeyOrNull(): String? {
    if (!application.attributes.contains(UserAuthKey)) return null
    val identity = (authOutcome() as? AuthOutcome.Verified)?.identity ?: return null
    val digest = MessageDigest.getInstance("SHA-256").digest("${identity.provider}:${identity.subject}".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

private suspend fun ApplicationCall.authOutcome(): AuthOutcome {
    attributes.getOrNull(AuthOutcomeKey)?.let { return it }
    val verifier = application.attributes[UserAuthKey].verifier
    val header = request.headers[HttpHeaders.Authorization]
    val token = header?.let(::bearerToken)
    val outcome = when {
        // Anonymous requests are normal (health checks, public routes): not worth a warning.
        header == null -> AuthOutcome.Missing.also { authLog.debug("No bearer token") }
        token == null -> AuthOutcome.Invalid.also { rejectToken("not a bearer token") }
        else -> verify(verifier, token)
    }
    attributes.put(AuthOutcomeKey, outcome)
    return outcome
}

// The verifier logs its own refusals.
private suspend fun verify(verifier: TokenVerifier, token: String): AuthOutcome {
    val identity = try {
        verifier.verify(token)
    } catch (_: AuthUnavailableException) {
        return AuthOutcome.Unavailable
    }
    return identity?.let(AuthOutcome::Verified) ?: AuthOutcome.Invalid
}

private fun bearerToken(raw: String): String? {
    val header = try {
        parseAuthorizationHeader(raw)
    } catch (_: ParseException) {
        null
    }
    return (header as? HttpAuthHeader.Single)
        ?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }
        ?.blob
        ?.takeIf { it.isNotBlank() }
}
