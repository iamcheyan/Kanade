#!/usr/bin/env bash
# Compile app/ into dist/kanade.pocket against the PocketJS SDK.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
SDK=${POCKETJS_SDK:-$HOME/development/.pocketjs-star-drift-sdk}
BUN=${BUN:-$HOME/.bun/bin/bun}
TARGET=${TARGET:-rocknix-h700-kms}
APP_DIR="$SDK/apps/kanade"
PROGRAM_DIR="$SDK/programs/kanade"
OUT="$REPO/dist/kanade.pocket"

[ -x "$BUN" ] || { echo "build: bun not found at $BUN" >&2; exit 1; }
[ -d "$SDK/contracts" ] || { echo "build: PocketJS SDK not found at $SDK" >&2; exit 1; }

mkdir -p "$APP_DIR" "$PROGRAM_DIR" "$REPO/dist"
rsync -a --delete "$REPO/app/" "$APP_DIR/"
cp "$REPO/config/profile.ts" "$PROGRAM_DIR/profile.ts"
cp "$REPO/config/pocket.runtime-config.ts" "$PROGRAM_DIR/pocket.runtime-config.ts"

cd "$SDK"
echo "== check"
"$BUN" tools/pocket.ts check \
  --runtime-config "programs/kanade/pocket.runtime-config.ts" \
  --target "$TARGET" \
  --manifest "apps/kanade/pocket.json"

echo "== compile"
"$BUN" tools/pocket.ts compile \
  --runtime-config "programs/kanade/pocket.runtime-config.ts" \
  --target "$TARGET" \
  --manifest "apps/kanade/pocket.json" \
  --output "$OUT"

echo "== package"
"$BUN" tools/pocket-pack.ts inspect "$OUT"
sha256sum "$OUT"
ls -l "$OUT"
