#!/usr/bin/env bash
# Checks that the release keystore, alias and passwords from the signing secrets fit together.
#   STORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... scripts/verify-keystore.sh <keystore>
# Never prints the secrets themselves.
set -uo pipefail
KS=${1:?keystore path}
fail() { echo "::error::$1 See docs/RELEASING.md"; exit 1; }

[ -s "$KS" ] || fail "KEYSTORE decodes to an empty file: the secret must hold the base64 of the .jks file."
for v in STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  [ -n "${!v:-}" ] || fail "Signing secret for $v is empty."
done
java "$(dirname "$0")/VerifyKey.java" "$KS" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
exit "${PIPESTATUS[0]}"
