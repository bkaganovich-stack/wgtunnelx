#!/usr/bin/env bash
# Validates the configs produced by XrayConfigBuilder with a real Xray-core build:
#   scripts/validate-xray-configs.sh <path-to-xray-binary> <dir-with-geoip.dat-and-geosite.dat>
# The Xray version should match xray/libxray.version.
set -euo pipefail
XRAY=${1:?xray binary}
GEO=${2:?geo dir}
OUT=$(mktemp -d)
export XRAY_DUMP_DIR="$OUT"
./gradlew --quiet :xray:testDebugUnitTest --tests 'com.wgtunnelx.xray.ConfigDumpTest' --rerun
status=0
for f in "$OUT"/*.json; do
  case "$f" in *.test.json) continue ;; esac
  # the tun inbound cannot be created without privileges; it is checked by unit tests instead
  python3 - "$f" "${f%.json}.test.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
for i in c["inbounds"]:
    if i["protocol"] == "tun":
        i.update({"protocol": "socks", "listen": "127.0.0.1", "port": 0, "settings": {"udp": True}})
json.dump(c, open(sys.argv[2], "w"))
PY
  if XRAY_LOCATION_ASSET="$GEO" "$XRAY" run -test -c "${f%.json}.test.json" >"$f.log" 2>&1; then
    echo "OK   $(basename "$f")"
  else
    echo "FAIL $(basename "$f")"; cat "$f.log"; status=1
  fi
done
exit $status
