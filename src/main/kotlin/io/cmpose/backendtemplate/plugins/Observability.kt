package io.cmpose.backendtemplate.plugins

import io.cmpose.backendtemplate.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import org.slf4j.event.Level
import java.util.UUID

const val REQUEST_ID_HEADER = "X-Request-Id"
const val APP_VERSION_HEADER = "X-App-Version"

// An incoming request id is kept only when it is short and made of these characters; otherwise a new one is generated,
// so a client cannot forge log fields or headers through it.
private const val MAX_REQUEST_ID_LENGTH = 128
private val REQUEST_ID_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_', '.')

/** The authenticated user's id, set by the auth layer; the request log shows it as `userId`. */
val UserIdKey = AttributeKey<String>("userId")

/** The request's id: the client's `X-Request-Id` when it is safe, else a generated one. */
val ApplicationCall.requestId: String get() = callId.orEmpty()

// A path segment after one of these keys is a secret or a user id: logged as a placeholder.
private val REDACTED_AFTER = mapOf<String, String>(
    // cmpose:sharing
    "invites" to "{token}",
    "members" to "{userId}",
    // /cmpose:sharing
)

/** [path] as the request log shows it: secrets and user ids replaced with placeholders. */
internal fun redactedPath(path: String): String {
    val segments = path.split('/').toMutableList()
    for (i in 1 until segments.size) {
        REDACTED_AFTER[segments[i - 1]]?.let { segments[i] = it }
    }
    return segments.joinToString("/")
}

private fun isSafeRequestId(id: String) =
    id.isNotEmpty() && id.length <= MAX_REQUEST_ID_LENGTH && id.all { it in REQUEST_ID_CHARS }

/**
 * Request ids, the version header and one JSON log line per request (logback with the logstash encoder, to stdout).
 * The line's MDC holds the request id, method, redacted path, status, duration and user id; never headers (tokens),
 * bodies, query strings or emails.
 */
fun Application.installObservability(config: AppConfig) {
    install(CallId) {
        retrieveFromHeader(REQUEST_ID_HEADER)
        generate { UUID.randomUUID().toString() }
        verify(::isSafeRequestId)
        replyToHeader(REQUEST_ID_HEADER)
    }
    install(
        createApplicationPlugin("AppVersionHeader") {
            onCall { call -> call.response.headers.append(APP_VERSION_HEADER, config.appVersion) }
        }
    )
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        mdc("method") { it.request.httpMethod.value }
        mdc("path") { redactedPath(it.request.path()) }
        mdc("status") { it.response.status()?.value?.toString() }
        mdc("durationMs") { it.processingTimeMillis().toString() }
        mdc("userId") { it.attributes.getOrNull(UserIdKey) }
        format { "request completed" }
    }
}
