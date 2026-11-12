#!/usr/bin/env bash
# Puts docs/release-notes/<version>.md into a release description, right after its first line.
#   scripts/release-notes.sh <version> <notes-file>   edit a local notes file in place
#   scripts/release-notes.sh <version>                update the published GitHub release
# The section sits between marker comments, so running it again replaces it. Without a notes
# file for the version, nothing changes. The second form needs GH_TOKEN and GITHUB_REPOSITORY.
set -euo pipefail
VERSION=${1:?version, e.g. 5.8.3-x.2}
SECTION="$(dirname "$0")/../docs/release-notes/$VERSION.md"

insert() { # $1: description file, edited in place
  python3 - "$1" "$SECTION" <<'PY'
import os, re, sys
path, section = sys.argv[1], sys.argv[2]
body = open(path).read()
body = re.sub(r"\n*<!-- x-notes -->.*?<!-- /x-notes -->\n*", "\n\n", body, flags=re.S)
if os.path.isfile(section):
    block = "<!-- x-notes -->\n" + open(section).read().strip() + "\n<!-- /x-notes -->"
    first, _, rest = body.partition("\n")
    body = first + "\n\n" + block + "\n\n" + rest.lstrip("\n")
open(path, "w").write(body)
PY
}

if [ $# -ge 2 ]; then
  insert "$2"
  exit 0
fi
[ -f "$SECTION" ] || { echo "No $SECTION, nothing to do"; exit 0; }
notes=$(mktemp)
gh release view "$VERSION" --repo "$GITHUB_REPOSITORY" --json body --jq .body > "$notes"
insert "$notes"
gh release edit "$VERSION" --repo "$GITHUB_REPOSITORY" --notes-file "$notes"
echo "Updated the description of $VERSION"
