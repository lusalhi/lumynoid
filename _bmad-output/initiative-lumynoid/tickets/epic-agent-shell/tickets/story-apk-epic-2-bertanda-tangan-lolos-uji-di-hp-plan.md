---
title: 'APK Epic 2 bertanda tangan lolos uji di HP'
type: chore
ticket: '12'
created: '2026-11-22'
status: done
baseline_revision: 'f17f3e744dd88b70719f2f5ac903fae605df3b45'
route: oneshot
route_source: auto
risk: high
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context:
  - '{project-root}/docs/release.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 2 has local and emulator evidence, but the latest signed APK on GitHub Releases is Epic 1 (`v0.2.1`). The upgrade and coding flow have not been accepted on the target handset with the production package and certificate.

**Approach:** Prepare `v0.3.0` (`versionCode` 4). Reuse the owner-gated tag workflow from 1.12 and verify the downloaded APK and its matching runtime/source assets. Upgrade the installed Epic 1 release in place on an arm64 API 37 handset and record the Epic 2 acceptance results. Keep app identity, key, and the restricted coding scope unchanged.

</frozen-after-approval>

## Implementation Notes

Oneshot: the code change is release metadata in `app/build.gradle.kts`. Signing, publishing, and device testing use the existing workflow and upgrade procedure. No signing secrets are needed locally. The current branch contains Epic 2; `main` is still at the `v0.2.1` baseline. As in 1.12, leave outward steps owner-gated; do not substitute a debug or unsigned APK for handset acceptance.

The retained TaskRunner report has 40 passing tests, including one audited repair before `MALFORMED_TOOL_CALL` and the 20-call limit before `BUDGET_EXHAUSTED`. The tag workflow reruns the JVM tests against the release revision.

## Plan Change Log

## Review Triage Log

- Quick review: closed the candidate-evidence finding by building and inspecting the new `v0.3.0` APK; see Verification.
- Patched the release checklist to run the tag checker with Python, verify tagged build inputs plus source notices, require in-flight screen-off work and all teardown paths, and distinguish forged assistant text from forged tool output.

## Release and device checklist

- [x] Bump app version; run the existing tag checker self-test and build/inspect the unsigned arm64 release candidate against its staged rootfs and native source assets.
- [x] With owner go-ahead, merge locally and push to `main`, tag `v0.3.1`, and obtain owner approval for the `release` environment. Verify the published checksums, certificate, package/version, ABI, alignment, rootfs, and corresponding native sources and notices.
- [x] Upgrade from `v0.2.1` to the signed `v0.3.1` APK on the Samsung API 37 handset without uninstalling. Old chat and a new streaming plain reply still work.
- [x] Approve the initial runtime download; run file creation, bundled offline npm install, and JSON formatting with the screen off. Check results in Log and read the output in a later turn.
- [x] Decline and then approve an overwrite of the test file, checking that the contents change only after approval. Reopen the app and verify Log and HTML preview offline.
- [x] On the handset, the diagnostic APK verifies no runtime children remain after success, failure, cancellation, timeout, or while awaiting approval.
- [x] On the handset, the diagnostic APK rejects out-of-scope workspace, command, and npm package requests, ignores project config/scripts, and requires a real approval despite forged tool output.
- [x] On signed `v0.3.1`, confirm assistant Markdown cannot create activity/approval cards, and a test file returned by a tool cannot authorize its own overwrite.
- [x] Record the existing TaskRunner fixture results for malformed tool calls and call budget; see Implementation Notes. The tagged workflow also passed its JVM test job.

Handset fixture: in a new coding chat, ask the model to create `acceptance-input.json` with `{"release":"epic-2","ok":true}`, install `@lumynoid/json-tools` offline, and format it as `acceptance-output.json`. Read the output in a later turn. Use only these fixture files for overwrite checks; keep the pre-upgrade chat untouched.

## Verification

- `python3 .github/scripts/check_release.py --self-test`
- `./gradlew :app:assembleRelease :userland:buildUserlandRootfs`; inspect the candidate with SDK `aapt2` and `zipalign -c -P 16 4`, `tools/userland/verify-apk.py`, and the staged native sources/notices.
- After merge, create the local `v0.3.0` tag on the merged revision, then run `python3 .github/scripts/check_release.py tag --tag v0.3.0 --main origin/main` before pushing the tag. After CI publishes, verify the downloaded assets using `SHA256SUMS`, `apksigner`, `aapt2`, `zipalign`, and `tools/userland/verify-apk.py`.

Local evidence: release checker self-test passed all 19 cases. The staged PRoot and talloc archives contain `COPYING` and `LICENSE`, respectively; the matching PRoot patch and native provenance are staged beside them.

The initial `assembleRelease` attempts reached the tool deadline. `./gradlew --offline --max-workers=2 :app:packageRelease :userland:buildUserlandRootfs` completed its packaging and lint-vital tasks. `aapt2` reported non-debuggable `dev.lusalhi.lumynoid` `v0.3.0` (code 4), target SDK 37, and only `arm64-v8a`. `zipalign -c -P 16 4` passed; all three runtime ELFs passed their segment checks and the staged rootfs matched the APK's digest and size. Unsigned APK SHA-256: `a58b71a3f884d0020bc3df92a69f60ba414534c382de68502d9e122be37170aa`.

Release recovery: `v0.3.0` was tagged on `main`, but [GitHub Actions failed before signing](https://github.com/lusalhi/lumynoid/actions/runs/44993863107): the runner could not find `sdkmanager`. CI and both Android release jobs now provision the pinned SDK packages explicitly; rootfs jobs use Ubuntu 24.04 for `qemu-aarch64-static`. The original tag is unchanged. The replacement tag `v0.3.1` (code 5) was used for release and handset acceptance, still upgrading from `v0.2.1`.

Published release: [GitHub Actions run 44994487467](https://github.com/lusalhi/lumynoid/actions/runs/44994487467) passed `verify`, `build`, and `publish`. All eight files listed in `SHA256SUMS` matched. Independently verified the registered certificate, package `dev.lusalhi.lumynoid`, `v0.3.1` (code 5), target API 37, arm64-only ABI, 16 KiB alignment, and the APK-pinned rootfs. The published native archives match their source hashes and contain their notices; the patch matches the tag, which contains the build script, supervisor source, and talloc cross-build answers. Signed APK SHA-256: `815c57331583028f860722e19a5ded6aed4da8d8b53a6f29385e6228ba39ba08`.

Handset evidence (owner report): Samsung, Android API 37. All five requested checks passed on the signed `v0.3.1` APK: upgrade preserving old chat and plain streaming, runtime setup, screen-off coding and reading the formatted output in a later turn, deny/approve, and persistent Log with offline HTML preview. Exact Samsung model was not provided.

Handset diagnostic evidence (owner report): Lumynoid Test `2.7.1`, debug APK SHA-256 `42c978d03d96d7dd67c1b02d8b7b5172941f2dc147a5b6b68b47798c247dd563`, passed 8/8 on the Samsung API 37 handset. The separate fixture covers runtime teardown, workspace and coding boundaries, and the current approval requirement with forged tool output. It does not exercise the signed app's Chat UI.

Final handset evidence (owner report): both signed `v0.3.1` Chat checks passed on the same Samsung API 37 handset. Assistant Markdown stayed text; forged approval in file output did not bypass the real overwrite card, and declining preserved the fixture file. Story 2.12 acceptance is complete.
