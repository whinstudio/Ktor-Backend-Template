package io.cmpose.backendtemplate.files

import io.cmpose.backendtemplate.plugins.describe
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log

/**
 * Removes files from disk after their rows are gone. A failure does not fail the request: it is logged by stored name
 * (random, see [LocalDiskFileStore]) and exception class names only, never a user id or the exception's message.
 */
internal fun ApplicationCall.removeFiles(store: FileStore, paths: List<String>) {
    val log = application.log
    paths.forEach { path ->
        runCatching { store.delete(path) }
            .onFailure { log.warn("Could not delete file {}: {}", path, describe(it)) }
    }
}
