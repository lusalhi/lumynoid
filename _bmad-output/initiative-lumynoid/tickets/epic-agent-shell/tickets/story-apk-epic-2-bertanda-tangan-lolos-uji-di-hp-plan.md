---
title: 'APK Epic 2 bertanda tangan lolos uji di HP'
type: chore
ticket: '12'
created: '2026-11-22'
status: built
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
- [ ] With owner go-ahead, push, open/merge the release PR to `main`, and check/tag the merged revision. Push `v0.3.0`; owner approves the GitHub `release` environment. Download the official assets and verify checksums, certificate, package/version, ABI, 16 KiB alignment and matching rootfs. Check the native archives include the PRoot and talloc notices, the release assets include the patch and provenance, and the tag's source tree supplies `tools/userland/build-native.sh`, `tools/userland/supervisor.c`, and `tools/userland-spike/talloc-cross-answers.txt`.
- [ ] On arm64 API 37, keep the installed `v0.2.1` and a saved chat. Install the verified `v0.3.0` APK over it without uninstalling or clearing data. Confirm old chat history, provider configuration, and a new streaming plain reply.
- [ ] Approve the initial runtime download. Start coding work, verify it is still running, then turn off the screen and verify it finishes before turning the screen back on. Check workspace write/read, bundled offline npm and the built-in JSON formatter in activity cards and Log; read the same file in a later turn. Verify no runtime children remain after success, failure, cancellation, timeout, or while awaiting approval.
- [ ] Check deny and approve on a file replacement; reject paths outside the workspace, arbitrary commands, and packages outside the allowlist. Verify project config/script fixtures do not change the operation and tool output containing a fake approval does not bypass the app's approval card. Separately, render an assistant Markdown reply that imitates a card and confirm no activity or approval card or approval controls appear. Confirm the log persists after relaunch; open text/HTML artifacts offline.
- [ ] Record the existing TaskRunner fixture results for malformed tool calls and call budget in the release verification, and record every handset check with device/API, build, pass/fail, and evidence. Keep the ticket open until the owner confirms the signed-device run.

Handset fixture: in a new coding chat, ask the model to create `acceptance-input.json` with `{"release":"epic-2","ok":true}`, install `@lumynoid/json-tools` offline, and format it as `acceptance-output.json`. Read the output in a later turn. Use only these fixture files for overwrite checks; keep the pre-upgrade chat untouched.

## Verification

- `python3 .github/scripts/check_release.py --self-test`
- `./gradlew :app:assembleRelease :userland:buildUserlandRootfs`; inspect the candidate with SDK `aapt2` and `zipalign -c -P 16 4`, `tools/userland/verify-apk.py`, and the staged native sources/notices.
- After merge, create the local `v0.3.0` tag on the merged revision, then run `python3 .github/scripts/check_release.py tag --tag v0.3.0 --main origin/main` before pushing the tag. After CI publishes, verify the downloaded assets using `SHA256SUMS`, `apksigner`, `aapt2`, `zipalign`, and `tools/userland/verify-apk.py`.

Local evidence: release checker self-test passed all 19 cases. The staged PRoot and talloc archives contain `COPYING` and `LICENSE`, respectively; the matching PRoot patch and native provenance are staged beside them.

The initial `assembleRelease` attempts reached the tool deadline. `./gradlew --offline --max-workers=2 :app:packageRelease :userland:buildUserlandRootfs` completed its packaging and lint-vital tasks. `aapt2` reported non-debuggable `dev.lusalhi.lumynoid` `v0.3.0` (code 4), target SDK 37, and only `arm64-v8a`. `zipalign -c -P 16 4` passed; all three runtime ELFs passed their segment checks and the staged rootfs matched the APK's digest and size. Unsigned APK SHA-256: `a58b71a3f884d0020bc3df92a69f60ba414534c382de68502d9e122be37170aa`.

No remote write, signing, or handset acceptance was performed. Continue with the release PR, then the owner-gated tag workflow and in-place upgrade test.

Release recovery: `v0.3.0` was tagged on `main`, but [GitHub Actions failed before signing](https://github.com/lusalhi/lumynoid/actions/runs/44993863107): the runner could not find `sdkmanager`. CI and both Android release jobs now provision the pinned SDK packages explicitly; rootfs jobs use Ubuntu 24.04 for `qemu-aarch64-static`. The original tag is unchanged. Use replacement tag `v0.3.1` (code 5) for the remaining release and handset checklist, still upgrading from `v0.2.1`.
