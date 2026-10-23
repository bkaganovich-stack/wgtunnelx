#!/usr/bin/env bash
# Resolves the merge conflicts the fork policy can decide on its own:
#  - .github/**   : upstream automation is not used by the fork -> take the deletion
#  - README.md    : the fork README wins
# Anything else is a real conflict between upstream code and fork changes and is reported.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
unresolved=()
while IFS= read -r path; do
  [ -n "$path" ] || continue
  case "$path" in
    .github/*)
      git rm -q -f -- "$path" 2>/dev/null || true
      echo "resolved (drop upstream automation): $path" ;;
    README.md)
      git checkout --ours -- "$path" && git add -- "$path"
      echo "resolved (keep fork): $path" ;;
    *)
      unresolved+=("$path") ;;
  esac
done < <(git diff --name-only --diff-filter=U)

if [ ${#unresolved[@]} -gt 0 ]; then
  echo "Unresolved conflicts:"
  printf '  %s\n' "${unresolved[@]}"
  printf '%s\n' "${unresolved[@]}" > "${CONFLICTS_FILE:-/dev/null}"
  exit 1
fi
