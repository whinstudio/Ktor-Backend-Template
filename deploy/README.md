# Deploying cmpose-app

The app runs as one container behind the server repository's shared nginx and PostgreSQL. This folder holds the parts
that the server repository takes in; nothing shared is edited by hand to add an app.

| File | Goes to (server repository) |
|------|-----------------------------|
| `docker-compose.cmpose-app.yml` | `apps/docker-compose.cmpose-app.yml` |
| `nginx/cmpose-app.conf` | `/etc/nginx/apps/cmpose-app.conf` (the nginx config folder the server repository mounts) |
| `.env.example` | filled in as `apps/cmpose-app.env` |
| `sql/create-cmpose-app.sql` | copied to the server and run once (step 1), then deleted |
| `Dockerfile` | used by CI only; the image is `ghcr.io/<owner>/cmpose-app` |

The `Dockerfile` pins its base images by digest (`<image>:<tag>@sha256:…`), so a rebuild uses the same bytes until
Dependabot's `docker` updates (configured for `/deploy`) open a pull request that moves the digest.

All values below (`<owner>`, `<domain>`, passwords, project ids) are placeholders.

## Server repository contract

- nginx's main server block contains `include /etc/nginx/apps/*.conf;`. It owns TLS and HSTS.
- The main compose file defines the external networks `proxy` (nginx and the apps) and `db` (PostgreSQL and the
  apps). PostgreSQL is reachable on `db` as host `postgres`.
