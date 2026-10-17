#!/usr/bin/env bash
# Tests for tools/resolve-markers.sh: one fixture per marker form and file kind, both modes, and the error cases.
# Usage: bash tools/resolve-markers.test.sh   (prints "== N passed, M failed", exit 1 on any failure)
set -uo pipefail

tools="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
resolver="$tools/resolve-markers.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/resolve-markers-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

passed=0
failed=0
pass() {
    echo "PASS $1"
    passed=$((passed + 1))
}
fail() {
    echo "FAIL $1"
    failed=$((failed + 1))
}

# fixture <name>: a fresh, empty project directory; prints its path.
fixture() {
    rm -rf "${work:?}/$1"
    mkdir -p "$work/$1"
    echo "$work/$1"
}

# expect <name> <file> <expected content>: the resolved file equals the expected content.
expect() {
    if [ "$(cat "$2")" == "$3" ]; then
        pass "$1"
    else
        fail "$1"
        diff <(printf '%s\n' "$3") "$2" | sed 's/^/    /'
    fi
}

# resolve <dir> <mode> [expected exit]: runs the resolver; checks the exit code (default 0); keeps stderr in $err.
resolve() {
    local status=0
    err="$(bash "$resolver" "$1" "$2" 2>&1 >/dev/null)" || status=$?
    [ "$status" -eq "${3:-0}" ] || {
        echo "    resolver exited $status (expected ${3:-0}): $err"
        return 1
    }
}

# Comment prefix of each file kind: <file name> <line comment or "html">.
kinds=(
    "Main.kt //" "build.gradle.kts //" "V9__x.sql --"
    "app.yml #" "app.yaml #" "libs.versions.toml #" "gradle.properties #" ".gitignore #" ".env.example #" ".dockerignore #"
    "NOTES.md html"
)

for entry in "${kinds[@]}"; do
    file="${entry% *}"
    prefix="${entry##* }"
    if [ "$prefix" == "html" ]; then
        open="<!-- cmpose:sharing -->"
        close="<!-- /cmpose:sharing -->"
    else
        open="$prefix cmpose:sharing"
        close="$prefix /cmpose:sharing"
    fi
    marked="$(printf '%s\n' "first" "    $open" "    shared" "    $close" "last")"

    for mode in on off; do
        dir="$(fixture "block-$mode")"
        printf '%s\n' "$marked" >"$dir/$file"
        if resolve "$dir" "$mode"; then
            if [ "$mode" == "on" ]; then
                expect "$file: block $mode" "$dir/$file" "$(printf '%s\n' "first" "    shared" "last")"
            else
                expect "$file: block $mode" "$dir/$file" "$(printf '%s\n' "first" "last")"
            fi
        else
            fail "$file: block $mode"
        fi
    done

    if [ "$prefix" != "html" ]; then
        swapped="$(printf '%s\n' "first" "  $prefix cmpose:sharing-off: plain()" "        shared()" "last")"
        for mode in on off; do
            dir="$(fixture "swap-$mode")"
            printf '%s\n' "$swapped" >"$dir/$file"
            if resolve "$dir" "$mode"; then
                if [ "$mode" == "on" ]; then
                    expect "$file: swap $mode" "$dir/$file" "$(printf '%s\n' "first" "        shared()" "last")"
                else
                    # The replacement takes the indentation of the line it replaces.
                    expect "$file: swap $mode" "$dir/$file" "$(printf '%s\n' "first" "        plain()" "last")"
                fi
            else
                fail "$file: swap $mode"
            fi
        done
    fi

    for mode in on off; do
        dir="$(fixture "nested-$mode")"
        printf '%s\n' "$open" "$open" "x" "$close" "$close" >"$dir/$file"
        if resolve "$dir" "$mode" 2 && [[ "$err" == *nested* ]]; then
            pass "$file: nested block $mode -> exit 2"
        else
            fail "$file: nested block $mode -> exit 2"
        fi

        dir="$(fixture "unclosed-$mode")"
        printf '%s\n' "a" "$open" "x" >"$dir/$file"
        if resolve "$dir" "$mode" 2 && [[ "$err" == *unclosed* ]]; then
            pass "$file: unclosed block $mode -> exit 2"
        else
            fail "$file: unclosed block $mode -> exit 2"
        fi
    done
done

# A close without an open, a swap on the last line and a malformed marker are errors too.
dir="$(fixture stray)"
printf '%s\n' "a" "// /cmpose:sharing" >"$dir/A.kt"
if resolve "$dir" off 2 && [[ "$err" == *without* ]]; then pass "stray close -> exit 2"; else fail "stray close -> exit 2"; fi

