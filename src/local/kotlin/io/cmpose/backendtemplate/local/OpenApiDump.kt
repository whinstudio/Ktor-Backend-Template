package io.cmpose.backendtemplate.local

import io.cmpose.backendtemplate.openapi.OpenApi
import kotlinx.serialization.json.Json
import java.io.File

private val prettyJson = Json { prettyPrint = true }

/** `./gradlew writeOpenApi` — writes the served API description (no public base URL) to args[0]. */
fun main(args: Array<String>) {
    val file = File(args.first())
    file.absoluteFile.parentFile.mkdirs()
    file.writeText(prettyJson.encodeToString(OpenApi.document(null)) + "\n")
}
