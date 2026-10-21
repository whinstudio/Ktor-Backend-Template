package io.cmpose.backendtemplate.local

import com.auth0.jwk.JwkProvider
import io.cmpose.backendtemplate.AppDeps
import io.cmpose.backendtemplate.auth.PublishedKeyIds
import io.cmpose.backendtemplate.config.AppConfig
import io.cmpose.backendtemplate.db.connectDatabase
import io.cmpose.backendtemplate.module
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

private const val STOP_GRACE_MS = 1_000L
private const val STOP_TIMEOUT_MS = 3_000L
private const val LOCAL_VERSION = "local"

/**
 * The local server's settings: the production defaults, read through [AppConfig.fromEnv], with the embedded
 * database, files in `<dir>/files`, version `local` and neither CORS origins nor a minimum client version.
 */
fun localConfig(
    settings: LocalSettings,
    jdbcUrl: String,
    user: String,
    password: String,
    dir: File
): AppConfig = AppConfig.fromEnv(
    mapOf(
        "PORT" to settings.port.toString(),
        "DATABASE_URL" to jdbcUrl,
        "DATABASE_USER" to user,
        "DATABASE_PASSWORD" to password,
        "FIREBASE_PROJECT_ID" to settings.firebaseProjectId,
        "FILES_DIR" to dir.resolve("files").absolutePath,
        "APP_VERSION" to LOCAL_VERSION
    )
)

/** The running local stack; closing stops Ktor first, then PostgreSQL. */
class LocalServer private constructor(
    private val engine: EmbeddedServer<*, *>,
    private val database: LocalDatabase,
    private val pidFile: File
) : AutoCloseable {
    override fun close() {
        engine.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        database.close()
        pidFile.delete()
    }

    companion object {
        /**
         * Starts PostgreSQL, migrates it and serves on loopback. Every failure is a [LocalSetupException];
         * whatever had started by then (Ktor, PostgreSQL) is stopped first, so no process keeps the data folder.
         * [keyIds] is Google's published key set (see `AppDeps.jwkKeyIds`); tests leave it out.
         */
        fun start(
            dir: File,
            settings: LocalSettings,
            jwks: JwkProvider,
            out: (String) -> Unit,
            keyIds: PublishedKeyIds? = null
        ): LocalServer {
            if (!PortCheck.isFree(settings.port)) {
                throw LocalSetupException("Port ${settings.port} is in use:\n${PortCheck.holder(settings.port)}")
            }
            val database = startDatabase(dir)
            return try {
                serve(dir, settings, jwks, keyIds, out, database)
            } catch (e: Exception) {
                database.close()
                throw setupFailure("The local server did not start", e)
            }
        }

        private fun startDatabase(dir: File): LocalDatabase = try {
            LocalDatabase.start(dir)
        } catch (e: Exception) {
            throw setupFailure("Embedded PostgreSQL did not start in ${dir.resolve("postgres").path}", e)
        }

        private fun serve(
            dir: File,
            settings: LocalSettings,
            jwks: JwkProvider,
            keyIds: PublishedKeyIds?,
            out: (String) -> Unit,
            database: LocalDatabase
        ): LocalServer {
            val config = localConfig(settings, database.jdbcUrl, database.user, database.password, dir)
            File(config.filesDir).mkdirs()
            // Migrated before Ktor starts, so a Flyway failure never leaves a half-started engine behind.
            val db = connectDatabase(config)
            val engine = embeddedServer(Netty, port = settings.port, host = "127.0.0.1") {
                module(AppDeps(config, jwks, db, jwkKeyIds = keyIds))
            }
            try {
                engine.start(wait = false)
                val pidFile = dir.resolve("server.pid").apply { writeText(ProcessHandle.current().pid().toString()) }
                out("LOCAL_SERVER_READY port=${settings.port}")
                return LocalServer(engine, database, pidFile)
            } catch (e: Exception) {
                engine.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
                throw e
            }
        }

        private fun setupFailure(
            what: String,
            cause: Exception
        ) = cause as? LocalSetupException
            ?: LocalSetupException("$what: ${cause.message ?: cause::class.simpleName}", cause)
    }
}
