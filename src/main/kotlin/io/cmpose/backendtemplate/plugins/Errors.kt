@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.sql.SQLException

/** One invalid input field of a validation error. */
@Serializable
data class FieldError(val field: String, val message: String)

/**
 * A failure the client may see: [status], a constant [code] and a message safe to show; [quota] only on a
 * `QUOTA_EXCEEDED` (see [quotaExceeded]).
 */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    message: String,
    val fields: List<FieldError> = emptyList(),
    val quota: String? = null,
) : RuntimeException(message)

/** A 404 `NOT_FOUND`; also the answer for a row the caller may not see, so its existence is not revealed. */
fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "Not found")

/** A 400 `BAD_REQUEST` with a [message] safe to show and, for validation errors, the invalid [fields]. */
fun badRequest(message: String, fields: List<FieldError> = emptyList()): Nothing =
    throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", message, fields)

/** [minVersion] is set only on a 426 `CLIENT_UPGRADE_REQUIRED`, [quota] only on a 409 `QUOTA_EXCEEDED`. */
@Serializable
data class ErrorDetail(
    val code: String,
    val message: String,
    val requestId: String,
    val fields: List<FieldError>? = null,
    val minVersion: String? = null,
    val quota: String? = null,
)

/** The one error body: `{"error":{"code":…,"message":…,"requestId":…}}`, plus `fields` for validation errors. */
@Serializable
data class ErrorBody(val error: ErrorDetail)

private const val MAX_CAUSES = 8

/**
 * What the log says about an unexpected error: exception class names along the cause chain, plus the SQLState of a
 * database error. Never the messages or stack text: a database error's message quotes the statement and the failing
 * row, which hold user ids and emails; other messages may quote secrets.
 */
internal fun describe(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()
    val classes = chain.joinToString(" <- ") { it::class.java.name }
    val sqlState = chain.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }
    return if (sqlState == null) classes else "$classes (SQLState $sqlState)"
}

private fun Throwable.causes() = generateSequence(this) { it.cause }.take(MAX_CAUSES)

/** Answers with the shared error body. */
suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    fields: List<FieldError> = emptyList(),
    minVersion: String? = null,
    quota: String? = null,
) {
    respond(status, ErrorBody(ErrorDetail(code, message, requestId, fields.ifEmpty { null }, minVersion, quota)))
}

private suspend fun ApplicationCall.respondPayloadTooLarge() =
    respondError(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", "Request body is too large")

private suspend fun ApplicationCall.respondUnsupportedMediaType() =
    respondError(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type")

private fun ApplicationCall.bodyTooLarge(e: Throwable) =
    attributes.contains(BodyTooLargeKey) || e.causes().any { it is PayloadTooLargeException }

// A typed body is read as JSON only; any other declared type cannot be converted.
private fun ApplicationCall.isUnsupportedBody(): Boolean {
    val type = request.contentType()
    return type != ContentType.Any && !type.match(ContentType.Application.Json)
}

private suspend fun ApplicationCall.respondMalformedBody() =
    respondError(HttpStatusCode.BadRequest, "BAD_REQUEST", "Malformed request body")

/**
 * Renders every failure as the shared error body. An [ApiException] keeps its status, code and message; a body that
 * cannot be read is a 400, one over the size limit a 413; anything else is a 500 whose detail goes only to the log
 * (class names, see [describe]). Bare statuses (an unknown route, a rate limit) get the same body.
 */
fun Application.installErrors() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            // An audit line without personal data; the request log already has the request id and the user id.
            if (e.code == "QUOTA_EXCEEDED") call.application.log.info("Quota exceeded ({})", e.quota)
            call.respondError(e.status, e.code, e.message.orEmpty(), e.fields, quota = e.quota)
        }
        exception<ClientUpgradeRequiredException> { call, e ->
            call.respondError(
                HttpStatusCode.UpgradeRequired,
                "CLIENT_UPGRADE_REQUIRED",
                "This app version is no longer supported, please update",
                minVersion = e.minVersion,
            )
        }
        exception<UnsupportedMediaTypeException> { call, _ -> call.respondUnsupportedMediaType() }
        // The body reader may swallow or wrap the size limit's exception, so the limiter's mark on the call decides.
        exception<ContentTransformationException> { call, e ->
            when {
                call.bodyTooLarge(e) -> call.respondPayloadTooLarge()
                call.isUnsupportedBody() -> call.respondUnsupportedMediaType()
                else -> call.respondMalformedBody()
            }
        }
        exception<BadRequestException> { call, e ->
            if (call.bodyTooLarge(e)) call.respondPayloadTooLarge() else call.respondMalformedBody()
        }
        exception<SerializationException> { call, e ->
            if (call.bodyTooLarge(e)) call.respondPayloadTooLarge() else call.respondMalformedBody()
        }
        exception<Throwable> { call, e ->
            if (call.bodyTooLarge(e)) {
                call.respondPayloadTooLarge()
            } else {
                call.application.log.error("Unhandled error: {}", describe(e))
                call.respondError(HttpStatusCode.InternalServerError, "INTERNAL", "Internal error")
            }
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(HttpStatusCode.NotFound, "NOT_FOUND", "Not found")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondError(HttpStatusCode.MethodNotAllowed, "METHOD_NOT_ALLOWED", "Method not allowed")
        }
        status(HttpStatusCode.PayloadTooLarge) { call, _ -> call.respondPayloadTooLarge() }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respondError(HttpStatusCode.TooManyRequests, "RATE_LIMITED", "Too many requests, try again later")
        }
    }
}
