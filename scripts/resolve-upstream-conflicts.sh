#!/usr/bin/env bash
# Resolves the merge conflicts the fork policy can decide on its own:
#  - .github/**    upstream automation is not used by the fork -> take the deletion
#  - README.md, SECURITY.md   the fork version wins
#  - hook files    take upstream, then re-apply the fork hooks (scripts/apply-hooks.py)
# Anything else is a real conflict between upstream code and fork changes and is reported.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
HOOK_FILES=$(python3 -I -B - <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("hooks", "scripts/apply-hooks.py")
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
print("\n".join(m.HOOKS))
PY
)
unresolved=()
rehook=()
while IFS= read -r path; do
  [ -n "$path" ] || continue
  if grep -qxF -- "$path" <<<"$HOOK_FILES"; then
    git checkout --theirs -- "$path"
    rehook+=("$path")
    continue
  fi
  case "$path" in
    .github/*)
      git rm -q -f -- "$path" 2>/dev/null || true
      echo "resolved (drop upstream automation): $path" ;;
    README.md|SECURITY.md)
      git checkout --ours -- "$path" && git add -- "$path"
      echo "resolved (keep fork): $path" ;;
    *)
      unresolved+=("$path") ;;
  esac
done < <(git diff --name-only --diff-filter=U)

if [ ${#rehook[@]} -gt 0 ]; then
  if python3 -I -B scripts/apply-hooks.py "${rehook[@]}"; then
    git add -- "${rehook[@]}"
    printf 'resolved (upstream + fork hooks): %s\n' "${rehook[@]}"
  else
    unresolved+=("${rehook[@]}")
  fi
fi

if [ ${#unresolved[@]} -gt 0 ]; then
  echo "Unresolved conflicts:"
  printf '  %s\n' "${unresolved[@]}"
  printf '%s\n' "${unresolved[@]}" > "${CONFLICTS_FILE:-/dev/null}"
  exit 1
fi
