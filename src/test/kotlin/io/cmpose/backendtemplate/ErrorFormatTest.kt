package io.cmpose.backendtemplate

import ch.qos.logback.classic.Level
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every failure answers with `{"error":{"code","message","requestId"}}`; a 500 never carries detail. */
class ErrorFormatTest {
    private suspend fun HttpResponse.error(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject

    private suspend fun HttpResponse.errorCode() = error()["code"]!!.jsonPrimitive.content

    @Test
    fun unknownRouteIs404WithSharedBody() = testApplication {
        application { module(testDeps()) }
        val response = client.get("/nope") { header("X-Request-Id", "req-404") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("req-404", response.headers["X-Request-Id"])
        assertEquals(
            """{"error":{"code":"NOT_FOUND","message":"Not found","requestId":"req-404"}}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun generatedRequestIdIsTheOneInTheBody() = testApplication {
        application { module(testDeps()) }
        val response = client.get("/nope")
        assertEquals(response.headers["X-Request-Id"], response.error()["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun unexpectedErrorIs500WithoutDetail() {
        lateinit var body: String
        lateinit var status: HttpStatusCode
        val logs = capturedLogs {
            testApplication {
                application {
                    module(testDeps())
                    testRoutes()
                }
                val response = client.get("/test/boom") { header("X-Request-Id", "req-500") }
                status = response.status
                body = response.bodyAsText()
            }
        }
        assertEquals(HttpStatusCode.InternalServerError, status)
        assertEquals(
            """{"error":{"code":"INTERNAL","message":"Internal error","requestId":"req-500"}}""",
            body,
        )
        assertFalse(body.contains("password"))
        val errors = logs.filter { it.level == Level.ERROR }
        assertTrue(errors.any { it.formattedMessage.startsWith("Unhandled error: java.lang.IllegalStateException") })
        logs.forEach { assertFalse(it.fullText().contains("password"), "log leaks the message: ${it.fullText()}") }
    }

    @Test
    fun unknownJsonFieldIs400() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        val response = client.post("/test/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"a","admin":true}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.errorCode())
        assertEquals("Malformed request body", response.error()["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun malformedJsonIs400() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        val response = client.post("/test/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("BAD_REQUEST", response.errorCode())
    }

    @Test
    fun validJsonPasses() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        val response = client.post("/test/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"a"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"name":"a"}""", response.bodyAsText())
    }

    @Test
    fun wrongContentTypeIs415() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        val response = client.post("/test/echo") {
            contentType(ContentType.Text.Plain)
            setBody("name=a")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals("UNSUPPORTED_MEDIA_TYPE", response.errorCode())
    }

    @Test
    fun notFoundHelper() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        val response = client.get("/test/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", response.errorCode())
    }

    @Test
    fun badRequestHelperCarriesFields() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        val response = client.get("/test/invalid") { header("X-Request-Id", "req-400") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            """{"error":{"code":"BAD_REQUEST","message":"Invalid input","requestId":"req-400",""" +
                """"fields":[{"field":"name","message":"must not be blank"}]}}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun quotaExceededIs409WithTheQuota() {
        lateinit var error: JsonObject
        lateinit var status: HttpStatusCode
        val logs = capturedLogs {
            testApplication {
                application {
                    module(testDeps())
                    testRoutes()
                }
                val response = client.get("/test/quota")
                status = response.status
                error = response.error()
            }
        }
        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals("QUOTA_EXCEEDED", error["code"]!!.jsonPrimitive.content)
        assertEquals("Storage quota reached", error["message"]!!.jsonPrimitive.content)
        assertEquals("storage", error["quota"]!!.jsonPrimitive.content)
        assertTrue(error["requestId"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(logs.any { it.level == Level.INFO && it.formattedMessage == "Quota exceeded (storage)" })
    }

    @Test
    fun otherErrorsCarryNoQuota() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        // Like `fields`, an unset quota is left out of the body, not written as null.
        assertFalse("quota" in client.get("/test/invalid").error())
        assertFalse("quota" in client.get("/test/missing").error())
    }

    @Test
    fun wrongMethodIs405() = testApplication {
        application {
            module(testDeps())
            testRoutes()
        }
        val response = client.post("/test/ping")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals("METHOD_NOT_ALLOWED", response.errorCode())
    }
}
