#!/usr/bin/env bash
# Run the Kanade panel on the desktop host (the emulator) and capture frames.
#
# The device needs an RG34XX; this harness builds the same TSX against the
# linux-app profile, installs the KANADE bridge into the SDK guest crate, runs
# videod locally and screenshots what the panel would draw.
#
#   pocket/scripts/emulator.sh            # build, run, capture
#   pocket/scripts/emulator.sh --shots    # only re-capture from a running host
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
SDK=${POCKETJS_SDK:-$HOME/development/.pocketjs-star-drift-sdk}
BUN=${BUN:-$HOME/.bun/bin/bun}
OUTDIR=${OUTDIR:-$REPO/pocket/docs/emulator}
DATA=${KANADE_DATA:-/tmp/kanade-emu-data}
PORT=${KANADE_PORT:-8790}
DISPLAY_NUM=${KANADE_DISPLAY:-:99}
SHOTS=1
[ "${1:-}" = "--shots" ] && SHOTS=1

mkdir -p "$OUTDIR"

echo "== 1/6 helper binaries (x86)"
# The Go toolchain sometimes lives only in the module cache; find it by
# directory name so no "@version" literal is needed here.
if [ -z "${GO:-}" ] && ! command -v go >/dev/null 2>&1; then
  tc=$(find "$HOME/go/pkg/mod/golang.org" -maxdepth 1 -name 'toolchain*' -type d 2>/dev/null | head -1)
  [ -n "$tc" ] && GO=$tc/bin/go
fi
GO=${GO:-$(command -v go)}
[ -x "$GO" ] || { echo "emulator: go not found (set GO=)" >&2; exit 1; }
(cd "$REPO/services/videod" && \
  CGO_ENABLED=0 "$GO" build -o /tmp/kanade-ctl ./cmd/kanade-ctl && \
  CGO_ENABLED=0 "$GO" build -o /tmp/videod-host ./cmd/videod)

echo "== 2/6 videod with seeded data"
pkill -x videod-host 2>/dev/null || true
pkill -x videod-amd64 2>/dev/null || true
rm -rf "$DATA" && mkdir -p "$DATA"
KANADE_DOWNLOADS="$DATA/downloads" nohup /tmp/videod-host -addr "127.0.0.1:$PORT" -data "$DATA" -seed -seed-library >/tmp/videod-emu.log 2>&1 &
sleep 2
KANADE_BASE="http://127.0.0.1:$PORT" /tmp/kanade-ctl GET /api/health >/dev/null \
  || { echo "videod did not answer"; tail -5 /tmp/videod-emu.log; exit 1; }

echo "== 3/6 install the KANADE bridge into the SDK guest"
python3 "$REPO/pocket/scripts/patch-desktop-kanade.py" "$SDK"

echo "== 4/6 compile the package for linux-app"
mkdir -p "$SDK/apps/kanade" "$SDK/programs/kanade"
rsync -a --delete "$REPO/pocket/app/" "$SDK/apps/kanade/"
cp "$REPO/pocket/config/profile.ts" "$SDK/programs/kanade/profile.ts"
cp "$REPO/pocket/config/pocket.runtime-config.ts" "$SDK/programs/kanade/pocket.runtime-config.ts"
(cd "$SDK" && export PATH="$HOME/.bun/bin:$PATH" && \
  "$BUN" tools/pocket.ts compile \
    --runtime-config programs/desktop/linux/pocket.runtime-config.ts \
    --target linux-app --manifest apps/kanade/pocket.json \
    --output /tmp/kanade-desktop.pocket 2>&1 | tail -4)

echo "== 5/6 build the host binary"
# A running host holds the artifact open, and the build then fails with
# ETXTBSY while copying it into place. comm is truncated to "pocket-desktop-",
# so match the cmdline instead of using pkill -x.
for pid in $(ps -eo pid,args | awk '/program\/pocket-desktop-host/ {print $1}'); do
  kill -9 "$pid" 2>/dev/null || true
done
sleep 1
(cd "$SDK" && export PATH="$HOME/.bun/bin:$PATH" && \
  "$BUN" tools/pocket.ts build \
    --config programs/desktop/linux/pocket.runtime-config.ts --target linux-app \
    --pocket /tmp/kanade-desktop.pocket --out /tmp/kanade-host 2>&1 | tail -5)

