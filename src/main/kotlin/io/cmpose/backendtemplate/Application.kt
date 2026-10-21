@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate

import com.auth0.jwk.JwkProvider
import io.cmpose.backendtemplate.auth.FirebaseTokenVerifier
import io.cmpose.backendtemplate.auth.PublishedKeyIds
import io.cmpose.backendtemplate.auth.UserAuth
import io.cmpose.backendtemplate.auth.installUserAuth
import io.cmpose.backendtemplate.config.AppConfig
import io.cmpose.backendtemplate.feature.sample.ItemRepository
import io.cmpose.backendtemplate.feature.sample.itemRoutes
import io.cmpose.backendtemplate.files.FileRepository
import io.cmpose.backendtemplate.files.FileStore
import io.cmpose.backendtemplate.files.LocalDiskFileStore
import io.cmpose.backendtemplate.files.fileRoutes
import io.cmpose.backendtemplate.health.healthRoutes
import io.cmpose.backendtemplate.openapi.openApiRoutes
import io.cmpose.backendtemplate.plugins.installClientVersion
import io.cmpose.backendtemplate.plugins.installErrors
import io.cmpose.backendtemplate.plugins.installObservability
import io.cmpose.backendtemplate.plugins.installRateLimit
import io.cmpose.backendtemplate.plugins.installSecurity
import io.cmpose.backendtemplate.sharing.InviteRepository
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.cmpose.backendtemplate.sharing.inviteRoutes
import io.cmpose.backendtemplate.sharing.memberRoutes
import io.cmpose.backendtemplate.sharing.spaceRoutes
import io.cmpose.backendtemplate.users.UserRepository
import io.cmpose.backendtemplate.users.userRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path

/**
 * What the application needs from outside: settings, Google's signing keys, the database (absent in some tests),
 * where uploaded files go (by default the local disk at `FILES_DIR`) and Google's published kids (see
 * [FirebaseTokenVerifier]; null in tests, which then look up one kid at a time).
 */
class AppDeps(
    val config: AppConfig,
    val jwkProvider: JwkProvider,
    val db: Database?,
    val fileStore: FileStore = LocalDiskFileStore(Path.of(config.filesDir)),
    val jwkKeyIds: PublishedKeyIds? = null,
)

fun Application.module(deps: AppDeps) {
    install(ContentNegotiation) {
        json(
            Json {
                // A field the server does not know is a client error, not something to drop silently.
                ignoreUnknownKeys = false
            }
        )
    }
    val config = deps.config
    installObservability(config)
    installErrors()
    installSecurity(config)
    installClientVersion(config)
    // cmpose:sharing
    val spaces = deps.db?.let { SpaceRepository(it, config.maxSpacesPerUser, config.maxMembersPerSpace) }
    val invites = deps.db?.let { InviteRepository(it, config.inviteTtlDays, config.maxPendingInvitesPerSpace) }
    // /cmpose:sharing
    // cmpose:sharing-off: val users = deps.db?.let(::UserRepository)
    val users = deps.db?.let { db -> UserRepository(db, onCreated = { SpaceRepository(db).personalSpace(it) }) }
    val files = deps.db?.let { FileRepository(it, config.maxStorageBytesPerUser) }
    // cmpose:sample
    val items = deps.db?.let(::ItemRepository)
    // /cmpose:sample
    if (users != null) {
        val verifier = FirebaseTokenVerifier(
            deps.jwkProvider,
            config.firebaseProjectId,
            config.jwtClockSkewSeconds,
            publishedKeyIds = deps.jwkKeyIds,
        )
        installUserAuth(UserAuth(verifier, users))
    }
    installRateLimit(config)
    routing {
        healthRoutes(config.appVersion, deps.db)
        openApiRoutes(config.publicBaseUrl)
        // Without a database only the health routes and the API description exist (some tests build the app that way).
        // Every repository exists exactly when the database does; the checks only unwrap them.
        // cmpose:sharing-off: if (users != null && files != null) {
        if (users != null && files != null && spaces != null) {
            // cmpose:sharing-off: userRoutes(users, files, deps.fileStore)
            userRoutes(users, files, deps.fileStore, spaces)
            // cmpose:sharing-off: fileRoutes(files, deps.fileStore, config.maxUploadBytes, config.maxConcurrentUploads)
            fileRoutes(files, deps.fileStore, config.maxUploadBytes, config.maxConcurrentUploads, spaces)
            // cmpose:sample
            // cmpose:sharing-off: items?.let { itemRoutes(it) }
            items?.let { itemRoutes(it, spaces) }
            // /cmpose:sample
            // cmpose:sharing
            spaceRoutes(spaces, deps.fileStore)
            memberRoutes(spaces)
            invites?.let { inviteRoutes(spaces, it) }
            // /cmpose:sharing
        }
    }
}
