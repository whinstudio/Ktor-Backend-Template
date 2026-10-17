package io.cmpose.backendtemplate

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.cmpose.backendtemplate.config.AppConfig
import io.cmpose.backendtemplate.config.ConfigException
import io.cmpose.backendtemplate.db.connectDatabase
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private const val GOOGLE_JWKS =
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
private const val JWKS_CACHE_SIZE = 10L
private const val JWKS_CACHE_HOURS = 6L
private const val JWKS_FETCHES_PER_MINUTE = 10L

/** Google's signing keys for Firebase ID tokens, cached and rate limited (also used by the local runner). */
fun googleJwkProvider(): JwkProvider = JwkProviderBuilder(URI(GOOGLE_JWKS).toURL())
    .cached(JWKS_CACHE_SIZE, JWKS_CACHE_HOURS, TimeUnit.HOURS)
    .rateLimited(JWKS_FETCHES_PER_MINUTE, 1, TimeUnit.MINUTES)
    .build()

fun main() {
    val log = LoggerFactory.getLogger("Main")
    val config = try {
        AppConfig.fromEnv()
    } catch (e: ConfigException) {
        // A clear startup error that names the variables, never their values.
        log.error(e.message)
        exitProcess(1)
    }
    val db = try {
        connectDatabase(config)
    } catch (e: Exception) {
        // The class name only: a connection error's message can quote the URL or the user.
        log.error("Database unavailable at startup: {}", e::class.java.name)
        exitProcess(1)
    }
    embeddedServer(Netty, port = config.port) { module(AppDeps(config, googleJwkProvider(), db)) }
        .start(wait = true)
}
