---
title: 'PRoot menjalankan operasi bawaan dan berhenti bersama tugas'
type: feature
ticket: 5
created: '2026-11-21'
status: done
baseline_revision: 2e211031a780530dd15c466c2f2826b0abc18538
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

Supply the app-controlled Linux runtime for coding tools. Build Android arm64 PRoot from pinned sources, install a versioned Alpine/Node/npm rootfs after approval, and stop its processes with the current task slice.

## Boundaries & Constraints

Use the existing TaskRunner and per-call approval. PRoot and its loader execute from the installed native library directory. App-owned code selects operations, arguments, environment and mounts. The active workspace comes from ToolContext. Story 2.7 supplies the coding tools and 2.8 supplies chat cards and stable conversation workspaces.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| First install | Rootfs missing and approval requested | DENY leaves source unopened; APPROVE downloads and validates | Bad checksum or cancelled install leaves no installed rootfs |
| Run | Valid rootfs, workspace and built-in operation | Report pinned Node/npm versions and read workspace through PRoot | Unknown operations and invalid workspaces fail before spawn |
| Handoff | Fixture forks descendants, then exits, fails, times out, is cancelled or waits for approval | Cleanup completes before the task slice is handed off | Deadline, user cancellation and normal exits stay distinct |

</frozen-after-approval>

## Code Map

- `core/.../TaskRunner.kt` releases ToolSessionRelease under NonCancellable before publishing approval and before releasing its execution slot. `Tool.kt` supplies ToolContext and deterministic tool risk.
- `app/.../LumynoidApp.kt` owns process-scope dependencies. `AgentService.kt` observes the runner's busy state.
- `tools/userland-spike/` records the tested PRoot build recipe. The 2.1 plan records Alpine 3.22.8, Node 22.23.5 and npm 11.6.4 passing on Samsung API 37.
- `.github/workflows/ci.yml` and `release.yml` build and check the app. The tag workflow can publish the pinned rootfs with the signed APK.

## Tasks & Acceptance

- [x] `tools/userland/`, `:userland/build.gradle.kts`, `.github/workflows/` — build pinned PRoot, loader and supervisor, verify 16 KiB alignment, build/version rootfs, embed expected metadata, and stage rootfs and provenance with the release artifacts.
- [x] `:userland/src/main/`, `app/.../LumynoidApp.kt` — add approved, atomic install and a typed operation adapter; wire process-scope release to TaskRunner.
- [x] `:userland/src/androidTest/` — drive production adapter and TaskRunner on arm64 with an instrumented install and process fixture.

**Acceptance Criteria**

- Given an arm64 APK from the build pipeline, when inspected, then packaged runtime binaries and their ELF segments pass 16 KiB checks.
- Given a clean device fixture, when the first install is denied, approved, or served altered bytes, then installation and execution follow the matrix.
- Given an active process tree and an approval or terminal event, when the next task starts, then processes from the prior slice are gone.

## Design Notes

- Use the tag release asset for the rootfs. CI and release stage the same archive whose digest and size are embedded in the APK. The instrumented fixture may use a local archive stream and altered bytes. Implement in this plan's worktree under /tmp, with the original dirty checkout left intact.
- Use NDK 28.2.13676358, PRoot 52d65a4dc140ca554d241a1438ddd4f6b00c9813 and talloc 2.5.0. Use extracted JNI libraries and check ELF LOAD alignment independently of zipalign. PRoot ignores SIGTERM. Use a native supervisor with a control pipe, dedicated process group and subreaping. Consider PTRACE_O_EXITKILL before resuming the initial tracee.
- Keep the setup tool's EGRESS risk stable across resumes. Validate archive digest before extraction, install atomically under a mutex and clean staging on failure or cancellation. Keep cancellation distinct from an operation deadline. Hold the supervisor's stdin open as a lifetime channel and await cleanup before returning. Validate workspace paths before mount.
- The SDK is at /opt/android-sdk. ADB sees Redroid arm64 API 34. Spike provenance and archive are under /home/ubuntu/lumynoid/build/diagnostics/proot-npm/ for investigation. A clean rebuild in `/tmp/lumynoid-rootfs-analysis.IPCgWm` matches all 31 Alpine package versions. Lock the hashes of `base/packages/*.apk` and use the matching `*.repo` names for download URLs. To assemble, run signed `apk add --no-network --no-scripts /packages/*.apk` inside the pinned minirootfs with native arm64 or static QEMU.

