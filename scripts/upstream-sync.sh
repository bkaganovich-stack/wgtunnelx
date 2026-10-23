#!/usr/bin/env bash
# Merges an upstream WG Tunnel release tag into the current branch and applies the fork policy.
#   scripts/upstream-sync.sh 5.8.3
# Exit codes: 0 merged (or already merged), 2 conflicts that need a human (merge aborted).
set -euo pipefail
TAG=${1:?upstream tag}
cd "$(git rev-parse --show-toplevel)"

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/wgtunnel/android.git
git fetch --no-tags upstream "+refs/tags/$TAG:refs/tags/upstream/$TAG"

if git merge-base --is-ancestor "upstream/$TAG" HEAD; then
  echo "upstream $TAG is already merged"
else
  git config merge.ours.driver true
  git config rerere.enabled true
  git config rerere.autoUpdate true
  if ! git merge --no-ff --no-edit -m "Merge upstream WG Tunnel $TAG" "upstream/$TAG"; then
    if scripts/resolve-upstream-conflicts.sh; then
      git commit --no-edit
    else
      git merge --abort
      exit 2
    fi
  fi
fi

scripts/prune-upstream-files.sh
# a clean merge can still lose a hook when upstream rewrites the surrounding code
if ! python3 -I -B scripts/apply-hooks.py; then
  echo "Fork hooks cannot be applied to upstream $TAG" >&2
  python3 -I -B scripts/apply-hooks.py --check | grep -E '^(FAILED|MISSING)' | awk '{print $2}' \
    > "${CONFLICTS_FILE:-/dev/null}" || true
  git reset -q --hard ORIG_HEAD 2>/dev/null || true
  exit 2
fi
# a new upstream version restarts the fork revision
sed -i 's/^xRevision=.*/xRevision=1/' wgtunnelx.properties
git add -A
git diff --cached --quiet || git commit -m "chore(x): prepare $TAG-x.1"
