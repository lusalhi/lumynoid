# Lumynoid

Lumynoid is an Android app for chatting with an AI model using your own API key. There is no Lumynoid account and no Lumynoid server: the app talks directly to the endpoint you choose (an OpenAI-compatible provider or Anthropic).

> Status: early development. The first releases are pre-releases (`v0.x`).

## Install

Lumynoid is distributed only as a signed APK downloaded manually from this repository's [GitHub Releases](../../releases). There is no Play Store or F-Droid listing and the app does not update itself: to update, download the newer APK.

New releases carry the signed `lumynoid-<version>.apk`, a versioned arm64 Linux rootfs, source provenance, and a `SHA256SUMS` file. Release signing-key SHA-256 fingerprint:

```
19:39:03:AA:56:2F:76:B0:88:B0:F0:AE:60:5C:7B:21:63:5C:47:59:1D:9E:DC:93:56:A0:B4:ED:28:73:DC:B4
```

Verify an APK with `apksigner verify --print-certs` and the release's `SHA256SUMS`. Maintainers: see [docs/release.md](docs/release.md).

## Background replies

On Android 13+, Lumynoid asks for notification permission the first time you open chat with a provider configured. Allow it to see the in-progress reply notification. Replies still run if you decline; you can enable notifications later in Android's app settings.

## Coding in chat

On arm64, chat can create and read private project files, install the bundled npm package offline, and run the built-in JSON formatter. Each conversation has its own workspace, reused by later messages. Activity cards show the operation and its result.

The configured model must support structured tool calls on arm64; the provider connection test only verifies text chat.

The first Linux runtime download and each file replacement require approval in chat. If you enabled notifications, tapping the approval notification takes you to its card; it cannot approve or decline the action. Leaving chat without killing the process keeps the request available. After process death, unfinished work is marked interrupted and is not replayed.

Open **Log** in chat to see tool targets, approvals and the full stored output, including after relaunch. A tool's own capture limit still applies. Deleting a conversation removes its log and private workspace, including its bundled package install. You can delete it once its queued, running or approval-waiting tasks have ended.

## Build

Build with JDK 17, Android SDK platform 37, Build Tools 37.0.0, and NDK 28.2.13676358. The native build also uses GNU Make, Python 3, curl, unzip, and patch. On an arm64 Linux host it uses a native Clang and LLD with the pinned NDK sysroot.
For a macOS app build, install GNU Make as `gmake`.

```
./gradlew :app:assembleDebug          # debug APK, package dev.lusalhi.lumynoid.debug
./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleRelease        # unsigned arm64-v8a APK unless the release key env vars are set
```

The arm64 instrumentation suite exercises the runtime installer, TaskRunner process cleanup, workspace file operations and deletion, overwrite approval, and the bundled offline npm and JSON formatter flow. Building the fixture archive requires a Linux host with `chroot` access through root or passwordless sudo. On x86-64, install `qemu-aarch64-static` before building it.

The rootfs build is offline: it uses the Alpine inputs bundled in `tools/userland/alpine-inputs.tar` and checks each one against `alpine-inputs.lock` before installation.
The lock records the upstream URLs; `alpine-source-provenance.tsv` records the exact Alpine recipes and licenses for the installed packages.

Run the same instrumentation APK on a connected arm64 device. Use an API 37 handset for the device check and the arm64 emulator for the separate emulator check.

```sh
./gradlew :userland:assembleDebugAndroidTest
adb install -t -r userland/build/outputs/apk/androidTest/debug/userland-debug-androidTest.apk
adb shell am instrument -w dev.lusalhi.lumynoid.userland.test/androidx.test.runner.AndroidJUnitRunner
```

For a phone-only check, build `./gradlew :userland-check:assembleDebug`. Install `tools/userland-check/build/outputs/apk/debug/userland-check-debug.apk`, open **Lumynoid Test**, and tap **Jalankan tes**. It runs the eight offline runtime and coding checks; run the instrumentation suite above for workspace deletion. **Hapus fixture lama** opens Android's uninstall confirmation for the instrumentation APK, not for the main Lumynoid app.

## Checks that gate every change

CI runs on every push and pull request, and these gates fail the build:

| Gate | What it enforces | Run it locally |
| --- | --- | --- |
| `:core` stays pure | `:core` has no Android, Ktor or Room dependency (AD-1) | see `.github/workflows/ci.yml` |
| Dependency policy | The release runtime classpath, transitive dependencies included, has no `com.google.android.gms`, `com.google.firebase`, `com.google.mlkit` or `com.google.android.play`, and every dependency declares an OSI-approved licence. Exceptions go in `config/license-allowlist.txt` with a reason (AD-17) | `./gradlew :app:checkDependencyPolicy` |
| Schema versions | A pull request may not edit an exported Room schema, reuse a version already on the base branch, or claim a version another open pull request claims (AD-19) | `python3 .github/scripts/check_schema_claims.py --base origin/main` |
| Release tags | A `v*` tag must be on `main`, equal `versionName`, raise `versionCode` over every earlier tag and keep Room schemas on main's migration line; the signed APK must match the committed certificate fingerprint (AD-25) | `python3 .github/scripts/check_release.py --self-test` |
| Persisted names | Every persisted subtype and enum entry has an explicit snake_case `@SerialName` (AD-19) | `./gradlew :core:test` |

## License

Copyright 2026 lusalhi. Licensed under the [Apache License, Version 2.0](LICENSE).