[ "$SHOTS" = 1 ] || { echo "built only"; exit 0; }

echo "== 6/6 run under Xvfb and capture"
pkill -f 'Xvfb '"$DISPLAY_NUM" 2>/dev/null || true
Xvfb "$DISPLAY_NUM" -screen 0 720x480x24 >/tmp/xvfb.log 2>&1 &
sleep 1
# `program` is a directory holding the binary plus a `run` wrapper; -x is true
# for directories, so test for a file.
for candidate in /tmp/kanade-host/program/pocket-desktop-host /tmp/kanade-host/pocket-desktop-host; do
  [ -f "$candidate" ] && HOST_BIN=$candidate && break
done
[ -n "${HOST_BIN:-}" ] || { echo "emulator: no host binary"; exit 1; }
# `VAR=x cmd && ...` applies the assignment to cmd only, so it must go through
# env for the backgrounded host or kanade-ctl silently falls back to :8732.
cd /tmp/kanade-host/program
env -u WAYLAND_DISPLAY DISPLAY="$DISPLAY_NUM" \
  KANADE_CTL=/tmp/kanade-ctl KANADE_BASE="http://127.0.0.1:$PORT" \
  nohup "$HOST_BIN" --app kanade --js kanade.js --pak kanade.pak --viewport 360x240 --density 1 --title Kanade --fixed >/tmp/kanade-host.log 2>&1 &
sleep 6

# Capture the panel window, not the screen: Xvfb's root is 720x480 while the
# linux-app host draws the 360x240 logical viewport 1:1, so a root grab is a
# small panel in a large black field that reads as a scaling bug. The -2x file
# is the same pixels at device density (360x240 -> 720x480), nearest neighbour,
# for reading text; it is an upscale, not a re-render.
shot() {
  DISPLAY="$DISPLAY_NUM" import -window "$WID" "$OUTDIR/$1" 2>/dev/null \
    || DISPLAY="$DISPLAY_NUM" scrot -u "$OUTDIR/$1"
  convert "$OUTDIR/$1" -filter point -resize 200% "$OUTDIR/${1%.png}-2x.png" 2>/dev/null || true
  echo "shot: $OUTDIR/$1 ($(identify -format '%wx%h' "$OUTDIR/$1" 2>/dev/null))"
}
# The host maps the panel 1:1 into a window; the root capture is the panel.
# No window manager runs under Xvfb, so focus is never assigned: address the
# window by id or every key lands on the root and nothing moves.
WID=$(DISPLAY="$DISPLAY_NUM" xdotool search --name "Kanade" | head -1)
[ -n "$WID" ] || { echo "emulator: host window not found"; exit 1; }
# winit drops synthetic XSendEvent keys, so give the window real input focus
# (XSetInputFocus) and type at the display instead of at the window.
DISPLAY="$DISPLAY_NUM" xdotool windowfocus --sync "$WID" 2>/dev/null || true
key() { DISPLAY="$DISPLAY_NUM" xdotool key --clearmodifiers "$1"; sleep 1; }

shot 01-library.png
key Down; key Down; shot 02-cursor.png
key Right; shot 03-watch-later.png
key Right; key Right; key Right; shot 04-history.png
key Left; key Left; key Left; shot 05-back-to-library.png

# File sync: R triggers KANADE sync -> videod POST /api/sync. The service logs
# the request, which is what proves the bridge reached it from the panel.
# The guest arms its pad handler after a few frames, and a key sent before that
# is dropped, so retry until the service actually logs the request instead of
# asserting on one press.
for attempt in 1 2 3 4 5 6; do
  key r
  grep -q "/api/sync" /tmp/videod-emu.log && break
  key w
  grep -q "/api/sync" /tmp/videod-emu.log && break
  sleep 1
done
shot 06-sync.png
printf 'sync requests seen: %s\n' "$(grep -c '/api/sync' /tmp/videod-emu.log)"

echo "captured into $OUTDIR"
tail -4 /tmp/kanade-host.log
