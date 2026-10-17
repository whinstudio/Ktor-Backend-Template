#!/usr/bin/env bash
# Resolves the cmpose:sharing markers of a project directory in place, the way the generator does.
#   on:  drops only the marker lines; a swap marker goes and the line after it stays.
#   off: drops every marked block with its markers, puts each swap marker's text in place of the line after it (at
#        that line's indentation), deletes the paths of tools/sharing-paths.txt and the Kotlin imports the remaining
#        code no longer names (star imports stay).
# Either way a blank line on both sides of a dropped run becomes one. Marker forms by file name:
#   .kt .kts                                                          // cmpose:sharing … // /cmpose:sharing
#   .sql                                                              -- cmpose:sharing … -- /cmpose:sharing
#   .yml .yaml .toml .properties .gitignore .env.example .dockerignore  # cmpose:sharing … # /cmpose:sharing
#   .md                                                               <!-- cmpose:sharing --> … <!-- /cmpose:sharing -->
# and the swap line "<comment> cmpose:sharing-off: <replacement>" (not in .md). Blocks never nest.
# Usage: bash tools/resolve-markers.sh <dir> on|off
# Exit 1 on bad usage, 2 on a marker error (nested, unclosed or stray block, swap on the last line, malformed marker);
# on an error nothing is changed.
set -euo pipefail

usage() {
    echo "usage: $0 <dir> on|off" >&2
    exit 1
}
[ "$#" -eq 2 ] || usage
dir="$1"
mode="$2"
[ "$mode" == "on" ] || [ "$mode" == "off" ] || usage
[ -d "$dir" ] || {
    echo "error: $dir is not a directory" >&2
    exit 1
}
paths_file="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sharing-paths.txt"

# prefix_of <file name>: the line comment of the file's marker form (html for .md), empty when it has none.
prefix_of() {
    case "$1" in
        *.kt | *.kts) echo "//" ;;
        *.sql) echo "--" ;;
        *.yml | *.yaml | *.toml | *.properties | .gitignore | .env.example | .dockerignore) echo "#" ;;
        *.md) echo "html" ;;
        *) echo "" ;;
    esac
}

