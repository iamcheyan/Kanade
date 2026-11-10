#!/usr/bin/env python3
"""Install the KANADE `ui.__launcher` bridge into a PocketJS SDK checkout.

The desktopp host otherwise has no `__launcher` at all, so the emulator run of
the Kanade panel shows `OFFLINE` and the sync path cannot be exercised. This
copies pocket/bridge/kanade.rs into the guest crate and registers it, mirroring
what pocketshell/scripts/patch-desktop-bridge.py does for its own commands.

Idempotent: an already-patched lib.rs is left alone.

    python3 pocket/scripts/patch-desktop-kanade.py <sdk-root>
"""
from pathlib import Path
import shutil
import sys

REPO = Path(__file__).resolve().parent.parent
MODULE = "kanade_bridge"
ANCHOR = 'ui.set("__viewport", viewport)?;'
INSTALL = f'''
    ui.set(
        "__launcher",
        Function::new(ctx.clone(), |command: String| {MODULE}::command(&command))?,
    )?;
'''

if len(sys.argv) != 2:
    raise SystemExit(__doc__)
sdk = Path(sys.argv[1]).resolve()
guest = sdk / "engine/guest-rquickjs/src"
lib = guest / "lib.rs"
if not lib.is_file():
    raise SystemExit(f"patch-desktop-kanade: no guest crate at {guest}")

shutil.copyfile(REPO / "bridge" / "kanade.rs", guest / f"{MODULE}.rs")
source = lib.read_text()

marker = "use rquickjs::{"
if marker not in source:
    raise SystemExit("patch-desktop-kanade: guest lib.rs has no rquickjs import block")
if f"mod {MODULE};" not in source:
    source = source.replace(marker, f"mod {MODULE};\n\n{marker}", 1)
if f'"{MODULE}::command' not in source:
    if ANCHOR not in source:
        raise SystemExit("patch-desktop-kanade: ui.__viewport anchor missing")
    source = source.replace(ANCHOR, ANCHOR + INSTALL, 1)
lib.write_text(source)
print(f"patch-desktop-kanade: ui.__launcher -> {guest}/{MODULE}.rs")
