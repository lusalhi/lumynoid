---
title: 'Crashes are recorded locally and the user can export them'
type: feature
ticket: '10'
created: '2026-11-20'
status: done
baseline_revision: 'b14467d530a3e3f9770d811aa00c20803376d124'
route: full
route_source: auto
risk: low
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** When the app crashes on a phone, nothing is kept, so the owner cannot learn why. AD-25 requires a local, offline crash record that cannot leak the API key or prompts, and that the user can hand over.

**Approach:** An uncaught-exception handler writes a sanitized text report under app-private storage, then lets the normal crash proceed. On the next launch a banner above whichever screen is open (chat, provider setup, conversations) offers to share the newest report through the system share sheet, via the single `FileProvider` the conventions define. A debug-only entry point triggers a crash for testing.

## Boundaries & Constraints

**Always:** The handler uses no network and no new dependency, never throws, and always chains to the previous handler (AD-17, AD-25). Reports hold only app and Android version, device model, time, thread name, and for each throwable its class name and stack frames: no exception messages, no `toString()`, no suppressed exceptions, so no key or prompt text can enter (AD-15). Reports live in `filesDir/crash/` (already excluded from backup); export copies one into `cacheDir/export/`. The `FileProvider` is declared once in `:app`, authority `${applicationId}.files`, `exported=false`, one path `cache/export/` only (Consistency Conventions). The release manifest gains no permission (AD-24). UI strings go in `strings.xml`, in English. Pure logic stays free of `android.*` so it runs in JVM tests.

**Never:** No crash reporter library, no upload, no analytics, no automatic sharing. No crash-trigger code or manifest entry in the release build. No change to the Room schema, `:core`, `:llm` or `:data`. Do not add the AD-22 interrupted-task sweep (separate work).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Crash | Uncaught exception whose message and cause message contain an API key and prompt text | One report file written; it contains class names and frames, and neither string | Write failure is swallowed; previous handler still runs |
| Next launch | A report exists, any screen open (including provider setup before any provider is saved) | A banner with Share and Dismiss shows above that screen | No report: no banner |
| Share | User taps Share | Newest report is copied to `cacheDir/export/`, share sheet opens with a `content://` URI granted read-only | Copy failure: banner stays, no crash |
| Dismiss | User taps Dismiss | All stored reports are deleted, banner disappears | N/A |
| Many crashes | More than 5 reports | Only the newest 5 are kept | N/A |
| Huge trace | Very deep or long cause chain | Report is cut at 100 frames per throwable and 5 causes, with a truncation line | N/A |

**Decision (human, 2026-11-20, from review):** the banner shows on every screen, not only chat, because a user who crashes during first-run setup cannot reach chat.

</frozen-after-approval>

## Code Map

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/LumynoidApp.kt` -- `Application`; install the handler in a new `onCreate`, before the lazy `graph`.
- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/MainActivity.kt` -- `Root` picks a screen from `ChatViewModel` state; read pending reports here and render one banner above every screen's content (`Screen.PROVIDER`, `CHAT`, `CONVERSATIONS`; the loading spinner may skip it).
- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/Screens.kt`, `ProviderScreens.kt`, `ConversationScreens.kt` -- each screen applies its own `statusBarsPadding`/`navigationBarsPadding`; the banner must not double or lose the insets. A shared `CrashBanner` composable (Material 3 `Card`) lives with the code that uses it.
- `app/src/main/AndroidManifest.xml` -- add the `<provider>`; existing `${applicationId}`-style identity, no permission changes.
- `app/src/main/res/xml/data_extraction_rules.xml` -- already excludes `file` and `external` domains; do not edit.
- `app/src/main/res/values/strings.xml` -- add banner and share strings.
- `app/src/test/kotlin/dev/lusalhi/lumynoid/app/LinkPolicyTest.kt` -- style to follow for pure JVM JUnit4 tests (`libs.junit`, no Robolectric).
- `.github/workflows/ci.yml` -- runs `:app:assembleDebug`, so the debug source set is compiled in CI; the release workflow builds `assembleRelease`, which must not see it.
- New: `CrashReport.kt` (pure: formatter, `CrashStore`, export copy), `CrashHandler.kt` (Android: install, `Build` info, share intent), `res/xml/file_paths.xml`, `app/src/debug/AndroidManifest.xml` and `app/src/debug/kotlin/.../CrashTestActivity.kt`.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/kotlin/dev/lusalhi/lumynoid/app/CrashReport.kt` -- formatter and `CrashStore(dir)` with `write`, `newest`, `clear`, retention of 5, and `copyForExport(report, exportDir)` that first empties `exportDir` -- all behavior testable on the JVM
- [x] `app/src/main/kotlin/dev/lusalhi/lumynoid/app/CrashHandler.kt` -- install the default handler (write inside try/catch, then call the previous handler) and build the `ACTION_SEND` chooser with `FLAG_GRANT_READ_URI_PERMISSION` -- Android edge kept thin
- [x] `LumynoidApp.kt`, `MainActivity.kt`, `Screens.kt`, `strings.xml` -- install on start, show the banner above every screen, wire Share and Dismiss -- user-facing export
- [x] `AndroidManifest.xml`, `res/xml/file_paths.xml` -- one `FileProvider` with a single `cache-path` named `export`, path `export/` -- convention
- [x] `app/src/debug/...` -- debug manifest adds an exported launcher activity labelled for crash testing that throws in `onCreate` -- lets the tester trigger a crash without adb
- [x] `app/src/test/kotlin/dev/lusalhi/lumynoid/app/CrashReportTest.kt` -- cover every I/O row that runs on the JVM, including copy failure (the UI-only parts, banner, chooser, grant and Toast, need Compose or instrumented tests the repo lacks; see Review Triage row 9), plus a check that `file_paths.xml` has exactly one `cache-path` `export/` and the main manifest declares the provider once with authority `${applicationId}.files` -- proves hygiene and the convention

