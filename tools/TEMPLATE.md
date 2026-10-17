# Template tools

These tools serve the template repository itself. The generator deletes this file when it creates a project.

- `bash tools/gate.sh [--no-docker] [--offline]`: resolves the optional `sharing` part both ways in a temporary copy
  and builds, tests and lints each variant (and its Docker image), ending with `== N passed, 0 failed`.
- `bash tools/resolve-markers.sh <dir> on|off`: resolves the sharing markers in place, as the generator does
  (`tools/resolve-markers.test.sh` tests it).
- `bash tools/deploy-lint.sh`: static checks of `deploy/` and of the repository's public hygiene.