dir="$(fixture swap-last)"
printf '%s\n' "a" "// cmpose:sharing-off: b()" >"$dir/A.kt"
if resolve "$dir" off 2 && [[ "$err" == *"last line"* ]]; then pass "swap on the last line -> exit 2"; else fail "swap on the last line -> exit 2"; fi

dir="$(fixture malformed)"
printf '%s\n' "a" "//cmpose:sharing" "b" "// /cmpose:sharing" >"$dir/A.kt"
if resolve "$dir" on 2 && [[ "$err" == *malformed* ]]; then pass "malformed marker -> exit 2"; else fail "malformed marker -> exit 2"; fi

# An error leaves every file untouched, also the ones processed before it, and deletes no path.
dir="$(fixture atomic)"
mkdir -p "$dir/a" "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing"
printf '%s\n' "// cmpose:sharing" "x" "// /cmpose:sharing" >"$dir/a/A.kt"
printf '%s\n' "// cmpose:sharing" "x" >"$dir/a/B.kt"
touch "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing/Space.kt"
if resolve "$dir" off 2 && grep -q "cmpose:sharing" "$dir/a/A.kt" \
    && [ -f "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing/Space.kt" ]; then
    pass "an error changes nothing"
else
    fail "an error changes nothing"
fi

# Imports: off drops the ones the remaining code no longer names; on keeps them all; star imports stay.
kotlin="$(cat <<'EOF'
package demo

import a.b.Used
import a.b.Gone
import a.b.Thing as Alias
import a.b.Other as Dropped
import a.b.Prefix
import c.d.*

// cmpose:sharing
fun shared(): Gone = Gone(Dropped)
// /cmpose:sharing

fun plain(): Used = Used(Alias, PrefixLonger)
EOF
)"
dir="$(fixture imports-off)"
printf '%s\n' "$kotlin" >"$dir/A.kt"
if resolve "$dir" off; then
    expect "imports off" "$dir/A.kt" "$(cat <<'EOF'
package demo

import a.b.Used
import a.b.Thing as Alias
import c.d.*

fun plain(): Used = Used(Alias, PrefixLonger)
EOF
)"
else
    fail "imports off"
fi
dir="$(fixture imports-on)"
printf '%s\n' "$kotlin" >"$dir/A.kt"
if resolve "$dir" on; then
    expect "imports on" "$dir/A.kt" "$(printf '%s\n' "$kotlin" | grep -v 'cmpose:sharing')"
else
    fail "imports on"
fi

# Dropping a block between blank lines leaves one blank line.
dir="$(fixture blanks)"
printf '%s\n' "a" "" "// cmpose:sharing" "x" "// /cmpose:sharing" "" "b" >"$dir/A.kt"
if resolve "$dir" off; then
    expect "blank lines around a dropped block" "$dir/A.kt" "$(printf '%s\n' "a" "" "b")"
else
    fail "blank lines around a dropped block"
fi

# Off deletes the paths of tools/sharing-paths.txt; on keeps them. Build output is never touched.
for mode in on off; do
    dir="$(fixture "paths-$mode")"
    mkdir -p "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing" "$dir/src/main/resources/db/migration" "$dir/build"
    touch "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing/Space.kt"
    touch "$dir/src/main/resources/db/migration/V2__sharing.sql" "$dir/src/main/resources/db/migration/V1__core.sql"
    printf '%s\n' "// cmpose:sharing" "x" "// /cmpose:sharing" >"$dir/build/Generated.kt"
    if resolve "$dir" "$mode"; then
        present=0
        [ -e "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing" ] && present=$((present + 1))
        [ -e "$dir/src/main/resources/db/migration/V2__sharing.sql" ] && present=$((present + 1))
        want=2
        [ "$mode" == "off" ] && want=0
        if [ "$present" -eq "$want" ] && [ -f "$dir/src/main/resources/db/migration/V1__core.sql" ] \
            && grep -q "cmpose:sharing" "$dir/build/Generated.kt"; then
            pass "sharing paths $mode"
        else
            fail "sharing paths $mode"
        fi
    else
        fail "sharing paths $mode"
    fi
done

# Bad usage.
if resolve "$work" sideways 1; then pass "unknown mode -> exit 1"; else fail "unknown mode -> exit 1"; fi
if resolve "$work/missing" on 1; then pass "missing directory -> exit 1"; else fail "missing directory -> exit 1"; fi

echo "== $passed passed, $failed failed"
[ "$failed" -eq 0 ]
