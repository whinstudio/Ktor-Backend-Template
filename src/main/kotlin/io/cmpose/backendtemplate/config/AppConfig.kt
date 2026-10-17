package io.cmpose.backendtemplate.config

private const val DEFAULT_PORT = 8080
private const val MAX_PORT = 65_535
private const val DEFAULT_TRUSTED_PROXIES = "172.16.0.0/12"
private const val DEFAULT_RATE_LIMIT_PER_MINUTE = 120
private const val DEFAULT_RATE_LIMIT_SENSITIVE_PER_MINUTE = 10
private const val DEFAULT_MAX_JSON_BYTES = 1_048_576L
private const val DEFAULT_MAX_UPLOAD_BYTES = 10_485_760L
private const val DEFAULT_MAX_CONCURRENT_UPLOADS = 4
// cmpose:sharing
private const val DEFAULT_INVITE_TTL_DAYS = 7
// /cmpose:sharing
private const val DEFAULT_JWT_CLOCK_SKEW_SECONDS = 60L
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
    // cmpose:sharing
    val inviteTtlDays: Int,
    // /cmpose:sharing
    val publicBaseUrl: String?,
    val jwtClockSkewSeconds: Long,
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
            "minClientVersion=$minClientVersion, " +
            // cmpose:sharing
            "inviteTtlDays=$inviteTtlDays, " +
            // /cmpose:sharing
            "publicBaseUrl=$publicBaseUrl, jwtClockSkewSeconds=$jwtClockSkewSeconds, dbPoolSize=$dbPoolSize, " +
            "appVersion=$appVersion)"

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
                    corsAllowedOrigins = list("CORS_ALLOWED_ORIGINS", ""),
                    trustedProxies = list("TRUSTED_PROXIES", DEFAULT_TRUSTED_PROXIES),
                    rateLimitPerMinute = int("RATE_LIMIT_PER_MINUTE", DEFAULT_RATE_LIMIT_PER_MINUTE),
                    rateLimitSensitivePerMinute =
                    int("RATE_LIMIT_SENSITIVE_PER_MINUTE", DEFAULT_RATE_LIMIT_SENSITIVE_PER_MINUTE),
                    maxJsonBytes = long("MAX_JSON_BYTES", DEFAULT_MAX_JSON_BYTES),
                    maxUploadBytes = long("MAX_UPLOAD_BYTES", DEFAULT_MAX_UPLOAD_BYTES),
                    maxConcurrentUploads = int("MAX_CONCURRENT_UPLOADS", DEFAULT_MAX_CONCURRENT_UPLOADS),
                    minClientVersion = optional("MIN_CLIENT_VERSION"),
                    // cmpose:sharing
                    inviteTtlDays = int("INVITE_TTL_DAYS", DEFAULT_INVITE_TTL_DAYS),
                    // /cmpose:sharing
                    publicBaseUrl = optional("PUBLIC_BASE_URL"),
                    jwtClockSkewSeconds =
                    long("JWT_CLOCK_SKEW_SECONDS", DEFAULT_JWT_CLOCK_SKEW_SECONDS, 0L..Long.MAX_VALUE),
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

/** The environment is incomplete: [missing] required variables are unset, [invalid] ones hold bad numbers. */
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

    fun required(name: String): String = optional(name) ?: "".also { missing += name }

    fun list(name: String, default: String): List<String> =
        (optional(name) ?: default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun int(name: String, default: Int, range: IntRange = 1..Int.MAX_VALUE): Int {
        val raw = optional(name) ?: return default
        return raw.toIntOrNull()?.takeIf { it in range } ?: default.also { invalid += name }
    }

    fun long(name: String, default: Long, range: LongRange = 1L..Long.MAX_VALUE): Long {
        val raw = optional(name) ?: return default
        return raw.toLongOrNull()?.takeIf { it in range } ?: default.also { invalid += name }
    }
}