## Implementation Notes

- The app holds one process-scoped adapter and supplies its release hook to TaskRunner. The standalone arm64 fixture registers the typed setup and probe tools.
- The native supervisor uses a control pipe and reaps descendants, including the fixture's detached child. The pinned PRoot enables EXITKILL. The runtime uses fixed arguments, mounts and environment.
- The versioned rootfs SHA-256 is 333bcbdbadd2d370b9882eab96f34f24648c9c0e2d9a4d678d0cd9f6f2baf006 for 30,732,906 bytes. Native and QEMU assembly matched. Release staging includes corresponding source archives and provenance.

## Plan Change Log

- Resumed the Codex implementation in its original worktree. Kept the approved scope and the existing APK rootfs digest. Archived all 16 locked Alpine inputs (about 30 MiB) in the repository so CI and release no longer depend on the live package index retaining old versions.

## Review Triage Log

| Finding | Verdict / route | Evidence and change |
| --- | --- | --- |
| Process output capped at 16 KiB | Medium / patch | `UserlandProcesses` discarded bytes before `ToolOutcome` reached the event log, contrary to AD-13. It now drains the full UTF-8 stream; the fixture verifies a listing larger than 16 KiB in the recorded tool result. |
| One cleanup error skips later sessions | High / patch | The release loop stopped on the first exception, but TaskRunner still released its execution slot. The loop now closes all sessions before rethrowing the first error. The fixture starts two sessions and injects a cleanup failure into the first. |
| Rootfs bytes depend on build umask | Medium / patch | Extraction and workspace creation affected archived modes. The builder fixes the umask and extraction policy without changing the pinned digest. |
| Alpine package revisions can disappear | High / patch | Both workflows used the live branch URLs, with no local bytes. The rootfs builder now assembles offline from the archived inputs and still checks every member against the lock. |
| Native build hard-codes the Linux NDK | Medium / patch | Gradle invokes the builder for every app variant. The builder selects the macOS NDK, GNU Make, and the NDK ELF reader on Darwin; the Darwin path still needs a macOS run. |

## Verification

- Run the full JVM suite, assemble app and instrumented APKs, and verify the application APK with zipalign and apksigner.
- Run the arm64 instrumentation suite on the available emulator and on an API 37 handset if connected. Record both environments explicitly.

- The full Gradle JVM/build/dependency run passed. Gradle reported 160 passes and four existing Markdown assumption skips. Dependency policy checked 181 modules.
- The app debug APK passed apksigner, 16 KiB zipalign and the ELF verifier. The staged archive and instrumentation asset matched embedded metadata byte for byte. MainActivity opened after an adb APK upgrade.
- Redroid arm64 API 34, 4 KiB pages, SELinux and seccomp disabled. The Gradle-built instrumentation APK targets API 37 and reported four passing tests in 9.243 seconds.
- GitHub publication was outside this build.
- OMP review fixes: debug and instrumentation APKs built successfully. `apksigner`, 16 KiB `zipalign`, native ELF checks, and the APK/rootfs/fixture digest checks passed. Redroid arm64 API 34: all five instrumentation tests passed in 9.609 seconds.
- Offline rootfs builds as a normal user with umasks `0022` and `0077`, and as root, produced byte-identical archives with the embedded digest.
- No macOS host was available to test the native build.
- Added the standalone **Lumynoid Test** launcher for phone-only acceptance. Both entry points run the same checks: Redroid arm64 API 34 reported 5/5 through the launcher and `OK (5 tests)` through instrumentation. On Redroid, the launcher opened the OS uninstall dialog for the old fixture package and confirmed it was gone afterward.
- Physical-device acceptance: the user ran **Lumynoid Test** on a handset running Android API 37 and reported passes for all five checks.
