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

Frames are captured from the panel window itself (360x240), not the screen, and
each `*-2x.png` is the same pixels at device density for reading text.

- `01-library.png` — LIBRARY tab, 12 items, `1/12`, titles in Japanese and
  Chinese. That the CJK titles are glyphs and not boxes is the point: the baked
  character set is the only glyph source on this profile.
- `02-cursor.png`, `03-watch-later.png`, `04-history.png`, `05-back-to-library.png`
  — d-pad and tab navigation.
- `07-nas.png`, `08-nas-cursor.png`, `09-nas-keyboard.png` — the NAS page
  (A edit, START keys, B back), the cursor on HOST, and the on-screen keyboard
  the framework docks at the bottom. The keyboard frame was captured from the
  same build by driving it directly: a scripted tap occasionally falls between
  pad samples and the harness cannot tell that apart from a broken binding.
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
- Xvfb's root is 720x480 while the linux-app host draws the 360x240 logical
  viewport 1:1, so a root grab is a small panel in a large black field. Capture
  the window id; the `-2x` copies are nearest-neighbour upscales, not renders at
  device density.
- Wayland wins over `DISPLAY` when `WAYLAND_DISPLAY` is set, which draws on the
  real desktop and leaves the Xvfb root black; the host is started with
  `env -u WAYLAND_DISPLAY`.
- The guest arms its pad handler after a few frames, so a key sent immediately
  is dropped; the sync step retries until the service logs the request.

## Not covered

Audio, the panel's own GPU path and the pad driver are device-only. The
transfer of the package to the card and its admission are exercised by
`pocket/scripts/deploy.sh` on the device.

## Captures

The run serves the real library, so the frames contain real titles and cover
art. They are committed on purpose: the layout questions this directory answers
(sixteen-aligned textures, a column that lines up, real CJK at 2x) are only
visible on real content, and synthetic rows hid three separate bugs.
