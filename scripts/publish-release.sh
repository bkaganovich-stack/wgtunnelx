#!/usr/bin/env bash
# Creates the GitHub release of a built WG Tunnel X version.
#   scripts/publish-release.sh <apk-dir> <x-version> <upstream-version> <xray-version>
# Needs GH_TOKEN and GITHUB_REPOSITORY; HEAD must already be pushed.
set -euo pipefail
APK_DIR=${1:?apk dir}
VERSION=${2:?version, e.g. 5.8.3-x.1}
UPSTREAM=${3:?upstream version}
XRAY=${4:?xray version}
NOTES=$(mktemp)

APKSIGNER=$(command -v apksigner || ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/apksigner | sort -V | tail -1)
cd "$APK_DIR"
sha256sum -- *.apk > SHA256SUMS
cert=$("$APKSIGNER" verify --print-certs "$(find . -maxdepth 1 -name "*.apk" | head -1)" 2>/dev/null \
  | grep -m1 -Po '(?<=SHA-256 digest: ).*' || echo "n/a")
upstream_notes=$(gh api "repos/wgtunnel/android/releases/tags/$UPSTREAM" --jq '.body' 2>/dev/null \
  || echo "See https://github.com/wgtunnel/android/releases/tag/$UPSTREAM")
cd - >/dev/null

cat > "$NOTES" <<MD
**WG Tunnel X $VERSION** is [WG Tunnel $UPSTREAM](https://github.com/wgtunnel/android/releases/tag/$UPSTREAM) with domain-based split routing powered by Xray-core $XRAY.

Settings → **Domain routing (Xray)**: route traffic through the tunnel, directly or block it by domain, \`geosite:\`/\`geoip:\` category, IP, port, sniffed protocol or app.

### Downloads
| File | Devices |
| --- | --- |
| \`wgtunnel-standalone-v$VERSION-arm64.apk\` | most phones (64-bit ARM) |
| \`wgtunnel-standalone-v$VERSION-armv7.apk\` | older 32-bit ARM |
| \`wgtunnel-standalone-v$VERSION.apk\` | universal |

WG Tunnel X installs next to the original WG Tunnel (\`com.wgtunnelx.android\`) and updates itself from this repository.

Signing certificate SHA-256:
\`\`\`
$cert
\`\`\`
Verify: \`apksigner verify --print-certs <apk> | grep SHA-256\`, file hashes are in \`SHA256SUMS\`.

<details><summary>WG Tunnel $UPSTREAM release notes</summary>

$upstream_notes

</details>
MD

"$(dirname "$0")/release-notes.sh" "$VERSION" "$NOTES"

# the changelog commit and annotated tags need an identity even when no earlier step committed
git config user.name >/dev/null || git config user.name "github-actions[bot]"
git config user.email >/dev/null || git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

# the tag goes on the commit that has the release in CHANGELOG.md
"$(dirname "$0")/changelog.sh" "$VERSION" "$UPSTREAM" "$XRAY"
if ! git diff --quiet -- CHANGELOG.md; then
  git add CHANGELOG.md
  git commit -q -m "docs: changelog for $VERSION"
  git push origin HEAD:main
fi

git tag -a "$VERSION" -m "WG Tunnel X $VERSION (upstream $UPSTREAM)"
git push origin "refs/tags/$VERSION"
gh release create "$VERSION" \
  --repo "$GITHUB_REPOSITORY" \
  --title "WG Tunnel X $VERSION" \
  --notes-file "$NOTES" \
  --latest \
  "$APK_DIR"/*.apk "$APK_DIR"/SHA256SUMS
