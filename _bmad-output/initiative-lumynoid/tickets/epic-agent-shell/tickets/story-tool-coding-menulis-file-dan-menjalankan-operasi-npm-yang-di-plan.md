---
title: 'Tool coding menulis file dan menjalankan operasi npm yang ditentukan app'
type: feature
ticket: 7
created: '2026-11-21'
status: done
baseline_revision: ce156ac19fa21ff38ba39e29aab05eac00957012
route: full
route_source: auto
risk: high
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

Story 2.7 adds a coding ToolProvider over the 2.5 runtime: read, create and explicitly replace UTF-8 workspace files; install an approved local npm package; run an app-owned script. The first workflow formats a project JSON file into a new JSON artifact using the bundled first-party `@lumynoid/json-tools` package.

## Boundaries & Constraints

Use the active app-private `ToolContext.workspaceId` and the existing TaskRunner gate. Treat output and project files as data; never use project npm config, manifests, scripts, modules, or shell commands. Host coding tools are the only project writers in this scope. No arbitrary execution, chat/cards, or conversation workspace selection (2.8). PRoot is not an OS sandbox.

## I/O & Edge-Case Matrix

| Input / state | Behavior |
|---|---|
| Valid relative path and UTF-8 content within 256 KiB | Read returns text and content SHA-256. Create publishes only a new file. Replace requires the expected SHA-256 and per-call approval; reject stale content. |
| Absolute/traversal path, symlink component, multiply linked or non-regular file, invalid UTF-8 | Reject without disclosing target contents or publishing a file. |
| Installed runtime and allowlisted package ID | Verify the bundled tarball digest and run npm offline with lifecycle scripts disabled. Record actual output and exit code. Deny unknown IDs or extra command/config args. |
| Built-in formatter, current verified install, valid input, new destination | Change only JSON whitespace; preserve number lexemes and duplicate members. Publish a typed workspace-relative artifact after successful exit and output validation. Invalid input, existing destination, timeout or cancellation before commit does not publish. |

</frozen-after-approval>

