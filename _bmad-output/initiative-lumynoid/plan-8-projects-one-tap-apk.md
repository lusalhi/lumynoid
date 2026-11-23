---
title: 'APK diagnostik Projects satu tombol'
type: feature
ticket: ''
created: '2026-11-23'
status: built
baseline_revision: '9b49675f8e89066fe4e705348ac930cfb60fe8db'
route: full
route_source: auto
risk: medium
review: quick
review_source: pinned
lenses_ran: []
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

Deliver one installable Android APK that runs Epic Projects 8.1–8.8 checks from one tap, shows each story's evidence and distinguishes failures from acceptance that requires the production release. No ADB, account, provider key or download is needed on the user's handset. Install alongside signed Lumynoid without touching its data.

## Boundaries & Constraints

**Always:** use the real TaskRunner, Room migrations, coding runtime and preview policy, with deterministic fixture responses, disposable data and packaged rootfs. Check observable outcomes, report the first failing assertion per scenario, and allow a repeat run. Keep test code, assets, credentials and manifest changes out of debug/release production builds; use a separate package ID.

**Never:** do not push, tag, publish, change app signing or mark Story 8.8 accepted. The diagnostic reproduces upgrade and integrated behavior with fixtures; the signed Epic 2 → Projects installation and live handset acceptance still require the owner.

</frozen-after-approval>

## Code Map

- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`: separate arm64 `projectsCheck` build type, debug signing, separate launcher and dependencies. Stage `:userland:stageUserlandTestAssets` and exported `data/schemas/**/4.json` only for the diagnostic variant.
- `app/src/projectsCheck/`: add the launcher/report and fixtures. `tools/userland-check/.../ChecksActivity.kt` is the existing one-tap report pattern. Use `UserlandRuntime.create`, packaged rootfs bytes via the catalog's Ktor fixture engine, `LumynoidData`, `TaskRunner` and its admission gate. Do not introduce a production-only switch for fixtures.
- `data/src/test/.../LegacyWorkspaceAdoptionTest.kt`, `app/src/androidTest/.../{PreviewNavigationTest,ProjectDeletionTest,ProjectChatSubmissionTest}.kt`: existing scenarios for v4 adoption, persisted-event preview and scoped recovery. Use their proved setup patterns, not `UiAutomation` in the handset APK.
- `app/src/main/.../{LumynoidApp,OfflinePreviewWebView,ArtifactHtmlActivity}.kt`: use existing event/scope validation and WebView; share the resolver with an isolated fixture only if needed, with no behavior change.

## Tasks & Acceptance

**Execution:**
- [x] `app/build.gradle.kts`, `app/src/projectsCheck/AndroidManifest.xml` — stage assets and register one isolated launcher.
- [x] `app/src/projectsCheck/kotlin/` — implement the one-tap runner, report, scripted provider and the following cases.
- [x] `README.md` — document APK build/use and the signed acceptance boundary.

**Acceptance Criteria:**
- Given two chats in one project, when A writes a file and installs the bundled package and B reads, edits and formats it, then both use the same file and package but keep separate logs and request history. Another project and plain chat cannot access A's file; plain chat still responds (8.1).
- Given the exported Epic 2 database schema with file/log, package-only and plain chats, when a probe fails or is interrupted and adoption resumes, then pending work stays unavailable, both eligible chats are adopted once without file copies, and existing events and plain chat survive (8.2).
- Given valid persisted artifacts, when the harness opens text and HTML and tries a wrong conversation, an out-of-scope path and a deleting project, then valid bytes are readable offline, script behavior is opt-in, and invalid targets reveal nothing (8.3).
- Given an overwrite awaiting approval, when project instructions change, then the captured request on resume uses the old instructions and the next task uses the new ones, without sibling context; the over-limit value is rejected without overwriting the saved one (8.4).
- Given unfinished work followed by an idle chat, when deletion is requested, then pending deletion is denied, and idle deletion removes the chat/log while the surviving member can still use the project's data (8.5).
- Given a scoped deletion that stops after a durable marker, when persisted state is reopened and deletion resumes, then new work/preview is denied during deletion and only the target project's records and files are removed (8.6).
- Given checks start from one tap, then all regression groups report independently (8.7), and the integrated upgrade/tracer result is shown separately from the unverified official signed upgrade (8.8).

## Implementation Notes

- `projectsCheck` has its own application ID (`dev.lusalhi.lumynoid.projects.checks`) and launcher. AGP's variant source API stages the rootfs and exact exported v4 schema only for this variant; the merged manifest exposes only the diagnostic launcher.
- `LumynoidData` can close its Room database so disposable fixtures can reopen persisted state and remove their own files. Normal app wiring still retains its process-owned database.
- Baseline on a fresh Redroid arm64 API 34: existing `ProjectChatSubmissionTest`, `PreviewNavigationTest`, and `ProjectDeletionTest` passed (3/3). The device is isolated from the existing emulator and runs no real provider.
- The diagnostic uses real Room, TaskRunner, coding tools, the preview resolver and WebView. Fixture replies and runtime transfer never access a provider or the network. Each case has separate storage, closed before cleanup.

## Plan Change Log

## Review Triage Log

- Inline quick review: fixed the scripted provider to count tool rounds only after the current user turn; production history includes completed earlier tool rounds. Both caller and verifier now use the same preview resolver. The rootfs response streams packaged bytes instead of allocating the archive, and cancellation cannot skip fixture runner/WebView cleanup.
- The first compile exposed a missing approval helper and nullable JSON access. Fixed the helper to wait for the committed approval in the runner's pending state before resolving it, and corrected both reads.

## Verification

- `./gradlew --offline :app:assembleProjectsCheck` — inspect package ID and arm64 APK, then install only the diagnostic on the available Android target.
- Launch from its launcher, tap once and observe the per-story report; record device/runtime limits and APK checksum.
- Inspect normal debug and release APK listings and DEX: no diagnostic launcher, exported v4 schema or embedded rootfs fixture.

**Observed:** Gradle assembled the standalone arm64 APK. `aapt2`, `apksigner` and `zipalign` confirmed the diagnostic package and launcher, debug signature and alignment. The packaged v4 schema matched the Room export byte-for-byte; the packaged rootfs matched its pinned SHA-256.

The [PR #8 debug APK](https://github.com/lusalhi/lumynoid/actions/runs/45109138343) was independently inspected: no diagnostic activity, schema or rootfs fixture. The official signed release APK will be inspected after publication.

On isolated Redroid arm64 API 34, one tap produced **6 passed, 0 failed**. A second tap in the same installation started a fresh run and again produced **6 passed, 0 failed**. The report showed 8.7 and fixture 8.8 as passed; official signed 8.8 remained **Perlu uji manual**.

Local artifact: `build/fixtures/lumynoid-projects-test-v0.4.0-arm64.apk` (69,830,860 bytes). SHA-256: `32f82e329089675e65ac384619dfed47ee58644cabfb8eb00167de2a05163713`. No remote operations or signed upgrade were performed.

**Physical handset (owner report):** All six automated scenarios passed in the standalone Projects Test APK. Model and Android version were not provided. This is diagnostic evidence, not an in-place upgrade of the official signed APK.
