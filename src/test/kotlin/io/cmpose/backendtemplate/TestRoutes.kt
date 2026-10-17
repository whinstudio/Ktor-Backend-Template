package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.plugins.FieldError
import io.cmpose.backendtemplate.plugins.SENSITIVE_LIMIT
import io.cmpose.backendtemplate.plugins.badRequest
import io.cmpose.backendtemplate.plugins.notFound
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.Serializable

/** The JSON body of `POST /test/echo`. */
@Serializable
data class Echo(val name: String)

/** Routes that exist only in tests, to drive the HTTP layer before real routes use it. */
fun Application.testRoutes() {
    routing {
        get("/test/ping") { call.respondText("pong") }
        post("/test/echo") { call.respond(call.receive<Echo>()) }
        post("/test/upload") { call.respondText(call.receiveChannel().toByteArray().size.toString()) }
        get("/test/boom") { error("db password=x") }
        get("/test/missing") { notFound() }
        get("/test/invalid") { badRequest("Invalid input", listOf(FieldError("name", "must not be blank"))) }
        get("/test/whoami") { call.respondText(call.requireUser().toString()) }
        rateLimit(SENSITIVE_LIMIT) {
            get("/test/sensitive") { call.respondText("ok") }
        }
    }
}