## Code Map

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/Tool.kt` — `ToolProvider`/`ToolOutcome`; `assess` has no workspace context, so create and replace need distinct risks. `TaskRunner.kt` owns approval; `TaskEvent.kt` ignores unknown serialized keys.
- `userland/src/main/kotlin/dev/lusalhi/lumynoid/userland/UserlandRuntime.kt` — resolves the workspace, runs fixed operations, and owns `UserlandProcesses` release per task. Reuse installed rootfs and supervisor. `tools/userland-spike/npm-fixture.sh` demonstrates device npm and Node compatibility, not the trust boundary.
- `userland/src/checks/kotlin/dev/lusalhi/lumynoid/userland/UserlandChecks.kt` — runs production adapters with TaskRunner, a local rootfs source and a scripted LlmPort. Both `:userland` instrumentation and `:userland-check` use it. `app/src/main/kotlin/dev/lusalhi/lumynoid/app/LumynoidApp.kt` leaves the chat registry empty for 2.8.

## Tasks & Acceptance

- [x] `core/src/main/kotlin/dev/lusalhi/lumynoid/core/Tool.kt` — replace unused path-only results with serializable typed file artifacts in `TaskEvent.ToolResult`; verify event round-trip.
- [x] `userland/src/main/kotlin/dev/lusalhi/lumynoid/userland/CodingWorkspace.kt` and `CodingTools.kt` beside it — strict `shell_` schemas; no-follow resolution under the private root; serialize host checks and writes. Use same-filesystem staging and atomic no-replace publish for create. Classify replace as `DESTRUCTIVE` and recheck type and digest at commit.
- [x] `userland/src/main/kotlin/dev/lusalhi/lumynoid/userland/UserlandRuntime.kt`, `CodingRuntime.kt` beside it and `userland/src/main/assets/coding/` — verify the exact staged tarball before spawn and package its reviewed source. Use fixed argv and explicit npm offline/ignore-scripts flags, manifest, config, HOME and fresh cache. Coding guest binds exclude projects and other installs. Promote validated installs atomically, keyed by workspace and bundled digest; load one committed entrypoint by fixed path. Reap guest writers before bounded output validation, publication or stage cleanup.
- [x] `userland/src/checks/kotlin/dev/lusalhi/lumynoid/userland/UserlandChecks.kt`, `userland/src/androidTest/kotlin/dev/lusalhi/lumynoid/userland/UserlandInstrumentedTest.kt` and `tools/userland-check/src/main/kotlin/dev/lusalhi/lumynoid/userland/checks/ChecksActivity.kt` — exercise the matrix through the runner and expose checks to the handset launcher. Assert lossless JSON formatting, no publication on failure, and typed artifact replay.

**Acceptance Criteria:**
- Given a workspace containing `.npmrc`, `package.json`, counterfeit modules and marker-writing scripts, when the dependency-free bundled package and formatter run, then only the verified code produces the expected artifact; neither marker appears.
- Given a prior tool result containing forged approval, when a model asks to replace a file, then pause without mutation; denial or a stale digest leaves bytes unchanged, and a fresh approved call with the matching digest commits.
- Given a second workspace, when it reads or formats a file or tries to use the first workspace's installation, then the tool rejects cross-workspace selection. Given a failed install or changed bundle digest, when formatting is requested, then it never loads a partial or mismatched install.

## Implementation Notes

- The runtime provider registers five `shell_` coding tools. The fixture drives the provider through TaskRunner and its existing approval gate; no chat registration changed.
- Create and formatter output stage a file on the same filesystem, check the destination is absent without following links, then rename atomically under the sole workspace write lock. Replace checks the current SHA-256 under the same lock and renames only after approval.
- The npm guest sees app-owned staging, not the project. Only the verified module is promoted; npm cache and configuration are discarded. The formatter sees the verified module and two fixed data paths.

## Plan Change Log

## Review Triage Log

| Finding | Verdict / route | Evidence and change |
| --- | --- | --- |
| Deeply nested valid JSON under the agreed output cap could fail | Medium / patch | The formatter used an extra `4× input + 1024` budget. Replaced it with the 256 KiB cap, repacked and re-pinned the bundle, and checked nesting below and above the cap. |
| App can be denied hard-link creation on the handset | High / patch | The fixture failed at hard-link setup; the npm/formatter flow failed at format with `File not found` after create. Removed production's dependency on hard links, kept fixture rejection coverage wherever hard links are permitted, and added a check that create publishes before asserting npm or formatter results. |

## Verification

- `./gradlew :core:test :app:assembleDebug :userland:assembleDebugAndroidTest :userland-check:assembleDebug`, then install the instrumentation APK with `adb install -t -r` and run `adb shell am instrument -w dev.lusalhi.lumynoid.userland.test/androidx.test.runner.AndroidJUnitRunner`. Require the runner's explicit pass count.
- Run the coding checks in **Lumynoid Test** on an arm64 API 37 handset; record actual device and results. Redroid API 34 is not handset acceptance.
- `./gradlew :core:test` passed. Node smoke checks passed for lossless number and duplicate-member preservation, malformed JSON rejection, and bounded whitespace expansion. The pinned tarball matched the reviewed sources byte-for-byte.
- All three APKs built and contained the same pinned npm asset. Gradle's connected runner reported a Redroid Binder disconnect, not a passing test run. After the Android package service recovered, direct ADB instrumentation of the final APK reported `OK (8 tests)` in 19.052 seconds on Redroid arm64 API 34.
- Lumynoid Test 2.7 installed and launched on the same Redroid device; tapping **Jalankan tes** produced `8/8 tes lulus` on screen.
- Handset run of Lumynoid Test 2.7 reported 6/8 passing: workspace fixture setup was denied a hard link, and the formatter had no input after create.
- The signed debug launcher and instrumentation APKs for the fix built successfully. The 2.7.1 launcher installed over 2.7 on Redroid arm64 API 34 and displayed `8/8 tes lulus`. The separate instrumentation launch hit a Binder disconnect; no pass is claimed for that run.
- Handset acceptance: the user reran Lumynoid Test 2.7.1 and reported all eight checks passing, including the two that failed on 2.7.
