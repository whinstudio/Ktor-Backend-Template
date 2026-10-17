#!/usr/bin/env bash
# The template gate. For each sharing variant (on, off) the repository is copied to a temporary directory without its
# git data and build output, the markers are resolved (tools/resolve-markers.sh) and the copy is checked: no cmpose:
# marker left, in off no sharing word (space, invite, member) in the code, docs and deploy parts, ./gradlew build
# (compile, detekt, tests), tools/deploy-lint.sh, and the Docker image (non-root user) unless --no-docker.
# Usage: bash tools/gate.sh [--no-docker] [--offline]   (prints "== N passed, M failed", exit 1 on any failure)
# The temporary directory is removed when every check passes; on a failure it is kept for the logs.
set -uo pipefail

docker=1
gradle_args=(build --console=plain)
for arg in "$@"; do
    case "$arg" in
        --no-docker) docker=0 ;;
        --offline) gradle_args+=(--offline) ;;
        *)
            echo "usage: $0 [--no-docker] [--offline]" >&2
            exit 1
            ;;
    esac
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/template-gate.XXXXXX")"
passed=0
failed=0

# check <name> <command…>: runs the command with its output in a log; on a failure prints the log's tail.
check() {
    local name="$1" log
    shift
    log="$work/$(echo "$name" | tr -c 'A-Za-z0-9\n' '-').log"
    if "$@" >"$log" 2>&1; then
        echo "PASS $name"
        passed=$((passed + 1))
    else
        echo "FAIL $name (log: $log)"
        tail -n 40 "$log" | sed 's/^/    /'
        failed=$((failed + 1))
    fi
}

copy() {
    rsync -a --exclude /.git --exclude /build --exclude /.gradle --exclude /.kotlin --exclude /.local-server \
        --exclude /.idea --exclude .claude --exclude .DS_Store "$root/" "$1/"
}

# Every path of tools/sharing-paths.txt exists, so the list is not stale.
sharing_paths_exist() {
    local path missing=0
    while IFS= read -r path || [ -n "$path" ]; do
        case "$path" in "" | "#"*) continue ;; esac
        [ -e "$root/$path" ] || {
            echo "missing: $path"
            missing=1
        }
    done <"$root/tools/sharing-paths.txt"
    [ "$missing" -eq 0 ]
}

# Tools carry the marker forms in their own text, so they are left out.
no_markers_left() {
    ! grep -rn "cmpose:" "$1" --exclude-dir=tools --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.kotlin
}

no_sharing_words() {
    local targets=()
    for target in src SECURITY-CHECKLIST.md deploy README.md; do
        [ -e "$1/$target" ] && targets+=("$1/$target")
    done
    ! grep -rniE "space|invite|member" "${targets[@]}"
}

gradle_build() {
    (cd "$1" && ./gradlew "${gradle_args[@]}")
}

# Prints "<tests> tests, <failures> failures, <skipped> skipped" from the JUnit reports.
test_summary() {
    awk '/<testsuite / {
            for (i = 1; i <= NF; i++) if ($i ~ /^(tests|failures|errors|skipped)="/) {
                split($i, kv, "\""); sum[substr($i, 1, index($i, "=") - 1)] += kv[2]
            }
        }
        END { printf "%d tests, %d failures, %d skipped\n", sum["tests"], sum["failures"] + sum["errors"], sum["skipped"] }' \
        "$1"/build/test-results/test/TEST-*.xml
}

deploy_lint() {
    (cd "$1" && GIT_CEILING_DIRECTORIES="$work" bash tools/deploy-lint.sh)
}

docker_image() {
    local image="template-gate-$2:local" user status=0
    docker build -f "$1/deploy/Dockerfile" -t "$image" "$1" || return 1
    user="$(docker inspect --format '{{.Config.User}}' "$image")" || status=1
    docker image rm "$image" >/dev/null || true
    [ "$status" -eq 0 ] && [ "$user" == "10001" ] || {
        echo "image user is '$user', expected 10001"
        return 1
    }
}

check "resolve-markers tests" bash "$root/tools/resolve-markers.test.sh"
check "sharing paths exist" sharing_paths_exist
for variant in on off; do
    dir="$work/$variant"
    check "$variant: copy" copy "$dir"
    check "$variant: resolve markers" bash "$dir/tools/resolve-markers.sh" "$dir" "$variant"
    check "$variant: no marker left" no_markers_left "$dir"
    [ "$variant" == "off" ] && check "$variant: no sharing words" no_sharing_words "$dir"
    check "$variant: gradle build" gradle_build "$dir"
    if compgen -G "$dir/build/test-results/test/TEST-*.xml" >/dev/null; then
        echo "     $variant: $(test_summary "$dir")"
    fi
    check "$variant: deploy-lint" deploy_lint "$dir"
    [ "$docker" -eq 1 ] && check "$variant: docker image" docker_image "$dir" "$variant"
done

echo "== $passed passed, $failed failed"
if [ "$failed" -eq 0 ]; then
    rm -rf "$work"
else
    echo "logs: $work"
    exit 1
fi
