package io.cmpose.backendtemplate.plugins

import io.cmpose.backendtemplate.config.AppConfig
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path

const val CLIENT_VERSION_HEADER = "X-Client-Version"

// Liveness and readiness are probed by tools that send no app version.
private val EXEMPT_PATHS = setOf("/health", "/ready")
private val VERSION_FORMAT = Regex("""^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$""")

/** The client is older than `MIN_CLIENT_VERSION`: a 426 `CLIENT_UPGRADE_REQUIRED` that names [minVersion]. */
class ClientUpgradeRequiredException(val minVersion: String) : RuntimeException("Client upgrade required")

/** A `MAJOR.MINOR.PATCH` version, compared part by part as numbers (so 1.10.0 is newer than 1.9.0). */
internal data class ClientVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ClientVersion> {
    override fun compareTo(other: ClientVersion) =
        compareValuesBy(this, other, ClientVersion::major, ClientVersion::minor, ClientVersion::patch)

    companion object {
        /** Null unless [text] is exactly three dot-separated numbers without leading zeros. */
        fun parse(text: String): ClientVersion? = VERSION_FORMAT.matchEntire(text.trim())?.destructured
            ?.let { (major, minor, patch) -> ClientVersion(major.toInt(), minor.toInt(), patch.toInt()) }
    }
}

/**
 * Turns away clients older than [AppConfig.minClientVersion] with a 426. A request without `X-Client-Version` (a
 * browser, curl) passes; a malformed one is a 400. `/health` and `/ready` are exempt. Without a minimum nothing is
 * checked.
 *
 * @throws IllegalArgumentException at startup when the configured minimum is not `MAJOR.MINOR.PATCH`.
 */
fun Application.installClientVersion(config: AppConfig) {
    val minText = config.minClientVersion ?: return
    val min = requireNotNull(ClientVersion.parse(minText)) { "MIN_CLIENT_VERSION must be MAJOR.MINOR.PATCH" }
    install(
        createApplicationPlugin("ClientVersion") {
            onCall { call ->
                if (call.request.path() in EXEMPT_PATHS) return@onCall
                val raw = call.request.headers[CLIENT_VERSION_HEADER] ?: return@onCall
                val version = ClientVersion.parse(raw) ?: badRequest("$CLIENT_VERSION_HEADER must be MAJOR.MINOR.PATCH")
                if (version < min) throw ClientUpgradeRequiredException(minText)
            }
        }
    )
}
