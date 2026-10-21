# Security checklist for a new feature

Short rules for adding a record type or a route.
<!-- cmpose:sample -->
The sample feature (`feature/sample`, `V3__sample.sql` and its tests) follows every one of them: copy its shape.
<!-- /cmpose:sample -->

## Access

1. Every route calls `call.requireUser()` before it reads the body or looks anything up. No token, or a bad one, is 401.
<!-- cmpose:sharing -->
1. A route on a space's data calls `call.requireRole(spaces, spaceId, Role.VIEWER)` to read and `Role.EDITOR` to
   write, then uses the returned `membership.spaceId`, never the raw parameter. For a row looked up by id, load it
   first and check the role in the row's own space.
<!-- /cmpose:sharing -->
1. Every query on user data filters by its owner (`owner_id` = the caller), in the same query that reads or changes
   the row. Never fetch by id and check ownership later against a different value.
<!-- cmpose:sharing -->
1. With sharing, the filter is the space `requireRole` returned (`space_id`).
<!-- /cmpose:sharing -->
1. A row the caller may not see answers 404 `NOT_FOUND`, exactly like a missing row: never 403, never another message.
   A path id that is not a UUID is a 404 too.
1. Lists page with `limit` (1..200) and an opaque cursor; never return an unbounded list. The owner filter applies to
   every page: a cursor is only a position.

## Input

1. Each request body is a `@Serializable` DTO holding only the fields a client may set (never the id, the owner, the
   times or a role). The JSON setup rejects unknown fields with a 400, which blocks mass assignment.
1. Validate every field at the boundary (length in characters, ranges, formats) and answer 400 with `fields`, all
   invalid fields at once. Repeat the key limits as `CHECK` constraints in the migration.
1. Bodies are capped globally (`MAX_JSON_BYTES`, `MAX_UPLOAD_BYTES`); keep a route under them. Files go through the
   file routes, which decide the type from the bytes and name the file themselves.
1. Queries use Exposed's DSL or bound parameters. Never build SQL with string templates or concatenation; a table or
   column name chosen from input comes from an allow-list. Never pass input to a shell, `eval`, a template or a path.
1. An outbound request built from input (webhook, link preview) needs a host allow-list (SSRF); a redirect target
   from input must be checked against an allow-list too.

## Data lifecycle

1. A new table points at its owner with `REFERENCES users(id) ON DELETE CASCADE`, so account deletion
   (`DELETE /api/v1/me`) removes its rows. A file the rows need goes through the `files` table, so it leaves the disk.
<!-- cmpose:sharing -->
1. With sharing, the table points at `spaces(id) ON DELETE CASCADE` instead: deleting the space, or its owner's
   account, removes the rows.
1. What a user added to someone else's space stays in that space when the user deletes their account; only the link
   to them goes. A column naming who added a row is `REFERENCES users(id) ON DELETE SET NULL` (as `files.uploaded_by`),
   never `CASCADE`, which would delete shared data, and never a copied name or email, which would outlive the account
   (`SharingDeletionTest`). The privacy notes in `deploy/README.md` say so; keep the app's privacy policy in step.
1. An invite preview shows whoever holds the token the space name, the role and the inviter's display name, and keeps
   showing them after the invite is used, revoked or expires; only an unknown token is a 404. Add nothing else to the
   preview. A revoked invite keeps its row and previews as `revoked`, also once it has expired; accepting it is 410
   `INVITE_REVOKED`.
1. An OWNER lists a space's pending invites (`GET /api/v1/spaces/{id}/invites`) and revokes one
   (`DELETE /api/v1/spaces/{id}/invites/{inviteId}`). The list never shows a token or its hash; the token is shown
   once, when the invite is created. The revoke's log line names no invite, space or token. The revoke runs under
   `rateLimit(SENSITIVE_LIMIT)`; the list is a read, like the members list, and only the global limit counts it.
<!-- /cmpose:sharing -->
1. Schema changes are new Flyway migrations (`V<n>__<name>.sql`); never edit one that has run anywhere.
1. Responses are DTOs built for the client. Never serialize table rows or internal objects, and never return another
   user's email or id unless the feature needs it.
1. Only a verified email is stored or returned (`VerifiedIdentity.emailVerified`); never trust an unverified one for
   display, matching or access.

## Errors and logs

1. Fail with `notFound()`, `badRequest(...)` or an `ApiException`. Never put exception messages, SQL or stack traces in
   a response: an unexpected error is a 500 `INTERNAL` carrying only the request id.
1. Never log tokens, passwords, emails, request bodies or other personal data. The request log already has the request
   id and the user id.
1. Secrets come from the environment through `AppConfig`, never from code or the repository.

## Abuse

1. A costly or security-sensitive route (upload, deletion, invitations, anything sign-in-like) runs under
   `rateLimit(SENSITIVE_LIMIT)` and writes an audit log line without personal data. A route that holds a large body in
   memory also caps how many run at once (as uploads do with `MAX_CONCURRENT_UPLOADS`).
1. A change that must happen once (a payment, a counter, a single-use token) is idempotent and safe under races: check
   and change in one transaction with `SELECT … FOR UPDATE`, or in one conditional `UPDATE`.
1. A new write path that adds to something a quota counts checks the quota in the same locked transaction as the
   write (lock the parent row with `SELECT … FOR NO KEY UPDATE`, Exposed's
   `forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)`, count, then insert) and fails with `quotaExceeded(...)`.
   A plain `FOR UPDATE` would block the key-share lock a foreign-key check takes on that row and can deadlock two
   writes that point at each other's locked row.
1. Nothing changes state on a GET. The API authenticates with bearer tokens, not cookies, so CSRF does not apply; a
   feature that adds cookie sign-in adds CSRF protection with it.

## Tests

1. Every route gets an IDOR test: another user's GET, PATCH and DELETE answer 404 and change nothing.
<!-- cmpose:sharing -->
1. With sharing, also a VIEWER's write and a member of another space, both 404.
<!-- /cmpose:sharing -->
1. Every validation rule gets a negative test: empty, too long, wrong type, unknown and server-owned fields.
<!-- cmpose:sample -->
1. The sample feature has both kinds: `feature/sample/ItemIdorTest` and `feature/sample/ItemValidationTest`.
<!-- /cmpose:sample -->
1. Keep the API description (`openapi/OpenApiSpec.kt`) in step with the routes; `OpenApiTest` checks the path list
   and that every `$ref` resolves. Run `./gradlew test detekt` before shipping.

---

Some rules are adapted from the attack classes, data isolation and lifecycle, and web protocol and auth guides
(`ATTACK-CLASSES.md`, `DATA-ISOLATION-AND-LIFECYCLE.md`, `WEB-PROTOCOL-AND-AUTH.md`) of Cloudflare's
[security-audit-skill](https://github.com/cloudflare/security-audit-skill), used under the MIT License.
