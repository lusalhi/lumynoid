---
title: 'Refactor sweep'
type: refactor
ticket: '11'
created: '2026-11-21'
status: done
baseline_revision: 'acde6a60c30f63a98209c7de6090ca7a74bb39ae'
route: oneshot
route_source: auto
risk: low
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 2 introduced duplicate strict JSON string extraction for streamed tool arguments (2.3/2.6) and duplicate labels for resolved approvals in Chat and Log (2.8/2.9).

**Approach:** Share those two rules at their existing layer boundaries. Keep provider-specific null and completion handling, screen-specific pending and inactive labels, and all task, storage, and release behavior unchanged.

</frozen-after-approval>

## Implementation Notes

One-shot: small private or module-internal consolidations covered by the existing Epic 2 tests. No data migration or public API change.

- `:llm` now extracts strict string fragments in `Wire.kt`. OpenAI-compatible streams still accept missing or JSON-null fragments; Anthropic still rejects missing and non-string fragments in a required delta.
- Chat and Log now use one `ApprovalResolution` → string-resource mapping in `ToolCards.kt`. Chat retains its live deadline handling; Log retains its task-end handling. Neither approval policy nor stored events changed.

## Plan Change Log

## Review Triage Log

- Quick review: no findings.

## Verification

- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:checkDependencyPolicy :app:assembleDebug :userland:assembleDebugAndroidTest :userland:buildUserlandRootfs` — existing regression suite and Android build gates pass.
- Run the CI core runtime dependency check, dependency-policy self-test, APK alignment/signature checks, and staged rootfs verifier locally using the same CI inputs. Pull-request-only schema-claim job is not part of this change; no Room schema is edited.

- Local CI build gates passed. JVM reports: 185 passed, four existing skips, no failures; the dependency policy covered 182 modules. APK alignment, debug signature, native segment alignment, and the packaged/staged rootfs identity all passed.
- On a disposable arm64 Redroid emulator (Android 14/API 34), a temporary instrumentation fixture rendered a persisted declined request in Chat and confirmed `Approval: Declined` in Log details, with no tool execution. The temporary test source was removed after the check.
- The same emulator passed all 12 existing userland instrumentation tests. This is debug/emulator evidence; signed APK handset acceptance remains Story 2.12.
