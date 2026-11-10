# Kanade on PJOS (PocketJS)

The panel client for `videod`, built as a PocketJS package and rendered by
`pocket-linux-kms` on the RG34XX. This is not the Android app: PJOS has no
Android runtime, so the UI is Solid TSX compiled to `dist/kanade.pocket` and
the data comes from videod over loopback.

```
pocket/app/main.tsx        the panel UI (Library / Later / Favorites / History)
pocket/app/kanade-model.ts JSON -> rows, duration and progress formatting
pocket/app/host.ts         the one host surface: ui.__launcher("KANADE\t…")
pocket/config/             rocknix-h700-kms profile, identical to the boot launcher
```

## Why the app cannot call HTTP itself

The `rocknix-h700-kms` profile declares only `input.buttons` and
`text.glyphs.baked`. There is no `net` capability, so `fetch` does not exist in
the guest. Every data path goes through one narrow host command:

```
KANADE<TAB>status|library|watch_later|favorites|history|downloads
KANADE<TAB>settings|storage|sync|clear
KANADE<TAB>play|watched|later|favorite<TAB><video_id>
```

The Rust side (`PJOS-LINUX/src/apply-pocketjs-kanade.py`) maps each verb to a
fixed endpoint and shells out to the static `kanade-ctl` helper with a 12 s hard
timeout. The guest therefore cannot address arbitrary URLs or methods, and the
render thread only waits on a short-lived process.

## Build

```sh
make build     # -> dist/kanade.pocket  (stages app/ and config/ into the SDK)
make check     # admission check with the device's own runtime
make deploy    # push over ADB and launch once
```

Fonts are staged from the launcher checkout (`FONT_SRC`) rather than committed
twice; `app/fonts/` and `dist/` are ignored.

## Runtime requirement

The `KANADE` bridge arm must exist in the device's `pocket-linux-kms`. Build it
with `PJOS-LINUX/build-pocketjs-runtime-musl.sh`, which applies
`apply-pocketjs-kanade.py` and fails if the arm is missing. An older runtime
admits the package but every call returns `""`, which the UI renders as
`OFFLINE` / `videod is not running` — that is the symptom to check first.

## Deploy pieces

| Piece | Where on the card |
|---|---|
| `kanade.pocket` | `/storage/apps/kanade.pocket` |
| `kanade-ctl` (static arm64) | `/storage/System/compat/bin/kanade-ctl` |
| `videod` (static arm64) | started from `/storage/System/bin/` or a unit |

Start videod before the launcher, or press `B` in the app after starting it.
