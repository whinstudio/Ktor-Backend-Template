package io.cmpose.backendtemplate.config

import io.cmpose.backendtemplate.plugins.Cidr
import io.cmpose.backendtemplate.plugins.ClientVersion
import java.net.URI
import java.net.URISyntaxException

private const val DEFAULT_PORT = 8080
private const val MAX_PORT = 65_535
private const val DEFAULT_TRUSTED_PROXIES = "172.16.0.0/12"
private const val DEFAULT_RATE_LIMIT_PER_MINUTE = 120
private const val DEFAULT_RATE_LIMIT_SENSITIVE_PER_MINUTE = 10
private const val DEFAULT_MAX_JSON_BYTES = 1_048_576L
private const val DEFAULT_MAX_UPLOAD_BYTES = 10_485_760L
private const val DEFAULT_MAX_CONCURRENT_UPLOADS = 4
private const val DEFAULT_MAX_STORAGE_BYTES_PER_USER = 104_857_600L
// cmpose:sharing
private const val DEFAULT_INVITE_TTL_DAYS = 7
private const val DEFAULT_MAX_SPACES_PER_USER = 20
private const val DEFAULT_MAX_MEMBERS_PER_SPACE = 50
private const val DEFAULT_MAX_PENDING_INVITES_PER_SPACE = 20
// /cmpose:sharing
private const val DEFAULT_JWT_CLOCK_SKEW_SECONDS = 60L
private const val DEFAULT_ALLOWED_SIGN_IN_PROVIDERS = "password,google.com,apple.com"
private val SIGN_IN_PROVIDER = Regex("^[a-z0-9.]+$")
private val CORS_SCHEMES = setOf("http", "https")
private const val DEFAULT_DB_POOL_SIZE = 5
private const val DEFAULT_APP_VERSION = "dev"

