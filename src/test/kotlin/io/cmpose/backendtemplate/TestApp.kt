package io.cmpose.backendtemplate

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import io.cmpose.backendtemplate.config.AppConfig
import org.jetbrains.exposed.sql.Database

/** A config with every value set, for tests that build the application without the environment. */
fun testConfig(appVersion: String = "test-1") = AppConfig(
    port = 0,
    databaseUrl = "",
    databaseUser = "",
    databasePassword = "",
    firebaseProjectId = "template-test",
    filesDir = "build/test-files",
    corsAllowedOrigins = emptyList(),
    trustedProxies = listOf("172.16.0.0/12"),
    rateLimitPerMinute = 120,
    rateLimitSensitivePerMinute = 10,
    maxJsonBytes = 1_048_576L,
    maxUploadBytes = 10_485_760L,
    maxConcurrentUploads = 4,
    minClientVersion = null,
    // cmpose:sharing
    inviteTtlDays = 7,
    // /cmpose:sharing
    publicBaseUrl = null,
    jwtClockSkewSeconds = 60L,
    dbPoolSize = 5,
    appVersion = appVersion,
)

/** Knows no keys; for tests that never send a token. */
private val noKeys = object : JwkProvider {
    override fun get(keyId: String): Jwk = throw SigningKeyNotFoundException("no keys in this test", null)
}

fun testDeps(db: Database? = null, config: AppConfig = testConfig(), jwkProvider: JwkProvider = noKeys) =
    AppDeps(config, jwkProvider, db)

/** The test database plus [TestKeys]' signing key: the setup for every authenticated route test. */
fun authDeps(jwkProvider: JwkProvider = TestKeys.jwkProvider, config: AppConfig = testConfig()) =
    testDeps(TestDb.database, config, jwkProvider)