- App fragments are added with `docker compose -f docker-compose.yml -f apps/docker-compose.<app>.yml …` (paths then
  resolve from the server repository's root), or with `include:` and `project_directory: .` (Compose ≥ 2.20).
- Secrets live in `apps/<app>.env`. The server repository's `.gitignore` covers `apps/*.env`; the real files exist only
  on the server, with a copy in the owner's password manager.
- The top-level `.env` of the server repository sets `IMAGE_OWNER` (the GitHub owner, lower case) and optionally
  `IMAGE_TAG` and `CMPOSE_APP_MEM_LIMIT` (default `512m`).
- The image is `ghcr.io/${IMAGE_OWNER}/cmpose-app:${IMAGE_TAG:-latest}`. CI pushes `:<short sha>`, `:latest` (from
  `develop`) and `:<tag>` (from `v*` tags). If the package is private, log the server in once with
  `docker login ghcr.io` and a read-only (`read:packages`) token.
- `TRUSTED_PROXIES` defaults to Docker's whole private range (`172.16.0.0/12`). Narrow it to nginx alone: give the
  nginx container a fixed address on the `proxy` network (an `ipam` subnet on the network, `ipv4_address` on the
  service) and set `TRUSTED_PROXIES` to that address (`/32`) or to the `proxy` subnet. Otherwise another container
  on the same range could set `X-Forwarded-For` and pick the address the rate limiter counts.

## First deployment

Run from the server repository's root.

1. Create the database and its role, once. Copy the script to the server first (from your machine:
   `scp deploy/sql/create-cmpose-app.sql <server>:`), then on the server type the password into the shell instead of
   the command line or the shell history, and keep it for step 2:

   ```sh
   read -rs CMPOSE_APP_DB_PASSWORD
   export CMPOSE_APP_DB_PASSWORD
   docker exec -i -e CMPOSE_APP_DB_PASSWORD postgres psql -U postgres -f - < create-cmpose-app.sql
   unset CMPOSE_APP_DB_PASSWORD
   rm create-cmpose-app.sql
   ```

   `-e CMPOSE_APP_DB_PASSWORD` without a value hands the variable over from the shell, so the password never appears
   in a process list; the script reads it with `\getenv` (psql 15 or newer, as in the PostgreSQL 15+ images) and stops
   with an error, creating nothing, when it is empty. `postgres` is the PostgreSQL container's name; use yours.
   PostgreSQL must not log statements with their text (`log_statement` other than `ddl` / `all`), or the password
   ends up in its log.

2. Write the secrets: copy `.env.example` to `apps/cmpose-app.env`, fill in `DATABASE_PASSWORD` and
   `FIREBASE_PROJECT_ID` (see the Firebase checklist below), `chmod 600 apps/cmpose-app.env`, and store a copy in the
   password manager.
3. Copy the two files:

   ```sh
   cp <template>/deploy/docker-compose.cmpose-app.yml apps/
   cp <template>/deploy/nginx/cmpose-app.conf <nginx apps folder>/
   ```

4. Start the app and wait for it to become healthy (Flyway migrates the database at startup):

   ```sh
   docker compose -f docker-compose.yml -f apps/docker-compose.cmpose-app.yml pull cmpose-app
   docker compose -f docker-compose.yml -f apps/docker-compose.cmpose-app.yml up -d cmpose-app
   docker compose -f docker-compose.yml -f apps/docker-compose.cmpose-app.yml ps cmpose-app
   ```

5. Load the nginx part:

   ```sh
   docker compose exec nginx nginx -t
   docker compose exec nginx nginx -s reload
   ```

6. Check it from outside: `curl -i https://<domain>/cmpose-app/health` answers `200` with
   `{"status":"ok","version":"<APP_VERSION>"}` and an `X-App-Version` header; `/cmpose-app/ready` answers `200`.

## Updating

Steps 4 and 6. Pin a release with `IMAGE_TAG=<tag or short sha>` in the top-level `.env`; `latest` follows `develop`.

## Rollback

1. Set `IMAGE_TAG` to the previous good tag or short sha (`docker image ls ghcr.io/<owner>/cmpose-app` shows what the
   server has pulled).
2. `docker compose -f docker-compose.yml -f apps/docker-compose.cmpose-app.yml up -d cmpose-app`, then step 6.

Flyway migrations only move forward: an older image refuses to start on a database that a newer image has migrated
(`/ready` stays down). Before a release with a migration, take a database dump
(`docker compose exec -T postgres pg_dump -U postgres -Fc cmpose_app > cmpose_app.dump`); rolling back such a release
means restoring that dump.

## Firebase token verification checklist

- The only setting is `FIREBASE_PROJECT_ID`: Firebase console → Project settings → "Project ID" (for example
  `my-app-1a2b3`). It is not a secret, but it is server specific, so it lives in the `.env`. The app's clients must sign
  in with the same Firebase project.
- No service account key, Admin SDK credential or API key is needed: the server only checks signatures.
- What is checked: `alg` = `RS256` and a `kid` that matches one of Google's public keys; `aud` = `FIREBASE_PROJECT_ID`;
  `iss` = `https://securetoken.google.com/<FIREBASE_PROJECT_ID>`; `exp` in the future and `iat` / `auth_time` in the
  past, with `JWT_CLOCK_SKEW_SECONDS` (default 60) of tolerance; a non-empty `sub`.
- The keys come from
  `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com` and are cached. A
  `kid` never seen before is looked up there; after a lookup that found nothing, the next unknown `kid` waits a
  minute (it is a `401` until then), so made-up kids cannot use up the fetch budget that real keys need. Each such
  lookup reads Google's whole key set, so a newly published key is learned even during a flood of made-up kids.
- Network: the container needs outgoing HTTPS to `www.googleapis.com` (port 443). If it cannot reach it (or a fetch
  takes more than a few seconds) and holds no cached key, authenticated requests answer `503 AUTH_UNAVAILABLE` instead
  of `401`.
- The server's clock must be synchronised (NTP); a clock off by more than the tolerance rejects valid tokens.

## Privacy notes

- Account deletion (`DELETE /api/v1/me`) removes the user, their sign-in mappings, their records and their files, for
  good; nothing is kept for later.
<!-- cmpose:sharing -->
- Records and files a user added to someone else's space stay in that space after the user deletes their account:
  they belong to the space. Who added them is erased (the uploader becomes empty), so nothing left points at the
  deleted person. Say so in the app's privacy policy.
<!-- /cmpose:sharing -->
- Only an email the sign-in provider marks as verified is stored and shown; an unverified one is never kept.

## Quotas

- A write that would pass a quota is refused with `409 QUOTA_EXCEEDED`, whose body names the quota (`quota`); the
  limit itself is not sent. `0` turns a quota off. Lowering one deletes nothing: only new writes are refused.
- `MAX_STORAGE_BYTES_PER_USER` (default 104857600, 100 MB) caps the total size of a user's files (`quota`: `storage`).

<!-- cmpose:sharing -->
## Sharing

- `INVITE_TTL_DAYS` (default 7) is how long an invite token stays valid.
- Quotas: a file counts against the owner of its space, also when an editor uploaded it. `MAX_SPACES_PER_USER`
  (default 20) caps the spaces a user owns besides the personal one (`spaces`), `MAX_MEMBERS_PER_SPACE` (default 50)
  the members of a space, owner included, checked when an invite is accepted (`members`), and
  `MAX_PENDING_INVITES_PER_SPACE` (default 20) the valid invites of a space (`invites`).
<!-- /cmpose:sharing -->
