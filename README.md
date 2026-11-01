# Kanade

Kanade is an Android media player built on the NewPipe codebase. It brings streaming playback together with an offline library for synced videos, downloads, favorites, watch progress, and storage analysis. A companion on-device service, `videod`, handles media fetching and download jobs outside the app process.

> Kanade is an independent project based on NewPipe. It is not affiliated with or endorsed by NewPipe e.V. or the media services it supports. Service availability and behavior may change at any time.

## Features

- Browse and play media from supported providers, including YouTube and Bilibili.
- Use background playback, a floating player, queues, and playback controls.
- Sync video sources into an offline library, track downloads and watch progress, and manage favorites.
- Browse saved media and analyze its storage usage.
- Keep service cookies and download jobs in the separate `videod` process.
- Use the Android app without Google Play Services.

Provider support depends on the upstream NewPipe Extractor and `videod`; not every feature is available for every provider.

## Requirements

- Android 6.0 (API 23) or newer.
- JDK 21 to build the Android app.
- Go to build `videod` for an Android arm64 device.
- For on-device media fetching and downloads, a running `videod` service and the required Termux media tools. See [`services/videod/README.md`](services/videod/README.md) for deployment details.

## Build the Android app

Build a debug APK from the repository root:

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on a connected device with:

```sh
./gradlew :app:installDebug
```

If JDK 21 is not on `PATH` and Nix is available, use:

```sh
nix-shell -p jdk21 --run './gradlew :app:assembleDebug'
```

More build and device installation notes are in [`doc/gradle.md`](doc/gradle.md).

## Build videod

```sh
cd services/videod
make
```

This builds the Android arm64 service binary `videod-arm64`. The service listens on `127.0.0.1:8732`; app setup and deployment instructions are in [`services/videod/README.md`](services/videod/README.md).

## Project layout

- `app/` — Android application and offline library.
- `shared/` — shared Kotlin Multiplatform code and settings UI.
- `desktopApp/` — desktop application module.
- `services/videod/` — Go service for media access, local storage, and download jobs.

## Upstream and license

Kanade is based on [NewPipe](https://github.com/TeamNewPipe/NewPipe), with additional app and service code in this repository. The repository includes the [GNU General Public License, version 3](LICENSE); individual files and third-party components may have their own notices and license terms.

Kanade is not the official NewPipe app. Do not use NewPipe names or branding in a way that suggests endorsement. Distribution must comply with applicable licenses, the upstream trademark policy, and the terms of the media services you access.