**Acceptance Criteria:**
- Given a throwable with a key and prompt text in its message and in its cause's message, when the report is formatted, then neither string appears and the throwable class names and frames do.
- Given a crash triggered in a debug build before any provider is saved, when the app is reopened on the provider screen, then the banner is visible there.
- Given a crash triggered in a debug build, when the app is reopened and the user taps Share, then the share sheet offers the report as a text file, and the file contains no key or prompt text.
- Given the release APK, when its manifest is dumped, then it has no new permission and no crash-test activity.
- Given the new crash code, when its imports are listed, then none come from `io.ktor` or `java.net`.

## Implementation Notes

Built by a Sonnet subagent; verified again by the main session (all Verification commands pass: 16/16 `CrashReportTest`, full build, no `io.ktor`/`java.net` in `Crash*.kt`, 0 `CrashTest` matches in the release manifest).

Additions beyond the plan's file list, each forced by testability or safety:
- `crashRecordingHandler(store, env, now, previous)` lives in `CrashReport.kt` (pure) so "never throws, always chains" is tested on the JVM; `CrashHandler.kt` only wires Android values into it.
- File names are `crash-<millis>-<seq>.txt` so two crashes in the same millisecond do not overwrite each other.
- Superseded in story 1.12 (2026-11-20): file names are now `crash-<millis>.txt`, where the stamp is raised above the newest existing report's, so names keep write order, same-millisecond crashes never collide, and `newest()` stays the last write after a clock rollback. The sequence number is gone; `write` is synchronized.
- `app/src/debug/res/values/strings.xml` holds the debug-only launcher label, so the release build carries no trace of it.
- A failed Share shows a short Toast (`crash_share_failed`) and leaves the banner.

Re-derived after review pass 1 (banner on every screen): `Root` now renders `CrashBanner` above every screen, including provider setup and the loading spinner, and the screen below consumes the status-bar inset while the banner shows. The crash code is now behind a `CrashHandler` object; report names use `Locale.ROOT`; 17 `CrashReportTest` cases (one added for name sort order). Main-session verification re-run: all Verification commands pass, 0 `CrashTest` in the release manifest.

Not done or not covered:
- Not run on a device: crash-test icon, banner layout and insets, share sheet, and whether the chooser's read grant works. No device on this host.
- The banner, Share and Dismiss wiring in `Root` has no automated test (no Compose or Robolectric setup). Matrix rows "Next launch", "Share" and "Dismiss" are covered through `CrashStore` (`newest`, `copyForExport`, `clear`) only.
- The banner reads the crash folder on the main thread once when `Root` first composes (one directory listing).

## Plan Change Log

- 2026-11-20, review pass 1, intent_gap #1: the frozen intent put the banner on the chat screen only, but a user with no provider set up cannot leave the provider screen. Amended with the human's decision (banner on every screen). Known-bad state avoided: a first-run crash whose report cannot be shared. Code was reverted and re-derived. KEEP: `crashRecordingHandler(store, env, now, previous)` lives in `CrashReport.kt` and stays pure; report file names are `crash-<millis>-<seq>.txt` formatted with `Locale.ROOT` (locale digits broke sorting); a failed Share shows a short Toast and leaves the banner; the debug-only label string lives in `app/src/debug/res/values/strings.xml`; the 16 `CrashReportTest` cases (content, truncation, cause cycle, retention, same-millisecond names, clear, export, handler swallow-and-chain, `file_paths.xml` and manifest hygiene) stay as written; Share copies into `cacheDir/export` and builds the chooser with both `ClipData` and `FLAG_GRANT_READ_URI_PERMISSION`.

Done 2026-11-20: marked done with the epic release. The debug-build phone check ("Crash test" launcher icon, reopen, Share) was never run; the owner accepted that as a debt outside story 1.12, since release builds have no crash trigger.

## Review Triage Log

