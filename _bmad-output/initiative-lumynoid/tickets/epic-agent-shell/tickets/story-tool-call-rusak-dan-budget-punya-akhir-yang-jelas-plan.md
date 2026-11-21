---
title: 'Tool call rusak dan budget punya akhir yang jelas'
type: feature
ticket: 6
created: '2026-11-21'
status: built
baseline_revision: 'd3a2edf4380c8a6993a4d54710d392ebe015aad4'
route: full
route_source: auto
risk: medium
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

Give malformed tool replies one chance to recover, then surface a specific failure. Cap model calls per task to protect the user's API quota and persist the retry trail for recovery and the later action log.

## Boundaries & Constraints

Use AD-26. The default cap is 20 and the user can save a positive integer in the app. Count each stream invocation, including the initial request and repair, against the limit captured at submission. The count and the one repair allowance follow the task through approval waits. Repair a malformed batch as a whole. The existing risk checks apply to any corrected calls. Quota retries belong to the provider router. Story 2.9 owns the action log UI.

## I/O & Edge-Case Matrix

| Scenario | Input | Expected behavior |
|---|---|---|
| Repeated rejection | Empty tool reply, then invalid arguments | Two `ToolCallRejected` events, one `LlmRetry`, zero tool executions, visible `MALFORMED_TOOL_CALL` |
| Valid repair | Malformed reply followed by valid calls or text | Retry uses the original valid history and one fixed instruction; corrected calls use normal validation and approval |
| Limit | Provider keeps returning tool calls | Stop before model invocation number limit + 1 with `BUDGET_EXHAUSTED`; a final text answer within the limit succeeds |
| Crash | Latest event is a rejection or retry | On restart record `INTERRUPTED` without replaying the model or tool |

</frozen-after-approval>

## Code Map

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt` handles provider failures, call preparation, and per-task continuations. Both adapters already emit `LlmError.MalformedToolCall`. Both serialize `Role.SYSTEM`, so the repair instruction can stay scoped to one request and preserve intact assistant/result pairs.
- `core/.../TaskEvent.kt` defines stable JSON and nonterminal recovery classification. `data/.../Repositories.kt` handles durable appends and reconstructs unfinished tasks from the last event. The current context assembler ignores audit events.
- `data/.../LumynoidData.kt` exposes persistence ports. `app/.../LumynoidApp.kt` constructs the process runner. The provider list in `ProviderScreens.kt` already serves as settings, and `Screens.kt` maps terminal chat errors.

## Tasks & Acceptance

- [x] `core/.../TaskRunner.kt`, `TaskEvent.kt`, `Ports.kt` — track task-local limit and repair state, validate the whole reply before processing tools, and persist typed audit events before the retry.
- [x] `data/.../Repositories.kt`, `LumynoidData.kt` — require durable writes for audit events and store the app-wide cap in private preferences.
- [x] `app/.../LumynoidApp.kt`, `ProvidersViewModel.kt`, `ProviderScreens.kt`, `Screens.kt`, `app/src/main/res/values/strings.xml` — wire the budget from saved settings, validate input, and display the two terminal reasons.
- [x] `core/.../TaskRunnerTest.kt`, `data/.../PersistenceTest.kt` — cover retry, exact cap, approval continuation, and restart with local fixtures.
- Given a saved limit, when a new task starts, then it uses that limit even if the setting changes while it is awaiting approval.
- Given a task with completed tool results, when its next model reply is malformed, then repair preserves the existing tool transcript and never dispatches any part of the rejected batch.

## Implementation Notes

- The repair adds a fixed system message to one provider request. Audit events capture call numbers. Existing tool transcript entries retain their original correlation IDs.
- On the first malformed reply, an exhausted budget ends the turn without scheduling a retry. A second malformed reply after a repair ends with `MALFORMED_TOOL_CALL`.
- The saved preference becomes the active cap after its disk write succeeds. The provider settings form shows a save error when that write fails.

## Plan Change Log

## Review Triage Log

Quick review, 2026-11-21. Two medium findings and one low finding were patched.

| Verdict | Route | Evidence and action |
|---|---|---|
| Medium | Patch | A non-string argument fragment caused a provider decode error before malformed tool-call classification. Both adapters now mark that fragment malformed. Integration fixtures verify the audit, retry, and terminal error. |
| Medium | Patch | The provider screen loaded the app-wide cap only at ViewModel creation. Reopening refreshes the committed cap while preserving a draft or an in-flight save. App JVM fixtures cover the refresh behavior. |
| Low | Patch | Chat counted hidden audit rows when choosing the scroll target. The item count now uses the displayed rows. |

## Verification

- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` should pass existing and new fixtures and assemble the debug APK.
- `git diff --check` should report no whitespace errors.
- Final Gradle run succeeded with 159 passing tests and four existing Markdown tests skipped by host assumptions. Debug APK signing and 16 KiB ZIP alignment verified. ADB listed no attached device or emulator for UI testing.
