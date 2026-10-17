@file:Suppress("MatchingDeclarationName") // Named after what the file sets up, not after one of its declarations.

package io.cmpose.backendtemplate.files

import io.cmpose.backendtemplate.auth.requireUser
import io.cmpose.backendtemplate.plugins.ApiException
import io.cmpose.backendtemplate.plugins.BodyTooLargeKey
import io.cmpose.backendtemplate.plugins.FieldError
import io.cmpose.backendtemplate.plugins.SENSITIVE_LIMIT
import io.cmpose.backendtemplate.plugins.badRequest
import io.cmpose.backendtemplate.plugins.notFound
import io.cmpose.backendtemplate.sharing.Role
import io.cmpose.backendtemplate.sharing.SpaceRepository
import io.cmpose.backendtemplate.sharing.requireRole
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.UUID

private const val FILE_FIELD = "file"
private const val UPLOADS_BUSY_RETRY_SECONDS = 5

// cmpose:sharing
private const val SPACE_FIELD = "spaceId"
// /cmpose:sharing

/** The answer to an upload: the new file's [id], its sniffed [mime] type and its [size] in bytes. */
@Serializable
data class FileResponse(val id: String, val mime: String, val size: Long)

private fun unsupported(message: String): Nothing =
    throw ApiException(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", message)

private fun tooLarge(): Nothing =
    throw ApiException(HttpStatusCode.PayloadTooLarge, "PAYLOAD_TOO_LARGE", "Request body is too large")

/**
 * The bytes of the first `file` part; other parts are skipped. The body limit (see `installSecurity`) has already
 * capped the whole body at [maxBytes], so a part cannot be larger; the check here only guards that assumption.
 */
private suspend fun ApplicationCall.receiveFile(
    maxBytes: Long,
    // cmpose:sharing
    onSpaceId: (String) -> Unit,
    // /cmpose:sharing
): ByteArray {
    if (!request.contentType().match(ContentType.MultiPart.FormData)) unsupported("Expected a multipart/form-data body")
    var bytes: ByteArray? = null
    try {
        receiveMultipart(formFieldLimit = maxBytes).forEachPart { part ->
            if (part is PartData.FileItem && part.name == FILE_FIELD && bytes == null) {
                bytes = part.provider().readRemaining(maxBytes + 1).readByteArray()
            }
            // cmpose:sharing
            if (part is PartData.FormItem && part.name == SPACE_FIELD) onSpaceId(part.value)
            // /cmpose:sharing
            part.release()
        }
    } catch (e: IOException) {
        // The body limit's cut is answered 413 by the error handler; anything else is a broken body, not a 500.
        if (attributes.contains(BodyTooLargeKey)) throw e
        badRequest("Malformed multipart body")
    }
    val data = bytes ?: badRequest("No file", listOf(FieldError(FILE_FIELD, "required")))
    if (data.size > maxBytes) tooLarge()
    return data
}

/**
 * Runs [block] holding one of [uploads]' permits, so at most that many upload bodies are in memory at once. With none
 * free the answer is at once a 503 `UPLOADS_BUSY` with `Retry-After`; nobody waits for a permit.
 */
private suspend inline fun <T> ApplicationCall.withUploadPermit(uploads: Semaphore, block: () -> T): T {
    if (!uploads.tryAcquire()) {
        response.header(HttpHeaders.RetryAfter, UPLOADS_BUSY_RETRY_SECONDS.toString())
        throw ApiException(HttpStatusCode.ServiceUnavailable, "UPLOADS_BUSY", "Too many uploads, try again shortly")
    }
    try {
        return block()
    } finally {
        uploads.release()
    }
}

/** The `{id}` path parameter as a file id; anything but a UUID names no file (404). */
private fun ApplicationCall.fileId(): UUID =
    runCatching { UUID.fromString(parameters["id"].orEmpty()) }.getOrNull() ?: notFound()

// cmpose:sharing
/** The file named by `{id}` if the caller's role in its space is at least [atLeast]; otherwise 404. */
private suspend fun ApplicationCall.requireFile(files: FileRepository, spaces: SpaceRepository, atLeast: Role) =
    (files.get(fileId()) ?: notFound()).also { requireRole(spaces, it.spaceId.toString(), atLeast) }
// /cmpose:sharing

/**
 * Files: upload (multipart, field `file`, at most `MAX_UPLOAD_BYTES`), download and delete. The type comes from the
 * bytes ([ContentSniffer]), never from the name or header; anything else is a 415. A file the caller may not see is a
 * 404, the same as a missing one. Uploads count against [SENSITIVE_LIMIT], and at most [maxConcurrentUploads] run at
 * once (see [withUploadPermit]).
 */
fun Route.fileRoutes(
    files: FileRepository,
    store: FileStore,
    maxUploadBytes: Long,
    maxConcurrentUploads: Int,
    // cmpose:sharing
    spaces: SpaceRepository,
    // /cmpose:sharing
) {
    val uploads = Semaphore(maxConcurrentUploads)
    // cmpose:sharing-off: // Each file belongs to the user who uploaded it; another user's file is a 404.
    // A file belongs to a space, named by the upload's `spaceId` field (else the personal space): EDITOR uploads.
    rateLimit(SENSITIVE_LIMIT) {
        post("/api/v1/files") {
            val user = call.requireUser()
            call.withUploadPermit(uploads) {
                // cmpose:sharing
                var spaceField: String? = null
                // /cmpose:sharing
                // cmpose:sharing-off: val data = call.receiveFile(maxUploadBytes)
                val data = call.receiveFile(maxUploadBytes) { spaceField = it }
                // cmpose:sharing
                val spaceId = spaceField ?: spaces.personalSpace(user).toString()
                val membership = call.requireRole(spaces, spaceId, Role.EDITOR)
                // /cmpose:sharing
                val mime = ContentSniffer.detect(data) ?: unsupported("Only JPEG, PNG, WebP or PDF files")
                val path = store.write(data)
                // cmpose:sharing-off: val file = StoredFile(UUID.randomUUID(), user, path, mime, data.size.toLong())
                val file = StoredFile(UUID.randomUUID(), membership.spaceId, user, path, mime, data.size.toLong())
                // Without its row nothing would ever remove the file (the account may have been deleted meanwhile).
                runCatching { files.add(file) }
                    .onFailure { call.removeFiles(store, listOf(file.path)) }
                    .getOrThrow()
                call.respond(HttpStatusCode.Created, FileResponse(file.id.toString(), mime, file.size))
            }
        }
    }
    // cmpose:sharing-off: // Reading and deleting check the owner.
    // Any member downloads; EDITOR deletes.
    get("/api/v1/files/{id}") {
        // cmpose:sharing-off: val user = call.requireUser()
        call.requireUser()
        // cmpose:sharing-off: val stored = files.get(call.fileId(), user) ?: notFound()
        val stored = call.requireFile(files, spaces, Role.VIEWER)
        val bytes = store.read(stored.path) ?: notFound()
        // Downloaded, never rendered in a browser tab; the type is the sniffed one from the database.
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.toString())
        call.respondBytes(bytes, ContentType.parse(stored.mime))
    }
    delete("/api/v1/files/{id}") {
        // cmpose:sharing-off: val user = call.requireUser()
        call.requireUser()
        // cmpose:sharing-off: val stored = files.get(call.fileId(), user) ?: notFound()
        val stored = call.requireFile(files, spaces, Role.EDITOR)
        // cmpose:sharing-off: if (files.delete(stored.id, user)) call.removeFiles(store, listOf(stored.path))
        if (files.delete(stored.id)) call.removeFiles(store, listOf(stored.path))
        call.respond(HttpStatusCode.NoContent)
    }
}

