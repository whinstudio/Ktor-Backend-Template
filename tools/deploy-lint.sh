#!/usr/bin/env bash
# Static checks for the deploy parts (deploy/) and the repository's public hygiene.
# The app name is read from deploy/docker-compose.<app>.yml, so the checks survive the generator's renaming.
# Usage: bash tools/deploy-lint.sh   (prints "deploy-lint OK" or every failed check, exit 1)
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

failures=0
fail() {
    echo "FAIL: $*"
    failures=$((failures + 1))
}
# has <file> <fixed string> <what>
has() { grep -qF -- "$2" "$1" || fail "$1: $3"; }
# has_re <file> <extended regex> <what>
has_re() { grep -qE -- "$2" "$1" || fail "$1: $3"; }
# lacks_re <file> <extended regex> <what>
lacks_re() { ! grep -qE -- "$2" "$1" || fail "$1: $3"; }

shopt -s nullglob
composes=(deploy/docker-compose.*.yml)
shopt -u nullglob
if [ "${#composes[@]}" -ne 1 ]; then
    echo "FAIL: expected exactly one deploy/docker-compose.<app>.yml, found ${#composes[@]}"
    exit 1
fi
compose="${composes[0]}"
app="${compose#deploy/docker-compose.}"
app="${app%.yml}"
db="${app//-/_}"
prefix="$(echo "$db" | tr '[:lower:]' '[:upper:]')"

dockerfile=deploy/Dockerfile
nginx="deploy/nginx/$app.conf"
sql="deploy/sql/create-$app.sql"
env_example=deploy/.env.example
for file in "$dockerfile" "$nginx" "$sql" "$env_example" deploy/README.md .github/workflows/ci.yml; do
    [ -f "$file" ] || fail "$file is missing"
done

if [ -f "$dockerfile" ]; then
    has_re "$dockerfile" '^USER 10001$' "needs a 'USER 10001' line"
    lacks_re "$dockerfile" '^[[:space:]]*HEALTHCHECK' "must not have a HEALTHCHECK (compose owns it)"
    # Base images pinned by digest; the tag stays for readers and for Dependabot, which moves both.
    unpinned="$(grep -iE '^[[:space:]]*FROM[[:space:]]' "$dockerfile" \
        | grep -viE '^FROM [^[:space:]@:]+:[^[:space:]@]+@sha256:[0-9a-f]{64}( AS [[:alnum:]_.-]+)?$' || true)"
    [ -z "$unpinned" ] || fail "$dockerfile: every FROM pins its image by digest (<image>:<tag>@sha256:…)"
fi

