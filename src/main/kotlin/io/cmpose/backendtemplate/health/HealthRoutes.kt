package io.cmpose.backendtemplate.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

@Serializable
data class HealthResponse(val status: String, val version: String)

@Serializable
data class ReadyResponse(val status: String)

/**
 * `/health` is liveness: no dependency is checked. `/ready` pings [db] and answers 503 without a database or when the
 * ping fails; the compose healthcheck and the deploy script use it.
 */
fun Route.healthRoutes(appVersion: String, db: Database?, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    get("/health") { call.respond(HealthResponse("ok", appVersion)) }
    get("/ready") {
        val ready = db != null && ping(db, dispatcher)
        if (ready) {
            call.respond(ReadyResponse("ready"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, ReadyResponse("unavailable"))
        }
    }
}

private suspend fun Route.ping(db: Database, dispatcher: CoroutineDispatcher): Boolean = try {
    newSuspendedTransaction(dispatcher, db) { exec("SELECT 1") }
    true
} catch (e: Exception) {
    // The class name only: a database error's message can quote connection details.
    application.log.warn("Readiness ping failed: {}", e::class.java.name)
    false
}
