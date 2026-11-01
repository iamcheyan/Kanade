# videod

On-device service that owns everything the app must not do in its own process:
cookies, yt-dlp, ffmpeg, the download queue and the SQLite database.

The app talks to it over plain HTTP on `127.0.0.1:8732`; see
`VideodClient` in `app/src/main/java/org/schabi/newpipe/local/offline/`.

## Why it is a separate process

* Cookie jars stay out of the app sandbox.
* yt-dlp and ffmpeg are Termux binaries with their own libraries.
* The queue survives the app being swiped away.

## Build

```sh
make            # arm64 binary for the phone -> videod-arm64
make run        # run on the host against a scratch data dir
```

`make build` cross-compiles with `CGO_ENABLED=0`; the SQLite driver is the pure
Go `modernc.org/sqlite`, so no NDK is needed.

## Install on the device

The service lives in Termux's home, started by `start.sh` (which sets `PATH`,
`HOME`, DNS and the data directory). Replacing the binary:

```sh
scp videod-arm64 phone:/tmp/
adb push /tmp/videod-arm64 /data/local/tmp/
adb shell su -c 'cp /data/local/tmp/videod-arm64 /data/data/com.termux/files/home/videod/videod.new \
  && chmod 755 /data/data/com.termux/files/home/videod/videod.new \
  && chown $(stat -c %u:%g /data/data/com.termux/files/home/videod/data) \
       /data/data/com.termux/files/home/videod/videod.new'
adb shell su -c 'pkill -x videod; mv .../videod .../videod.bak; mv .../videod.new .../videod'
adb shell su -c 'setsid /data/data/com.termux/files/home/videod/start.sh >> .../run.log 2>&1 &'
```

## Endpoints the app uses

`/api/sync`, `/api/library`, `/api/watch_later`, `/api/favorites`,
`/api/downloads`, `/api/history`, `/api/settings`, `/api/storage`,
`/api/storage/breakdown`, `/api/media/{id}`.

## Known limitation: videos without audio streams

YouTube sometimes returns a player response with video-only formats for some
videos (observed with the `visionos` client). yt-dlp then reports
`Requested format is not available`, because no `bestaudio` exists to pair with.
The format selector has a fallback chain, but it cannot invent a missing audio
stream; such videos need a different extractor client or an authenticated
session.