has "$compose" "mem_limit: \${${prefix}_MEM_LIMIT:-512m}" "needs mem_limit: \${${prefix}_MEM_LIMIT:-512m}"
has_re "$compose" 'read_only: true' "needs read_only: true"
has "$compose" "no-new-privileges" "needs no-new-privileges"
has_re "$compose" 'pids_limit: [0-9]+' "needs a pids_limit"
has "$compose" "cap_drop" "needs cap_drop"
has_re "$compose" 'healthcheck:' "needs a healthcheck"
has_re "$compose" '127\.0\.0\.1:8080/ready' "the healthcheck must probe /ready"
lacks_re "$compose" '^[[:space:]]*ports:' "must not publish ports"
has "$compose" "env_file: ./apps/$app.env" "needs env_file: ./apps/$app.env"
has "$compose" "image: ghcr.io/\${IMAGE_OWNER}/$app:\${IMAGE_TAG:-latest}" "needs the ghcr image reference"
for network in proxy db; do
    # A top-level network <name> declared external: true.
    awk -v net="$network" '
        /^networks:/ { top = 1; next }
        /^[^[:space:]#]/ { top = 0 }
        top && $0 ~ "^  " net ":" { seen = 1; next }
        top && seen && /^  [^[:space:]]/ { seen = 0 }
        top && seen && /external: true/ { found = 1 }
        END { exit found ? 0 : 1 }
    ' "$compose" || fail "$compose: needs the external network '$network'"
done

if [ -f "$nginx" ]; then
    has "$nginx" "location /$app/" "needs location /$app/"
    has_re "$nginx" 'client_max_body_size' "needs client_max_body_size"
    lacks_re "$nginx" 'server[[:space:]]*\{' "must not contain a server block"
fi

if [ -f "$sql" ]; then
    has "$sql" "REVOKE ALL ON DATABASE $db FROM PUBLIC" "needs REVOKE ALL ON DATABASE $db FROM PUBLIC"
    has "$sql" ":'password'" "the password must come from the psql variable :'password'"
    has "$sql" "\\getenv password ${prefix}_DB_PASSWORD" "must read the password with \\getenv from ${prefix}_DB_PASSWORD"
    lacks_re "$sql" "PASSWORD[[:space:]]+'" "must not contain a literal password"
fi
# The password reaches psql through the environment only: a -v password=… argument would show in `ps`.
for file in "$sql" deploy/README.md; do
    if [ -f "$file" ]; then
        lacks_re "$file" "-v[[:space:]]*password=" "must not pass the password with -v password="
    fi
done
if [ -f deploy/README.md ]; then
    has deploy/README.md "-e ${prefix}_DB_PASSWORD" "needs the docker exec -e ${prefix}_DB_PASSWORD step"
fi

if [ -f "$env_example" ]; then
    # Every variable AppConfig reads (so the sharing-off variant drops INVITE_TTL_DAYS with the code), plus JAVA_OPTS.
    config_file="$(find src/main/kotlin -name AppConfig.kt | head -n 1)"
    vars="$(grep -oE '(required|optional|int|long|list)\("[A-Z][A-Z0-9_]*"' "$config_file" | sed -E 's/.*\("([A-Z0-9_]+)"/\1/' | sort -u)"
    [ -n "$vars" ] || fail "$config_file: no environment variables found"
    for var in $vars JAVA_OPTS; do
        grep -qE "^(# )?$var=" "$env_example" || fail "$env_example: $var is not listed"
    done
    while IFS= read -r line; do
        name="${line%%=*}"
        echo "$vars JAVA_OPTS" | tr ' ' '\n' | grep -qx "$name" || fail "$env_example: $name is not read by the app"
    done < <(grep -E '^[A-Z][A-Z0-9_]*=' "$env_example")
    has "$env_example" "${prefix}_MEM_LIMIT" "needs the ${prefix}_MEM_LIMIT note"
    # Secrets hold placeholders only.
    has_re "$env_example" '^DATABASE_PASSWORD=(change-me)?$' "DATABASE_PASSWORD must be empty or change-me"
    has_re "$env_example" '^FIREBASE_PROJECT_ID=your-firebase-project-id$' "FIREBASE_PROJECT_ID must be the placeholder"
fi

# No private paths, names or domains anywhere in the tracked (or about to be tracked) files.
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    files="$(git ls-files -co --exclude-standard | grep -v '^tools/deploy-lint\.sh$' || true)"
else
    files="$(find . -type f -not -path './.git/*' -not -path './build/*' -not -path './.gradle/*' \
        -not -path './.kotlin/*' -not -path './.local-server/*' -not -path './tools/deploy-lint.sh' | sed 's|^\./||')"
fi
# Home-directory paths always; DEPLOY_LINT_FORBIDDEN (an extended regex, e.g. your domain|your name) adds your own
# private words without writing them into the repository.
forbidden='/Use[r]s/[^/[:space:]]+/|/hom[e]/[^/[:space:]]+/'  # bracketed so this line does not match itself
[ -z "${DEPLOY_LINT_FORBIDDEN:-}" ] || forbidden="$forbidden|$DEPLOY_LINT_FORBIDDEN"
if [ -n "$files" ]; then
    leaks="$(echo "$files" | while IFS= read -r file; do [ -f "$file" ] && echo "$file"; done \
        | tr '\n' '\0' | xargs -0 grep -lE "$forbidden" 2>/dev/null || true)"
    [ -z "$leaks" ] || fail "private path, name or domain in: $(echo "$leaks" | tr '\n' ' ')"
fi

if [ "$failures" -ne 0 ]; then
    echo "deploy-lint: $failures check(s) failed"
    exit 1
fi
echo "deploy-lint OK"
