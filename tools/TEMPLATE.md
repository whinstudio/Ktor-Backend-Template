# Template tools

These tools serve the template repository itself. The generator deletes this file when it creates a project.

- `bash tools/gate.sh [--no-docker] [--offline]`: resolves the optional parts in a temporary copy per variant
  (`full`: sharing and sample on; `sharing-off`; `cmp`: sample off) and builds, tests and lints each one (and the
  Docker image of `full` and `sharing-off`), ending with `== N passed, 0 failed`.
- `bash tools/resolve-markers.sh <dir> <option> on|off`: resolves one option's markers (`cmpose:<option>`) in place
  and, when off, deletes the paths of `tools/<option>-paths.txt`, as the generator does; the options run as `sharing`
  first, then `sample` (`tools/resolve-markers.test.sh` tests it).
- `bash tools/deploy-lint.sh`: static checks of `deploy/` and of the repository's public hygiene.
