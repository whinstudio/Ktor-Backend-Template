#!/usr/bin/env bash
# Tests for tools/resolve-markers.sh: one fixture per marker form and file kind, both modes, the error cases, and the
# option name (each run sees only its own option's markers and paths file).
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

# run_resolver <script> <dir> <option> <mode> [expected exit]: runs the script; checks the exit code (default 0); keeps
# stderr in $err.
run_resolver() {
    local status=0
    err="$(bash "$1" "$2" "$3" "$4" 2>&1 >/dev/null)" || status=$?
    [ "$status" -eq "${5:-0}" ] || {
        echo "    resolver exited $status (expected ${5:-0}): $err"
        return 1
    }
}

# resolve <dir> <option> <mode> [expected exit]: runs tools/resolve-markers.sh.
resolve() {
    run_resolver "$resolver" "$@"
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
        if resolve "$dir" sharing "$mode"; then
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
            if resolve "$dir" sharing "$mode"; then
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
        if resolve "$dir" sharing "$mode" 2 && [[ "$err" == *nested* ]]; then
            pass "$file: nested block $mode -> exit 2"
        else
            fail "$file: nested block $mode -> exit 2"
        fi

        dir="$(fixture "unclosed-$mode")"
        printf '%s\n' "a" "$open" "x" >"$dir/$file"
        if resolve "$dir" sharing "$mode" 2 && [[ "$err" == *unclosed* ]]; then
            pass "$file: unclosed block $mode -> exit 2"
        else
            fail "$file: unclosed block $mode -> exit 2"
        fi
    done
done

# A close without an open, a swap on the last line and a malformed marker are errors too.
dir="$(fixture stray)"
printf '%s\n' "a" "// /cmpose:sharing" >"$dir/A.kt"
if resolve "$dir" sharing off 2 && [[ "$err" == *without* ]]; then pass "stray close -> exit 2"; else fail "stray close -> exit 2"; fi

dir="$(fixture swap-last)"
printf '%s\n' "a" "// cmpose:sharing-off: b()" >"$dir/A.kt"
if resolve "$dir" sharing off 2 && [[ "$err" == *"last line"* ]]; then pass "swap on the last line -> exit 2"; else fail "swap on the last line -> exit 2"; fi

dir="$(fixture malformed)"
printf '%s\n' "a" "//cmpose:sharing" "b" "// /cmpose:sharing" >"$dir/A.kt"
if resolve "$dir" sharing on 2 && [[ "$err" == *malformed* ]]; then pass "malformed marker -> exit 2"; else fail "malformed marker -> exit 2"; fi

# An error leaves every file untouched, also the ones processed before it, and deletes no path.
dir="$(fixture atomic)"
mkdir -p "$dir/a" "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing"
printf '%s\n' "// cmpose:sharing" "x" "// /cmpose:sharing" >"$dir/a/A.kt"
printf '%s\n' "// cmpose:sharing" "x" >"$dir/a/B.kt"
touch "$dir/src/main/kotlin/io/cmpose/backendtemplate/sharing/Space.kt"
if resolve "$dir" sharing off 2 && grep -q "cmpose:sharing" "$dir/a/A.kt" \
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
if resolve "$dir" sharing off; then
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
if resolve "$dir" sharing on; then
    expect "imports on" "$dir/A.kt" "$(printf '%s\n' "$kotlin" | grep -v 'cmpose:sharing')"
else
    fail "imports on"
fi

# Dropping a block between blank lines leaves one blank line.
dir="$(fixture blanks)"
printf '%s\n' "a" "" "// cmpose:sharing" "x" "// /cmpose:sharing" "" "b" >"$dir/A.kt"
if resolve "$dir" sharing off; then
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
    if resolve "$dir" sharing "$mode"; then
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

# test_option_name_selects_markers: a run sees only its own option's markers; another option's are plain text.
sample_marked="$(printf '%s\n' "first" "// cmpose:sample" "sample()" "// /cmpose:sample" "// cmpose:sample-off: b()" "a()" "last")"
dir="$(fixture option-other)"
printf '%s\n' "$sample_marked" >"$dir/A.kt"
if resolve "$dir" sharing off; then
    expect "option name: sharing off keeps sample markers" "$dir/A.kt" "$sample_marked"
else
    fail "option name: sharing off keeps sample markers"
fi
dir="$(fixture option-own)"
printf '%s\n' "$sample_marked" >"$dir/A.kt"
if resolve "$dir" sample off; then
    expect "option name: sample off drops the sample block" "$dir/A.kt" "$(printf '%s\n' "first" "b()" "last")"
else
    fail "option name: sample off drops the sample block"
fi

# test_cross_option_nesting: a sharing swap inside a sample block; sharing runs first, then sample.
nested="$(printf '%s\n' "first" "// cmpose:sample" "// cmpose:sharing-off: plain()" "shared()" "// /cmpose:sample" "last")"
dir="$(fixture cross-on-off)"
printf '%s\n' "$nested" >"$dir/A.kt"
if resolve "$dir" sharing on && resolve "$dir" sample off; then
    expect "cross-option: sharing on, sample off" "$dir/A.kt" "$(printf '%s\n' "first" "last")"
else
    fail "cross-option: sharing on, sample off"
fi
dir="$(fixture cross-off-on)"
printf '%s\n' "$nested" >"$dir/A.kt"
if resolve "$dir" sharing off && resolve "$dir" sample on; then
    expect "cross-option: sharing off, sample on" "$dir/A.kt" "$(printf '%s\n' "first" "plain()" "last")"
else
    fail "cross-option: sharing off, sample on"
fi
# A swap may not stand before a marker line of any option, in any comment style: the swap would replace it unseen.
# check_swap_before <name> <file> <option> <mode> <line…>: the resolver exits 2 and leaves the file as it was.
check_swap_before() {
    local name="$1" file="$2" option="$3" mode="$4" before
    shift 4
    dir="$(fixture swap-before)"
    printf '%s\n' "$@" >"$dir/$file"
    before="$(cat "$dir/$file")"
    if resolve "$dir" "$option" "$mode" 2 && [[ "$err" == *"before a marker"* ]] \
        && [ "$(cat "$dir/$file")" == "$before" ]; then
        pass "$name -> exit 2"
    else
        fail "$name -> exit 2"
    fi
}
for mode in on off; do
    check_swap_before "sharing swap before a sample swap $mode" A.kt sharing "$mode" \
        "a" "// cmpose:sharing-off: plain()" "// cmpose:sample-off: b()" "c()" "d"
    check_swap_before "sharing swap before a sample block $mode" A.kt sharing "$mode" \
        "a" "// cmpose:sharing-off: plain()" "// cmpose:sample" "c" "// /cmpose:sample"
    check_swap_before "sharing swap before a sample close $mode" V9__x.sql sharing "$mode" \
        "-- cmpose:sample" "-- cmpose:sharing-off: plain" "-- /cmpose:sample" "d"
    check_swap_before "sharing swap before a marker in another comment style $mode" A.kt sharing "$mode" \
        "a" "// cmpose:sharing-off: plain()" "# cmpose:sample" "c"
    check_swap_before "sample swap before a sharing block $mode" A.kt sample "$mode" \
        "a" "// cmpose:sample-off: b()" "// cmpose:sharing" "c" "// /cmpose:sharing"
    check_swap_before "own swap before own marker $mode" A.kt sample "$mode" \
        "a" "// cmpose:sample-off: b()" "// cmpose:sample" "c" "// /cmpose:sample"
done

# test_paths_file_per_option: sample off deletes the paths of tools/sample-paths.txt and leaves sharing-paths.txt's.
copy_tools="$work/copy/tools"
mkdir -p "$copy_tools"
cp "$resolver" "$copy_tools/resolve-markers.sh"
printf '%s\n' "# Paths deleted when the sample feature is off" "src/sample" "docs/SAMPLE.md" >"$copy_tools/sample-paths.txt"
printf '%s\n' "src/sharing" >"$copy_tools/sharing-paths.txt"
for mode in on off; do
    dir="$(fixture "option-paths-$mode")"
    mkdir -p "$dir/src/sample" "$dir/src/sharing" "$dir/docs"
    touch "$dir/src/sample/Sample.kt" "$dir/src/sharing/Space.kt" "$dir/docs/SAMPLE.md" "$dir/docs/OTHER.md"
    if run_resolver "$copy_tools/resolve-markers.sh" "$dir" sample "$mode"; then
        present=0
        [ -e "$dir/src/sample" ] && present=$((present + 1))
        [ -e "$dir/docs/SAMPLE.md" ] && present=$((present + 1))
        want=2
        [ "$mode" == "off" ] && want=0
        if [ "$present" -eq "$want" ] && [ -f "$dir/src/sharing/Space.kt" ] && [ -f "$dir/docs/OTHER.md" ]; then
            pass "sample paths $mode"
        else
            fail "sample paths $mode"
        fi
    else
        fail "sample paths $mode"
    fi
done

# A file is picked by its own option's markers only: option "sam" leaves a file with only cmpose:sample markers (and
# its unused import) alone.
printf '%s\n' "# Paths deleted when the sam feature is off" >"$copy_tools/sam-paths.txt"
prefix_only="$(printf '%s\n' "import a.b.Unused" "" "// cmpose:sample" "x()" "// /cmpose:sample")"
dir="$(fixture option-prefix)"
printf '%s\n' "$prefix_only" >"$dir/A.kt"
if run_resolver "$copy_tools/resolve-markers.sh" "$dir" sam off; then
    expect "option name is not a prefix match" "$dir/A.kt" "$prefix_only"
else
    fail "option name is not a prefix match"
fi

# test_unknown_option_paths_file_is_usage_error: no tools/<option>-paths.txt is bad usage, and nothing changes.
for mode in on off; do
    dir="$(fixture "unknown-option-$mode")"
    printf '%s\n' "// cmpose:foo" "x" "// /cmpose:foo" >"$dir/A.kt"
    if resolve "$dir" foo "$mode" 1 && [[ "$err" == *foo-paths.txt* ]] && grep -q "cmpose:foo" "$dir/A.kt"; then
        pass "unknown option $mode -> exit 1"
    else
        fail "unknown option $mode -> exit 1"
    fi
done

# Bad usage.
if resolve "$work" sharing sideways 1; then pass "unknown mode -> exit 1"; else fail "unknown mode -> exit 1"; fi
if resolve "$work/missing" sharing on 1; then pass "missing directory -> exit 1"; else fail "missing directory -> exit 1"; fi
for option in Sharing sha-ring "" "../sharing"; do
    if resolve "$work" "$option" on 1; then
        pass "option name '$option' -> exit 1"
    else
        fail "option name '$option' -> exit 1"
    fi
done
status=0
bash "$resolver" "$work" on >/dev/null 2>&1 || status=$?
if [ "$status" -eq 1 ]; then pass "missing option -> exit 1"; else fail "missing option -> exit 1"; fi

echo "== $passed passed, $failed failed"
[ "$failed" -eq 0 ]
