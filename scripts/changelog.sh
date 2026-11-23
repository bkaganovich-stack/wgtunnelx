#!/usr/bin/env bash
# Adds the CHANGELOG.md entry of a release, or replaces it if it is already there.
#   scripts/changelog.sh <version> [upstream-version] [xray-version]
# The body is docs/release-notes/<version>.md without its heading; without that file an existing
# entry keeps its text and a new one only names the WG Tunnel release it is based on. The upstream version defaults to the one in
# <version>, the Xray version to the one in the existing entry or in the <version> tag.
# A replaced entry keeps its place and date; a new one goes on top, dated today (UTC).
set -euo pipefail
VERSION=${1:?version, e.g. 5.8.3-x.2}
UPSTREAM=${2:-${VERSION%-x.*}}
XRAY=${3:-}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG_XRAY=$(git -C "$ROOT" show "refs/tags/$VERSION:xray/libxray.version" 2>/dev/null | tr -d '[:space:]' || true)

python3 -I - "$ROOT/CHANGELOG.md" "$ROOT/docs/release-notes/$VERSION.md" \
  "$VERSION" "$UPSTREAM" "$XRAY" "$TAG_XRAY" "$(date -u +%F)" <<'PY'
import os, re, sys
path, notes, version, upstream, xray, tag_xray, today = sys.argv[1:]
text = open(path).read()
entry_re = re.compile(r"^## " + re.escape(version) + r" — (\S+)\n.*?(?=^## |\Z)", re.M | re.S)
old = entry_re.search(text)

if not xray and old:
    found = re.search(r"Xray-core (v?[\w.]+)\.", old.group(0))
    xray = found.group(1) if found else ""
xray = xray or tag_xray
if not xray:
    sys.exit(f"changelog.sh: no Xray-core version for {version}")
if not xray.startswith("v"):
    xray = "v" + xray

if os.path.isfile(notes):
    body = "\n".join(l for l in open(notes).read().strip().splitlines() if not l.startswith("#")).strip()
elif old:
    # an entry written by hand (no notes file) keeps its text
    body = re.sub(r"\A## .*?\n\n(?:WG Tunnel .*?\n\n)?", "", old.group(0), flags=re.S).strip()
else:
    body = f"- Based on [WG Tunnel {upstream}](https://github.com/wgtunnel/android/releases/tag/{upstream})."
date = old.group(1) if old else today
entry = f"## {version} — {date}\n\nWG Tunnel {upstream}, Xray-core {xray}.\n\n{body}\n\n"

if old:
    text = text[:old.start()] + entry + text[old.end():]
else:
    first = re.search(r"^## ", text, re.M)
    at = first.start() if first else len(text)
    text = text[:at] + entry + text[at:]
open(path, "w").write(text.rstrip("\n") + "\n")
print(("Updated" if old else "Added") + f" the CHANGELOG.md entry of {version}")
PY
