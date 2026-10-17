package io.cmpose.backendtemplate

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class BodyLimitTest {
    private val config = testConfig().copy(maxJsonBytes = 64, maxUploadBytes = 256)

    private fun json(size: Int) = """{"name":"${"a".repeat(size - 11)}"}""".also { check(it.length == size) }

    /** A body sent without `Content-Length` (chunked), so only counting while reading can catch it. */
    private class Chunked(private val bytes: ByteArray, override val contentType: ContentType) :
        OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(channel: ByteWriteChannel) {
            bytes.toList().chunked(16).forEach { channel.writeFully(it.toByteArray()) }
        }
    }

    private suspend fun HttpResponse.errorCode() =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(testDeps(config = config))
            testRoutes()
        }
        block()
    }

    @Test
    fun jsonWithinLimitPasses() = withApp {
        val response = client.post("/test/echo") {
            contentType(ContentType.Application.Json)
            setBody(json(64))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun declaredJsonOverLimitIs413() = withApp {
        val response = client.post("/test/echo") {
            contentType(ContentType.Application.Json)
            setBody(json(65))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("PAYLOAD_TOO_LARGE", response.errorCode())
    }

    @Test
    fun chunkedJsonOverLimitIs413() = withApp {
        val response = client.post("/test/echo") {
            setBody(Chunked(json(200).toByteArray(), ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("PAYLOAD_TOO_LARGE", response.errorCode())
    }

    @Test
    fun chunkedJsonWithinLimitPasses() = withApp {
        val response = client.post("/test/echo") {
            setBody(Chunked(json(60).toByteArray(), ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun multipartUsesTheUploadLimit() = withApp {
        val multipart = ContentType.MultiPart.FormData.withParameter("boundary", "b")
        val within = client.post("/test/upload") {
            contentType(multipart)
            setBody(ByteArray(200))
        }
        assertEquals(HttpStatusCode.OK, within.status)
        assertEquals("200", within.bodyAsText())
        val over = client.post("/test/upload") {
            contentType(multipart)
            setBody(ByteArray(257))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, over.status)
        val chunkedOver = client.post("/test/upload") { setBody(Chunked(ByteArray(300), multipart)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunkedOver.status)
    }

    @Test
    fun rawBodyOverJsonLimitIs413() = withApp {
        val response = client.post("/test/upload") { setBody(Chunked(ByteArray(100), ContentType.Application.OctetStream)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }
}
