---
title: 'Aksi berisiko menunggu persetujuan tanpa menahan antrean'
type: feature
ticket: 4
created: '2026-11-20'
status: built
baseline_revision: 1c6caffcbb1dd78a49e7850f26223480f1397a02
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

Risky structured calls need explicit approval while unrelated turns remain runnable. Gate execution in TaskRunner and keep the pending continuation in the app process across ordinary AgentService stops.

## Boundaries & Constraints

Allow deterministic READ_ONLY and LOCAL_WRITE. Require one approval each for DESTRUCTIVE, FINANCIAL, EGRESS, and PERSISTENT. Reject invalid, unregistered, or DENIED calls before batch execution. Assess current state at dispatch and fail closed if a saved approval no longer matches. Approval expires after 12 hours. Persist typed approval events before state changes and keep tool output in role TOOL. Preserve the single execution slot, registry snapshot and cancellation path. Add the lifecycle handoff for 2.5 and typed event and bound port for 2.8. Shell, tool registration, cards and Room schema changes are outside this story.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Risk policy | Each risk class, including structured calls prompted by fixture output | Automatic only for allowed classes; separate approval ID per risky call | DENIED never executes |
| Handoff | A pauses in a multi-call turn, then B in the same conversation finishes | Lifecycle release before next work; A alone leaves busy false; A resumes with its own messages/results and keeps its registry snapshot | Neither B nor earlier executed calls replay in A |
| Decision | Duplicate/unknown ID, denial, expiry, cancel or risk changed during wait | One durable resolution per waiting request; no stale or rejected dispatch | Approved work gets queue priority without preemption |
| Recovery | Restart with last event an approval request or resolution | Persist INTERRUPTED without model or tool replay | Same-process service recreation keeps the waiting continuation |

</frozen-after-approval>

## Code Map

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt` owns FIFO, context assembly, prepared calls and event writes. Save original request and remaining batch position. `ContextAssembler.kt` already groups by task and skips incomplete rounds.
- `core/.../Ports.kt`, `Tool.kt`, `TaskEvent.kt` own ports, assessment, stable event and failure names and recovery classification. `data/.../Repositories.kt` guards durable tool writes and reads last events or blob metadata.
- `app/.../LumynoidApp.kt` is the process composition root. `AgentService.kt` owns binder, foreground state and wake lock. `ChatViewModel.kt` uses pending conversations to guard deletion. `Screens.kt` maps failures exhaustively.

## Tasks & Acceptance

**Execution:**
- [x] `core/.../TaskEvent.kt`, `Ports.kt`, `Tool.kt` — add typed request and resolution (approval and call IDs, risk, tool details, expiry and decision), ApprovalPort, stable failures and injectable suspending tool-session release hook.
- [x] `core/.../TaskRunner.kt` — validate batch, gate each call, persist typed events, save task-local continuation and release resources before freeing the slot. Linearize resolve/expiry/cancel and ensure event observation cannot race pending registration. Resume approved work ahead of FIFO submissions. Include waiting tasks in pending conversations and exclude them from busy.
- [x] `data/.../Repositories.kt`, `app/.../LumynoidApp.kt`, `AgentService.kt`, `Screens.kt`, `app/src/main/res/values/strings.xml` — guard approval writes, reuse a single process-scoped runner through service recreation, expose ApprovalPort via the bound service and display approval failures.
- [x] `core/.../TaskRunnerTest.kt`, `data/.../PersistenceTest.kt` — cover matrix in fixture and Room tests, including cancellation before dispatch and event visibility.

**Acceptance Criteria:**
- Given a risky structured call, when the runner reaches it, then it persists a per-call request before dispatch and runs it only after approval of that exact call.
- Given one paused turn and another in the same conversation, when the second finishes and the first is approved, then both finish independently through the existing loop.
- Given a killed process with a pending or resolved approval, when a new runner starts, then it closes interrupted work without replay.

## Implementation Notes

- AgentService uses the process-owned runner. A paused turn retains its prepared round and registry snapshot, releases task resources, and keeps conversation deletion blocked while the foreground busy signal is idle.
- The runner persists one typed resolution before dispatch. Database retries reuse that event ID. It checks expiry, risk, and the described action again when an approved call reaches the head of the queue.
- The app manifest test now expects the existing notification permission. A clean baseline checkout reproduced the stale expectation.

- Approval expiry uses elapsed time. The stored wall-clock timestamp is available for display.

## Plan Change Log

## Review Triage Log

### Quick review, 2026-11-20

Verdicts: high 1, medium 2, low 0, false 0, maybe-false 0.

| Finding | Verdict | Route | Evidence and action |
|---|---|---|---|
| Resume could dispatch the approved first call after a later call became denied during the wait | high | patch | The whole-batch check only ran for fresh rounds. Reassess the remaining batch before approved dispatch. |
| A failed write with confirmed absence kept the attempted decision frozen | medium | patch | The in-memory event was reused after both a successful absence check and a failed read. Release the decision on confirmed absence and preserve its ID for ambiguous retries. |
| Wall-clock adjustment could move the effective expiry | medium | patch | The delay and dispatch compared against epoch time. Use a monotonic deadline for the 12-hour interval and retain the epoch timestamp for display. |

## Verification

- `./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :llm:test` — passed after review. Core 50, Room 16, provider 53, and app 29 passed. Four existing app tests were skipped.
- The debug APK passed `apksigner verify`. ADB found no connected device, so Android service recreation, foreground notification and wake-lock behavior remain unverified on device.
