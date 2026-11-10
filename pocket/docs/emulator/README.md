# Emulator run: the panel against a live videod

The device path needs an RG34XX. This is the same TSX compiled for the
linux-app profile, drawn by `pocket-desktop-host` under Xvfb, talking to a real
`videod` on loopback — so the list, the counter and the storage row come from
the service, not from fixtures in the app.

    bash pocket/scripts/emulator.sh

The script builds the x86 helpers, starts an isolated `videod` (port 8790, its
own data dir, `-seed -seed-library`), installs the KANADE `__launcher` bridge
into the SDK guest crate, compiles and builds the host, then drives it with
xdotool and captures frames into this directory.

## What the frames show

- `01-library.png` — LIBRARY tab, 12 items, `1/12`, titles in Japanese and
  Chinese. That the CJK titles are glyphs and not boxes is the point: the baked
  character set is the only glyph source on this profile.
- `02-cursor.png`, `03-watch-later.png`, `04-history.png`, `05-back-to-library.png`
  — d-pad and tab navigation.
- `06-sync.png` — after R1. The panel issued `KANADE<TAB>sync`, the bridge ran
  `kanade-ctl POST /api/sync`, and the service logged it:

      2026/11/10 09:54:32 POST /api/sync 0s

  `GET /api/storage` then reported the twelve files the seed created
  (`files:12, bytes:59136`), which is the same walk the device does over
  `/storage/content/downloads`.

## Things this harness has to get right

- `comm` is truncated to 15 characters, so the host process is
  `pocket-desktop-`; kill it by cmdline or the next build fails with `ETXTBSY`
  while copying the artifact into place.
- `VAR=x cmd && ...` binds the assignment to `cmd` only. The host must be
  started through `env` or `kanade-ctl` falls back to the default port and the
  run silently tests the wrong service.
- winit ignores synthetic `XSendEvent` keys: the window needs real input focus
  (`xdotool windowfocus`) and the keys go to the display, not the window.
- Wayland wins over `DISPLAY` when `WAYLAND_DISPLAY` is set, which draws on the
  real desktop and leaves the Xvfb root black; the host is started with
  `env -u WAYLAND_DISPLAY`.
- The guest arms its pad handler after a few frames, so a key sent immediately
  is dropped; the sync step retries until the service logs the request.

## Not covered

Audio, the panel's own GPU path and the pad driver are device-only. The
transfer of the package to the card and its admission are exercised by
`pocket/scripts/deploy.sh` on the device.
