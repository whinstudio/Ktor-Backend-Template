package io.cmpose.backendtemplate.local

import io.cmpose.backendtemplate.openapi.OpenApi
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenApiDumpTest {
    private val dir = createTempDirectory("openapi-dump").toFile()

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    // `main` is called through the class `writeOpenApi` runs: a bare `main` here would resolve to LocalMain's.
    @Test fun dumpWritesTheServedDocument() {
        val file = dir.resolve("nested/openapi.json")
        Class.forName("io.cmpose.backendtemplate.local.OpenApiDumpKt")
            .getMethod("main", Array<String>::class.java)
            .invoke(null, arrayOf(file.path))
        assertEquals(OpenApi.document(null), Json.parseToJsonElement(file.readText()))
    }
}
