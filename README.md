# Lumynoid

Lumynoid is an Android app for chatting with an AI model using your own API key. There is no Lumynoid account and no Lumynoid server: the app talks directly to the endpoint you choose (an OpenAI-compatible provider or Anthropic).

> Status: early development. The first releases are pre-releases (`v0.x`).

## Install

Lumynoid is distributed only as a signed APK downloaded manually from this repository's [GitHub Releases](../../releases). There is no Play Store or F-Droid listing and the app does not update itself: to update, download the newer APK.

Each release carries one `lumynoid-<version>.apk` and a `SHA256SUMS` file. Release signing-key SHA-256 fingerprint:

```
19:39:03:AA:56:2F:76:B0:88:B0:F0:AE:60:5C:7B:21:63:5C:47:59:1D:9E:DC:93:56:A0:B4:ED:28:73:DC:B4
```

Verify an APK with `apksigner verify --print-certs` and the release's `SHA256SUMS`. Maintainers: see [docs/release.md](docs/release.md).

## Background replies

On Android 13+, Lumynoid asks for notification permission the first time you open chat with a provider configured. Allow it to see the in-progress reply notification. Replies still run if you decline; you can enable notifications later in Android's app settings.

## Build

Requirements: JDK 17 and the Android SDK (platform 37).

```
./gradlew :app:assembleDebug          # debug APK, package dev.lusalhi.lumynoid.debug
./gradlew :core:test :llm:test :data:testDebugUnitTest
./gradlew :app:assembleRelease        # unsigned arm64-v8a APK unless the release key env vars are set
```

The structured-tool loop is exercised only by in-process JVM fixtures. The Android app does not register tools or run shell commands.

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
