#!/usr/bin/env bash
# Removes upstream automation that must not run in the fork: every workflow that is not one of
# ours (x-*.yml), Dependabot and the upstream funding file. Safe to run repeatedly.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
for f in .github/workflows/*.yml .github/workflows/*.yaml; do
  [ -e "$f" ] || continue
  case "$(basename "$f")" in x-*) continue ;; esac
  git rm -q -f "$f"; echo "pruned $f"
done
for f in .github/dependabot.yml .github/FUNDING.yml; do
  if [ -e "$f" ]; then git rm -q -f "$f"; echo "pruned $f"; fi
done
exit 0
