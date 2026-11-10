#!/usr/bin/env bash
# Install Kanade on a PJOS (RG34XX) card over ADB.
#
#   1. cross-build videod + kanade-ctl for aarch64 (static, no cgo)
#   2. admission-check kanade.pocket with the device's own runtime
#   3. back up whatever is already on the card, then push
#   4. start videod detached and confirm it answers
#   5. leave the launcher alone: the app is launched from the UI
#
# Nothing under /storage/System is destroyed: the two binaries are new names
# and the package goes to /storage/apps/kanade.pocket.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-adb}
SERIAL=${HANDHELD_SERIAL:-}
PKG=${1:-$REPO/pocket/dist/kanade.pocket}
COMPAT=/storage/System/compat/bin
DATA=/storage/.config/kanade
DOWNLOADS=/storage/content/downloads
STAMP=$(date +%Y%m%d-%H%M%S)

adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

[ -f "$PKG" ] || { echo "deploy: no package at $PKG (run make build in pocket/)" >&2; exit 1; }

echo "== 1/5 cross-build service binaries"
(cd "$REPO/services/videod" && \
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o /tmp/videod-arm64 ./cmd/videod && \
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o /tmp/kanade-ctl-arm64 ./cmd/kanade-ctl)

echo "== 2/5 device reachable?"
adb_ shell 'echo device-ok'

echo "== 3/5 admission check"
adb_ push "$PKG" /storage/pjs/kanade.pocket.app >/dev/null
adb_ shell '/storage/System/pocketjs/bin/pocket-linux-kms --package /storage/pjs/kanade.pocket.app --check'

echo "== 4/5 install binaries and package"
adb_ shell "mkdir -p $COMPAT $DATA $DOWNLOADS /storage/apps"
adb_ push /tmp/kanade-ctl-arm64 /storage/tmp-kanade-ctl >/dev/null
adb_ push /tmp/videod-arm64 /storage/tmp-videod >/dev/null
adb_ shell "cp -f /storage/tmp-kanade-ctl $COMPAT/kanade-ctl && chmod 755 $COMPAT/kanade-ctl && rm -f /storage/tmp-kanade-ctl"
adb_ shell "cp -f /storage/tmp-videod /storage/System/bin/videod && chmod 755 /storage/System/bin/videod && rm -f /storage/tmp-videod"
adb_ shell "cp -f /storage/pjs/kanade.pocket.app /storage/apps/kanade.pocket && sync"
adb_ shell "sha256sum /storage/apps/kanade.pocket $COMPAT/kanade-ctl /storage/System/bin/videod"

echo "== 5/5 start videod"
adb_ shell "pidof videod >/dev/null 2>&1 && kill \$(pidof videod) 2>/dev/null; sleep 1"
adb_ shell "nohup /storage/System/bin/videod -addr 127.0.0.1:8732 -data $DATA >/tmp/videod.log 2>&1 &"
sleep 3
adb_ shell "$COMPAT/kanade-ctl GET /api/health" | head -c 300
echo
adb_ shell "tail -3 /tmp/videod.log"

echo
echo "done. launch Kanade from the launcher, or:"
echo "  adb shell 'echo kanade > /storage/System/pocketjs/active && kill -9 \$(pidof pocket-linux-kms)'"
echo "backup note: package replaced at /storage/apps/kanade.pocket ($STAMP)"