# Resolves one file to stdout; awk reads it whole, as the import pass needs the remaining code.
resolve_file() {
    local prefix="$1" file="$2" open close swap kotlin=0
    if [ "$prefix" == "html" ]; then
        open="<!-- cmpose:sharing -->"
        close="<!-- /cmpose:sharing -->"
        swap=""
        prefix="<!--"
    else
        open="$prefix cmpose:sharing"
        close="$prefix /cmpose:sharing"
        swap="$prefix cmpose:sharing-off: "
    fi
    case "$file" in *.kt | *.kts) kotlin=1 ;; esac
    awk -v opener="$open" -v closer="$close" -v swap="$swap" -v prefix="$prefix" -v mode="$mode" \
        -v kotlin="$kotlin" -v file="$file" '
        function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
        function die(message, at) { printf "error: %s:%d: %s\n", file, at, message > "/dev/stderr"; failed = 1; exit 2 }
        # Out of a dropped run, blank lines that follow a blank line go.
        function emit(s) {
            if (dropped) { joining = 1; dropped = 0 }
            if (joining) {
                if (s == "" && n > 0 && out[n] == "") return
                joining = 0
            }
            out[++n] = s
        }
        # A line that looks like a marker but is none of the known forms.
        function malformed(t,    rest) {
            if (index(t, prefix) != 1) return 0
            rest = substr(t, length(prefix) + 1)
            sub(/^[ \t]*\/?/, "", rest)
            return index(rest, "cmpose:") == 1
        }
        # Whether the code names <name> as a whole word.
        function names(code, name,    at, start, before, after) {
            start = 1
            while ((at = index(substr(code, start), name)) > 0) {
                at += start - 1
                before = at > 1 ? substr(code, at - 1, 1) : ""
                after = substr(code, at + length(name), 1)
                if (before !~ /[A-Za-z0-9_]/ && after !~ /[A-Za-z0-9_]/) return 1
                start = at + 1
            }
            return 0
        }
        { lines[NR] = $0 }
        END {
            if (failed) exit 2
            for (i = 1; i <= NR; i++) {
                line = lines[i]
                t = trim(line)
                if (t == opener) {
                    if (inside) die("nested cmpose:sharing block (open since line " opened ")", i)
                    inside = 1; opened = i; dropped = 1; continue
                }
                if (t == closer) {
                    if (!inside) die("block close without an open", i)
                    inside = 0; dropped = 1; continue
                }
                if (inside && mode == "off") continue
                if (swap != "" && index(t, swap) == 1) {
                    if (i == NR) die("cmpose:sharing-off marker on the last line", i)
                    next_line = lines[i + 1]
                    nt = trim(next_line)
                    if (nt == opener || nt == closer || malformed(nt)) die("cmpose:sharing-off marker before a marker", i)
                    if (mode == "off") {
                        match(next_line, /^[ \t]*/)
                        next_line = substr(next_line, 1, RLENGTH) substr(t, length(swap) + 1)
                    }
                    i++
                    emit(next_line)
                    continue
                }
                if (malformed(t)) die("malformed cmpose marker: " t, i)
                emit(line)
            }
            if (inside) die("unclosed cmpose:sharing block", opened)

            if (kotlin && mode == "off") {
                import_re = "^import[ \t]+[A-Za-z0-9_.`]+([ \t]+as[ \t]+[A-Za-z0-9_`]+)?[ \t]*$"
                code = ""
                for (i = 1; i <= n; i++) if (out[i] !~ import_re) code = code out[i] "\n"
                for (i = 1; i <= n; i++) {
                    if (out[i] !~ import_re || out[i] ~ /\.\*[ \t]*$/) continue
                    name = trim(out[i])
                    if (name ~ /[ \t]as[ \t]/) sub(/.*[ \t]as[ \t]+/, "", name)
                    else sub(/.*\./, "", name)
                    gsub(/`/, "", name)
                    if (!names(code, name)) drop[i] = 1
                }
            }
            for (i = 1; i <= n; i++) if (!(i in drop)) print out[i]
        }
    ' "$file"
}

# Every file with a marker, outside the build and tool output; each is resolved to a temporary file first, so that a
# marker error anywhere leaves the directory as it was.
targets=()
results=()
cleanup() { [ "${#results[@]}" -eq 0 ] || rm -f "${results[@]}"; }
trap cleanup EXIT
while IFS= read -r -d '' file; do
    prefix="$(prefix_of "$(basename "$file")")"
    [ -n "$prefix" ] || continue
    grep -q "cmpose:" "$file" || continue
    result="$(mktemp "${TMPDIR:-/tmp}/resolve-markers.XXXXXX")"
    results+=("$result")
    resolve_file "$prefix" "$file" >"$result" || exit 2
    targets+=("$file")
done < <(find "$dir" \( -name .git -o -name build -o -name .gradle -o -name .kotlin -o -name .local-server \
    -o -name node_modules \) -prune -o -type f -print0)

# The sharing-only paths: relative, inside the directory.
sharing_paths=()
if [ "$mode" == "off" ]; then
    [ -f "$paths_file" ] || {
        echo "error: $paths_file is missing" >&2
        exit 1
    }
    while IFS= read -r path || [ -n "$path" ]; do
        path="${path%$'\r'}"
        case "$path" in "" | "#"*) continue ;; esac
        case "/$path/" in
            //* | */../* | */./*)
                echo "error: $paths_file: '$path' must be a relative path inside the project" >&2
                exit 1
                ;;
        esac
        sharing_paths+=("$path")
    done <"$paths_file"
fi

# Copied over (not moved) so that each file keeps its mode.
for index in "${!targets[@]}"; do
    cat "${results[$index]}" >"${targets[$index]}"
done
for path in ${sharing_paths[@]+"${sharing_paths[@]}"}; do
    rm -rf "${dir:?}/$path"
done