/** Every setting the server reads from the environment, typed; see [fromEnv] for names and defaults. */
data class AppConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val firebaseProjectId: String,
    val filesDir: String,
    val corsAllowedOrigins: List<String>,
    val trustedProxies: List<String>,
    val rateLimitPerMinute: Int,
    val rateLimitSensitivePerMinute: Int,
    val maxJsonBytes: Long,
    val maxUploadBytes: Long,
    val maxConcurrentUploads: Int,
    val minClientVersion: String?,
    // Quotas: 0 turns one off.
    val maxStorageBytesPerUser: Long,
    // cmpose:sharing
    val inviteTtlDays: Int,
    val maxSpacesPerUser: Int,
    val maxMembersPerSpace: Int,
    val maxPendingInvitesPerSpace: Int,
    // /cmpose:sharing
    val publicBaseUrl: String?,
    val jwtClockSkewSeconds: Long,
    // Firebase `sign_in_provider` ids that get an account; any other (anonymous, phone, custom...) is refused.
    val allowedSignInProviders: List<String>,
    val dbPoolSize: Int,
    val appVersion: String,
) {
    // Keeps the password out of logs and exception messages.
    override fun toString() =
        "AppConfig(port=$port, databaseUrl=$databaseUrl, databaseUser=$databaseUser, databasePassword=***, " +
            "firebaseProjectId=$firebaseProjectId, filesDir=$filesDir, corsAllowedOrigins=$corsAllowedOrigins, " +
            "trustedProxies=$trustedProxies, rateLimitPerMinute=$rateLimitPerMinute, " +
            "rateLimitSensitivePerMinute=$rateLimitSensitivePerMinute, maxJsonBytes=$maxJsonBytes, " +
            "maxUploadBytes=$maxUploadBytes, maxConcurrentUploads=$maxConcurrentUploads, " +
            "minClientVersion=$minClientVersion, maxStorageBytesPerUser=$maxStorageBytesPerUser, " +
            // cmpose:sharing
            "inviteTtlDays=$inviteTtlDays, maxSpacesPerUser=$maxSpacesPerUser, " +
            "maxMembersPerSpace=$maxMembersPerSpace, maxPendingInvitesPerSpace=$maxPendingInvitesPerSpace, " +
            // /cmpose:sharing
            "publicBaseUrl=$publicBaseUrl, jwtClockSkewSeconds=$jwtClockSkewSeconds, " +
            "allowedSignInProviders=$allowedSignInProviders, dbPoolSize=$dbPoolSize, appVersion=$appVersion)"

    companion object {
        /**
         * Reads the settings from [env]. A blank value counts as unset. Every missing required variable and every
         * malformed or out-of-range number is collected, then reported at once as a [ConfigException].
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val reader = EnvReader(env)
            val config = with(reader) {
                AppConfig(
                    port = int("PORT", DEFAULT_PORT, 1..MAX_PORT),
                    databaseUrl = required("DATABASE_URL"),
                    databaseUser = required("DATABASE_USER"),
                    databasePassword = required("DATABASE_PASSWORD"),
                    firebaseProjectId = required("FIREBASE_PROJECT_ID"),
                    filesDir = required("FILES_DIR"),
                    corsAllowedOrigins = list("CORS_ALLOWED_ORIGINS", "", ::isCorsOrigin),
                    trustedProxies = list("TRUSTED_PROXIES", DEFAULT_TRUSTED_PROXIES, ::isCidr),
                    rateLimitPerMinute = int("RATE_LIMIT_PER_MINUTE", DEFAULT_RATE_LIMIT_PER_MINUTE),
                    rateLimitSensitivePerMinute =
                    int("RATE_LIMIT_SENSITIVE_PER_MINUTE", DEFAULT_RATE_LIMIT_SENSITIVE_PER_MINUTE),
                    maxJsonBytes = long("MAX_JSON_BYTES", DEFAULT_MAX_JSON_BYTES),
                    maxUploadBytes = long("MAX_UPLOAD_BYTES", DEFAULT_MAX_UPLOAD_BYTES),
                    maxConcurrentUploads = int("MAX_CONCURRENT_UPLOADS", DEFAULT_MAX_CONCURRENT_UPLOADS),
                    minClientVersion = optional("MIN_CLIENT_VERSION") { ClientVersion.parse(it) != null },
                    maxStorageBytesPerUser =
                    long("MAX_STORAGE_BYTES_PER_USER", DEFAULT_MAX_STORAGE_BYTES_PER_USER, 0L..Long.MAX_VALUE),
                    // cmpose:sharing
                    inviteTtlDays = int("INVITE_TTL_DAYS", DEFAULT_INVITE_TTL_DAYS),
                    maxSpacesPerUser = int("MAX_SPACES_PER_USER", DEFAULT_MAX_SPACES_PER_USER, 0..Int.MAX_VALUE),
                    maxMembersPerSpace = int("MAX_MEMBERS_PER_SPACE", DEFAULT_MAX_MEMBERS_PER_SPACE, 0..Int.MAX_VALUE),
                    maxPendingInvitesPerSpace =
                    int("MAX_PENDING_INVITES_PER_SPACE", DEFAULT_MAX_PENDING_INVITES_PER_SPACE, 0..Int.MAX_VALUE),
                    // /cmpose:sharing
                    publicBaseUrl = optional("PUBLIC_BASE_URL"),
                    jwtClockSkewSeconds =
                    long("JWT_CLOCK_SKEW_SECONDS", DEFAULT_JWT_CLOCK_SKEW_SECONDS, 0L..Long.MAX_VALUE),
                    allowedSignInProviders =
                    list("ALLOWED_SIGN_IN_PROVIDERS", DEFAULT_ALLOWED_SIGN_IN_PROVIDERS, SIGN_IN_PROVIDER),
                    dbPoolSize = int("DB_POOL_SIZE", DEFAULT_DB_POOL_SIZE),
                    appVersion = optional("APP_VERSION") ?: DEFAULT_APP_VERSION,
                )
            }
            if (reader.missing.isNotEmpty() || reader.invalid.isNotEmpty()) {
                throw ConfigException(reader.missing, reader.invalid)
            }
            return config
        }
    }
}

/** The environment is incomplete: [missing] required variables are unset, [invalid] ones hold malformed values. */
class ConfigException(val missing: List<String>, val invalid: List<String> = emptyList()) :
    IllegalStateException(describe(missing, invalid))

// Names only: a value may be a secret.
private fun describe(missing: List<String>, invalid: List<String>) = listOfNotNull(
    missing.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Missing environment variables: "),
    invalid.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Invalid environment variables: "),
).joinToString("; ")

/** Reads typed values from the environment and records what is missing or malformed instead of failing early. */
private class EnvReader(private val env: Map<String, String>) {
    val missing = mutableListOf<String>()
    val invalid = mutableListOf<String>()

    fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** Like [optional], but a value [valid] rejects is recorded as invalid and read as unset. */
    fun optional(name: String, valid: (String) -> Boolean): String? {
        val raw = optional(name) ?: return null
        return raw.takeIf(valid) ?: null.also { invalid += name }
    }

    fun required(name: String): String = optional(name) ?: "".also { missing += name }

    fun list(name: String, default: String): List<String> = split(optional(name) ?: default)

    /** A list whose every entry matches [entry]; one that holds no entry at all (e.g. " , ") counts as unset. */
    fun list(name: String, default: String, entry: Regex): List<String> = list(name, default, entry::matches)

    /** A list whose every entry passes [valid]; one that holds no entry at all (e.g. " , ") counts as unset. */
    fun list(name: String, default: String, valid: (String) -> Boolean): List<String> {
        val values = optional(name)?.let(::split)?.takeIf { it.isNotEmpty() } ?: return split(default)
        return values.takeIf { all -> all.all(valid) } ?: split(default).also { invalid += name }
    }

    private fun split(raw: String) = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun int(name: String, default: Int, range: IntRange = 1..Int.MAX_VALUE): Int {
        val raw = optional(name) ?: return default
        return raw.toIntOrNull()?.takeIf { it in range } ?: default.also { invalid += name }
    }

    fun long(name: String, default: Long, range: LongRange = 1L..Long.MAX_VALUE): Long {
        val raw = optional(name) ?: return default
        return raw.toLongOrNull()?.takeIf { it in range } ?: default.also { invalid += name }
    }
}

/** True when [text] is an IP address with an optional in-range prefix, as `TRUSTED_PROXIES` takes it. */
private fun isCidr(text: String) = try {
    Cidr.parse(text)
    true
} catch (_: IllegalArgumentException) {
    false
}

/**
 * True when [text] is a bare web origin: `http` or `https`, a host and an optional port, nothing else (no path, not
 * even a trailing slash, no query, fragment or user info). `java.net.URI` because Ktor's `Url` reads `example.com`
 * as a path on localhost instead of failing.
 */
private fun isCorsOrigin(text: String): Boolean {
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return false
    }
    return uri.scheme in CORS_SCHEMES && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..MAX_PORT)
}
