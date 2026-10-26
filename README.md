# Cmpose Backend

A production-ready Kotlin/Ktor backend for a mobile app: Firebase sign-in verified on the server, PostgreSQL with
Flyway migrations, file uploads, account deletion, and the parts to deploy it as one container behind a shared nginx
and PostgreSQL.
<!-- cmpose:sample -->
One sample feature shows the shape to copy.
<!-- /cmpose:sample -->

- Kotlin on JVM 21, Ktor, Exposed, Flyway, PostgreSQL, HikariCP, logback.
- Sign-in: the app signs in with Firebase; the server checks each ID token's signature against Google's public keys,
  its audience, issuer and age. No Admin SDK and no service account.
- Tests run on an embedded PostgreSQL and sign their own tokens with a test key, so `./gradlew build` needs neither a
  database nor a network.

## Layout

| Path | What |
|------|------|
| `src/main/kotlin/.../config` | `AppConfig`: every setting, read from the environment |
| `src/main/kotlin/.../plugins` | errors, security headers, CORS, body limits, rate limits, request ids, client version |
| `src/main/kotlin/.../auth`, `users` | token verification, `requireUser()`, `GET` and `DELETE /api/v1/me`, the profile (`PUT /api/v1/me/profile`: display name) |
| `src/main/kotlin/.../files` | uploads (type read from the bytes) and downloads |
<!-- cmpose:sample -->
| `src/main/kotlin/.../feature/sample` | the sample feature (`/api/v1/items`, paged; `/api/v1/items/recent`, one response): the shape to copy |
<!-- /cmpose:sample -->
<!-- cmpose:sharing -->
| `src/main/kotlin/.../sharing` | spaces, roles and invitations |
<!-- /cmpose:sharing -->
| `src/main/kotlin/.../openapi` | the API description, served at `/api/v1/openapi.json` |
| `src/main/resources/db/migration` | Flyway migrations |
| `src/local/kotlin` | the local runner (embedded PostgreSQL); not in the release jar |
| `deploy/` | Dockerfile, compose fragment, nginx location, database script, `.env.example` |

## Run it locally

Needs JDK 21. The local server keeps everything in `.local-server/` (git-ignored):

1. Create `.local-server/config.properties` with `firebaseProjectId=<your Firebase project id>` (and optionally
   `port=<port>`; the default is `18001`).
2. `./gradlew runLocal` starts the server on `127.0.0.1` with an embedded PostgreSQL kept in `.local-server/`. It
   prints `LOCAL_SERVER_READY port=18001` when it accepts requests.
3. `curl http://127.0.0.1:18001/health` answers `{"status":"ok","version":"local"}`. Calls under `/api/v1` need an
   `Authorization: Bearer <Firebase ID token>` header from a signed-in app.

`./gradlew resetLocal` deletes `.local-server/` (database, files and settings). `./gradlew build` compiles, runs detekt
and the tests.

- `localServerDir=<path>` in `gradle.properties` (or `-PlocalServerDir=<path>`) moves the folder; the path is relative
  to this project. `runLocal` and `resetLocal` both use it, and the config file is then `<path>/config.properties`.
  The folder holds the local database and config, so it must stay git-ignored. It may not be this project or a folder
  that contains it, and an existing folder may hold only what the local server wrote (`config.properties`, `postgres/`,
  `files/`, `server.pid`); otherwise `runLocal` and `resetLocal` refuse it.
- `./gradlew writeOpenApi` writes the API description the server serves to `build/openapi/openapi.json`.

## API conventions

- All routes are under `/api/v1` except `/health` (liveness) and `/ready` (the database answers). The API description
  is `GET /api/v1/openapi.json`.
- Every error has one shape, with `fields` only for validation errors:

  ```json
  {"error":{"code":"BAD_REQUEST","message":"Invalid display name","requestId":"…","fields":[{"field":"displayName","message":"…"}]}}
  ```

  Codes: `BAD_REQUEST`, `UNAUTHORIZED`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `PAYLOAD_TOO_LARGE`,
  `UNSUPPORTED_MEDIA_TYPE`, `CLIENT_UPGRADE_REQUIRED`, `RATE_LIMITED`, `INTERNAL`, `AUTH_UNAVAILABLE`,
  `UPLOADS_BUSY`, `QUOTA_EXCEEDED` (409, with `quota` naming the limit reached: `storage`). A 500 carries no detail
  beyond the request id.