Pass 1, quick lens (omp, openai-codex/gpt-6.2-sol, high; findings in `story-crash-tercatat-lokal-dan-bisa-diekspor-pengguna-review.md`). 9 findings: high 0, medium 1, low 5, false 3, maybe-false 0. One intent_gap; the rest are rejected, except one patch that waits behind the intent_gap.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Crash on a fresh install cannot be exported on relaunch | medium | intent_gap | Resolved by the human (banner on every screen); code re-derived. Real: `ChatViewModel.init` sends an unconfigured app to `Screen.PROVIDER` and only `closeProvider()` with an active model returns to chat; only `ChatScreen` shows the banner. A crash during first-run setup, likely the most useful report, is not offerable until a provider works. The frozen intent says "the chat screen", so showing it elsewhere needs the human. |
| 2 | Thread name and frame file names written verbatim may carry secrets | false | reject | Both come from code and the JVM, not from user data; only the test builds synthetic frames. No path from a key or prompt reaches them. |
| 3 | Release logcat still shows the exception message via the platform's pre-handler | false | reject | The platform logs the crash before any app handler; the app adds no logging and this change adds none. Not caused or worsened by the change. |
| 4 | Base `androidx.core.content.FileProvider` may crash startup | medium | patch (story 1.12) | Wrongly rejected here. AndroidX says declaring the base class directly "is not reliable and will causes crashes on some devices"; the `v0.2.0` pre-release crashed on the owner's phone. Fixed in story 1.12 with the `LumynoidFileProvider` subclass, and `CrashReportTest` now asserts it. |
| 5 | Same-millisecond filename race; partial file after a failed write | low | reject, then patch (story 1.12) | The same-process sequence number already prevents the race. The partial-file half was rejected here and fixed in story 1.12: `CrashStore.write` writes a `.part` file and renames it into place. |
| 6 | `"%015d".format` is locale-sensitive; names sort as strings | low | patch | Real: Arabic-Indic digits sort differently, so Share could pick an older report and retention delete the newest. One-line fix: format with `Locale.ROOT`. Folded into the re-derivation (KEEP list in the Plan Change Log). |
| 7 | `File.delete()` failures ignored | low | reject | Needs a filesystem fault on app-private files; fix adds branches and a result type. |
| 8 | Throwing previous handler escapes the `try` | low | reject | The previous handler is the platform's, which does not throw; swallowing its exception would hide a real crash. |
| 9 | Tests never force a copy failure or exercise banner, chooser, grant or Dismiss | low | reject | True and already recorded in Implementation Notes; closing it needs Compose or instrumented test infrastructure the repo does not have. |

## Design Notes

Dropping exception messages entirely is deliberate: a regex cannot reliably remove prompt text, so the report keeps only what code, not data, produces. The cost is less detail than a message would give; frames still locate the fault.

The debug entry is a second launcher icon in debug builds only, so a tester needs no adb. Real-phone checks use the release APK (AD-25), which has no trigger, so story 12 can only confirm the banner after a real crash.

## Verification

**Commands:**
- `./gradlew :app:testDebugUnitTest` -- expected: all tests pass, including `CrashReportTest`
- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:assembleRelease` -- expected: BUILD SUCCESSFUL
- `grep -rnE 'io\.ktor|java\.net' app/src/main/kotlin/dev/lusalhi/lumynoid/app/Crash*.kt` -- expected: no output
- `m=$("$ANDROID_HOME/build-tools/37.0.0/aapt2" dump xmltree app/build/outputs/apk/release/*.apk --file AndroidManifest.xml) && ! grep -q CrashTest <<<"$m"` -- expected: exit 0 (aapt2 ran and nothing matched)

**Manual checks (if no CLI):**
- On a phone with the debug APK: open the crash-test icon, let the app close, open Lumynoid, tap Share, and send the report to a note or mail app; read it and confirm it has frames and no key or prompt. Not runnable on this host (no device).

### Pass 2 (after the loopback)

Quick lens again (omp, openai-codex/gpt-6.2-sol, high; findings in `story-crash-tercatat-lokal-dan-bisa-diekspor-pengguna-review-2.md`). 7 findings: high 0, medium 0, low 5, false 1, patch 1 of the low ones. No intent_gap or bad_plan; no further loopback. Verification re-run after the patch: 17 tests, 0 failed; build, grep and release-manifest checks pass.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Base `FileProvider` may crash startup | false | reject | `carried` from pass 1 #4: only a manifest-merge caveat; the merged release manifest holds one `FileProvider`. |
| 2 | Failed write can leave a truncated file offered as newest | low | reject | `carried` from pass 1 #5: needs a full disk; temp file plus rename adds complexity. |
| 3 | Clock set back can hide or delete the newest report | low | reject | Needs five existing reports with later timestamps (a clock moved back by more than the span of five crashes); the fix adds a second ordering key. |
| 4 | Dismiss ignores delete failures; export copy survives Dismiss | low | reject | Delete part `carried` from pass 1 #7. The leftover copy is the same sanitized report in app-private cache, replaced at the next share. |
| 5 | Banner can crowd out the screen with huge accessibility text in a short window | low | reject | Needs extreme font scale plus a small landscape window; fix adds scroll or height limits. The banner is dismissible. |
| 6 | Throwing previous handler escapes the `try` | low | reject | `carried` from pass 1 #8. |
| 7 | Tests miss some matrix rows; `grantUriPermissions` asserted with `assertNotNull` | low | patch | Gap on rows `carried` from pass 1 #9 (rejected). The weak assertion is real: DOM returns "" for a missing attribute. Patched to `assertEquals("true", ...)`. |
