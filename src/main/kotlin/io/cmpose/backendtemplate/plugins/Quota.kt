package io.cmpose.backendtemplate.plugins

import io.ktor.http.HttpStatusCode

/** A limit set in `AppConfig`; [wire] is the `quota` value of its 409 `QUOTA_EXCEEDED`. */
enum class Quota(val wire: String) {
    STORAGE("storage"),
    // cmpose:sharing
    SPACES("spaces"),
    MEMBERS("members"),
    INVITES("invites"),
    // /cmpose:sharing
}

/** A 409 `QUOTA_EXCEEDED` naming the [quota] that was reached; the limit itself stays out of the body. */
fun quotaExceeded(quota: Quota): Nothing {
    val message = when (quota) {
        Quota.STORAGE -> "Storage quota reached"
        // cmpose:sharing
        Quota.SPACES -> "Space limit reached"
        Quota.MEMBERS -> "This space is full"
        Quota.INVITES -> "Too many pending invites"
        // /cmpose:sharing
    }
    throw ApiException(HttpStatusCode.Conflict, "QUOTA_EXCEEDED", message, quota = quota.wire)
}