<!-- cmpose:sharing -->
  With sharing also: `LAST_OWNER` (409, a space needs at least one owner), and on accepting an invite `INVITE_USED`
  (409, already used; also on revoking a used one), `INVITE_REVOKED` (410, revoked by an owner) and `INVITE_EXPIRED`
  (410). `QUOTA_EXCEEDED`'s `quota` may also be `spaces`, `members` or `invites`.
<!-- /cmpose:sharing -->
- Headers:
  - Every response has `X-App-Version` (`APP_VERSION`) and `X-Request-Id`: the client's own id when it sends a safe
    one (letters, digits, `-`, `_`, `.`, at most 128 characters), else a generated one. Quote it when reporting a
    problem; the logs carry it.
  - The app sends `X-Client-Version`. Below `MIN_CLIENT_VERSION` the answer is 426 `CLIENT_UPGRADE_REQUIRED` with
    `minVersion`; a malformed value is 400; no header passes.
  - An app built before a server change may misread its new answers: one built before the quotas does not know
    `QUOTA_EXCEEDED` and shows a general error instead of the quota message.
<!-- cmpose:sharing -->
    One built before invite revocation shows a `revoked` invite preview as an unknown error, takes
    `QUOTA_EXCEEDED` on accepting an invite for "already used" (both are 409) and `INVITE_REVOKED` for "expired".
<!-- /cmpose:sharing -->
    After deploying such a server, raise `MIN_CLIENT_VERSION` to the first app release that knows the new answers,
    so older apps are sent to update.
  - A rate-limited request gets 429 `RATE_LIMITED` with `Retry-After`.
- A missing or invalid token is 401 `UNAUTHORIZED`. When Google's keys cannot be fetched and none is cached, the answer
  is 503 `AUTH_UNAVAILABLE`, so the app retries instead of signing out.
- **The 404 rule:** a row the caller may not see answers 404 `NOT_FOUND`, exactly like a row that does not exist. The
  server never says 403, so ids cannot be probed.
<!-- cmpose:sharing -->
  With sharing, a non-member of a space and a member whose role is too low both get 404.
<!-- /cmpose:sharing -->
- A list that can grow pages with `limit` and an opaque `cursor`; the response's `nextCursor` is `null` on the last
  page. A small list with a fixed upper bound may answer in one response. No list is unbounded.
<!-- cmpose:sample -->
  The sample feature has both: `GET /api/v1/items` pages, `GET /api/v1/items/recent` returns the newest 20 as one array.
<!-- /cmpose:sample -->
<!-- cmpose:sharing -->
- Invites: an OWNER lists a space's valid invites with `GET /api/v1/spaces/{id}/invites` (newest first, at most 200,
  not paged; `id`, `role`, `createdAt`, `expiresAt`, never the token) and revokes one with
  `DELETE /api/v1/spaces/{id}/invites/{inviteId}` (204, also when called again; 409 `INVITE_USED` once used; another
  space's invite or an id that is no UUID is 404). A revoked invite keeps previewing as `revoked`, even once it has
  expired, and stops counting against `MAX_PENDING_INVITES_PER_SPACE`. The list counts against the global rate limit
  only, as the members list does; the revoke, like creating, previewing and accepting, also against the sensitive one.
<!-- /cmpose:sharing -->

## Configuration

Every setting comes from the environment (`AppConfig`); the server refuses to start when a required one is missing or
invalid, naming it. Required: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `FIREBASE_PROJECT_ID`, `FILES_DIR`.
`deploy/.env.example` lists the optional ones with their defaults.

## Add a feature

<!-- cmpose:sample -->
Start a plain record type from the sample feature: `feature/sample` (table, repository, routes, DTOs), its migration
`V3__sample.sql` and its tests. It shows both list shapes: `GET /api/v1/items` pages (copy it for a list that can
grow), `GET /api/v1/items/recent` answers its newest 20 in one array (for a small list with a fixed bound). For any
other feature:
<!-- /cmpose:sample -->
Copy the shape of `files/` (repository, routes, migration, tests) and go through
[SECURITY-CHECKLIST.md](SECURITY-CHECKLIST.md).
The checklist covers authorization on every route, the owner filter in every query, validation at the boundary,
deletion with the account, rate limits, logs without personal data, and the IDOR and validation tests. Add the routes
to `openapi/OpenApiSpec.kt`; `OpenApiTest` keeps the two in step.

## Deploy

CI (`.github/workflows/ci.yml`) builds, tests and scans the code and publishes the image to GitHub's container
registry. [`deploy/README.md`](deploy/README.md) covers the server contract, the first deployment, updates and rollback.
