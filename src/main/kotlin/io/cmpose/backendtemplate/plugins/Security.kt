package io.cmpose.backendtemplate.plugins

import io.cmpose.backendtemplate.config.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.hostWithPortIfSpecified
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer

// A JSON API: nothing may be framed, sniffed, scripted or leak the URL; HSTS is set by nginx, where TLS ends.
private val SECURITY_HEADERS = mapOf(
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "X-Frame-Options" to "DENY",
    "Content-Security-Policy" to "default-src 'none'",
)

private const val COPY_BUFFER_BYTES = 8_192

/**
 * Security headers on every response and `Cache-Control: no-store` on authenticated ones, CORS for the configured
 * origins only (none by default: a mobile client needs none), and the request body limits: [AppConfig.maxUploadBytes]
 * for multipart bodies, [AppConfig.maxJsonBytes] for every other body. A body over its limit is a 413
 * `PAYLOAD_TOO_LARGE`, whether its `Content-Length` says so or it only turns out larger while being read.
 */
fun Application.installSecurity(config: AppConfig) {
    install(
        createApplicationPlugin("SecurityHeaders") {
            onCall { call -> SECURITY_HEADERS.forEach { (name, value) -> call.response.headers.append(name, value) } }
            // Runs when the answer is ready, after the route resolved the caller.
            onCallRespond { call ->
                val authenticated = call.attributes.contains(UserIdKey)
                if (authenticated && call.response.headers[HttpHeaders.CacheControl] == null) {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                }
            }
        }
    )
    if (config.corsAllowedOrigins.isNotEmpty()) installCors(config.corsAllowedOrigins)
    installBodyLimit(config.maxJsonBytes, config.maxUploadBytes)
}

/**
 * Ktor's CORS for exactly [origins] (scheme and host, as configured): any other origin gets no CORS header, so a
 * browser on a foreign site cannot read responses. No credentials: the client sends a bearer token, not cookies.
 */
private fun Application.installCors(origins: List<String>) {
    install(CORS) {
        origins.forEach { origin ->
            val url = Url(origin)
            allowHost(url.hostWithPortIfSpecified, schemes = listOf(url.protocol.name))
        }
        listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Patch, HttpMethod.Delete, HttpMethod.Options)
            .forEach(::allowMethod)
        listOf(HttpHeaders.Authorization, HttpHeaders.ContentType, REQUEST_ID_HEADER, CLIENT_VERSION_HEADER)
            .forEach(::allowHeader)
        exposeHeader(APP_VERSION_HEADER)
        exposeHeader(REQUEST_ID_HEADER)
        // A browser client reads it to wait out a 429.
        exposeHeader(HttpHeaders.RetryAfter)
        allowCredentials = false
    }
}

private fun Application.installBodyLimit(maxJsonBytes: Long, maxUploadBytes: Long) {
    fun limitOf(call: ApplicationCall) =
        if (call.request.contentType().match(ContentType.MultiPart.Any)) maxUploadBytes else maxJsonBytes
    install(
        createApplicationPlugin("RequestBodyLimit") {
            // A declared length over the limit is refused before a byte is read.
            onCall { call ->
                val declared = call.request.contentLength() ?: return@onCall
                if (declared > limitOf(call)) throw PayloadTooLargeException(limitOf(call))
            }
            // A body without a length (chunked) or with a false one is counted while it is read.
            on(ReceiveRequestBytes) { call, body -> call.limited(body, limitOf(call)) }
        }
    )
}

/** Set on a call whose body turned out larger than its limit while being read; the error handler answers 413. */
internal val BodyTooLargeKey = AttributeKey<Unit>("BodyTooLarge")

private fun ApplicationCall.limited(source: ByteReadChannel, limit: Long): ByteReadChannel = writer {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    var read = source.readAvailable(buffer)
    while (read != -1) {
        total += read
        if (total > limit) {
            attributes.put(BodyTooLargeKey, Unit)
            channel.cancel(PayloadTooLargeException(limit))
            return@writer
        }
        channel.writeFully(buffer, 0, read)
        read = source.readAvailable(buffer)
    }
}.channel
