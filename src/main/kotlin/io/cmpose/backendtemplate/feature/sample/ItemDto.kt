package io.cmpose.backendtemplate.feature.sample

import io.cmpose.backendtemplate.plugins.FieldError
import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID

/** Longest title in characters, after trimming; the schema checks it too. */
const val MAX_TITLE = 200

/** Longest note in characters; the schema checks it too. */
const val MAX_NOTE = 2000

/** An item as the API returns it; [dueDate], [createdAt] and [updatedAt] are epoch milliseconds. */
@Serializable
data class ItemResponse(
    val id: String,
    // cmpose:sharing
    val spaceId: String,
    // /cmpose:sharing
    val title: String,
    val note: String?,
    val dueDate: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One page of items, newest first; [nextCursor] is null on the last page. */
@Serializable
data class ItemPage(val items: List<ItemResponse>, val nextCursor: String?)

/**
 * A new item. Only these fields bind: the server sets the id and the times, and an unknown field is a 400. A missing
 * [title] is reported like an empty one, with its field.
 */
@Serializable
data class ItemDraft(
    val title: String = "",
    val note: String? = null,
    val dueDate: Long? = null,
    // cmpose:sharing
    // Without it the item goes to the caller's personal space.
    val spaceId: String? = null,
    // /cmpose:sharing
)

/** A change to an item: a field left out stays as it is; `null` clears [note] or [dueDate]. */
@Serializable
data class ItemPatch(val title: String? = null, val note: String? = null, val dueDate: Long? = null)

/** An item row; [title] is already trimmed and checked. */
data class Item(
    val id: UUID,
    // cmpose:sharing
    val spaceId: UUID,
    // /cmpose:sharing
    val title: String,
    val note: String?,
    val dueDate: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toResponse() = ItemResponse(
        id = id.toString(),
        // cmpose:sharing
        spaceId = spaceId.toString(),
        // /cmpose:sharing
        title = title,
        note = note,
        dueDate = dueDate,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** Where the page after this item starts. */
    fun cursor() = ItemCursor(createdAt, id)
}

private fun String.characters() = codePointCount(0, length)

/** Every field of an item that breaks a rule; [title] is the trimmed one. Empty when the item is valid. */
fun itemErrors(title: String, note: String?, dueDate: Long?): List<FieldError> = buildList {
    if (title.isEmpty()) add(FieldError("title", "required"))
    if (title.characters() > MAX_TITLE) add(FieldError("title", "at most $MAX_TITLE characters"))
    if (note != null && note.characters() > MAX_NOTE) add(FieldError("note", "at most $MAX_NOTE characters"))
    if (dueDate != null && dueDate < 0) add(FieldError("dueDate", "must not be negative"))
}

/**
 * A position in the newest-first order: the last item of a page. On the wire it is opaque (URL-safe Base64), so
 * clients pass it back unchanged and the format may change.
 */
data class ItemCursor(val createdAt: Long, val id: UUID) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt:$id".toByteArray())

    companion object {
        /** The cursor in [text], or null when it is not one this server issued. */
        fun decode(text: String): ItemCursor? = runCatching {
            val (time, id) = String(Base64.getUrlDecoder().decode(text)).split(':', limit = 2)
            ItemCursor(time.toLong(), UUID.fromString(id))
        }.getOrNull()
    }
}
