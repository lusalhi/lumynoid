---
title: 'Answers finish in the background and can be stopped'
type: feature
ticket: '8'
created: '2026-11-15'
status: done
baseline_revision: '691a49f9c36f541789cf34043d8da1402642ce74'
route: full
route_source: auto
risk: medium
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `AgentService` is a plain started service, so an answer streaming when the user switches apps can be frozen or killed before it is saved. The chat has no way to stop an answer. A task cut off by a crash or kill stays open in the event log forever (R3, AD-2, AD-3, AD-22).

**Approach:** The service goes foreground with a `task_running` notification and a partial wake lock only while the runner has work, and drops both when it is idle. The chat gets a Stop button that calls `TaskRunner.cancel`. On start, the runner closes every task that has no terminal event with `TaskFailed(INTERRUPTED)` before it takes new work.

## Boundaries & Constraints

**Always:** AD-2/AD-3: one runner in `AgentService`, FIFO, one task running at a time. AD-4: only `TaskRunner` appends events, including the INTERRUPTED ones. AD-22: `cancel` is the only cancel path. AD-24: only `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `WAKE_LOCK` are added. C5: no foreground service, wake lock or started service while idle. `:core` stays pure Kotlin. Strings in `strings.xml`, English, Material 3 defaults.

**Never:** No `POST_NOTIFICATIONS` request (epic-tugas-terjadwal). No `task_result` or `approval_needed` channels. No Stop action in the notification. No battery-optimisation prompt. No schema change. No automatic retry of an interrupted task.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Background | Send a long question, switch apps | Answer finishes and is saved; notification shows while it runs and is gone after | No notification permission: shown only in the system's active-apps list |
| Queue | A running, B queued | Stays foreground from A's start to B's end; one notification | — |
| Stop | Tap Stop while answering, before or after the first token | Streaming ends; chat shows "Stopped."; the partial text is not saved | Stop on a task that just finished does nothing |
| Stop with queue | Stop A while B is queued | A shows "Stopped."; B starts | — |
| Failure | Revoked key, network down, empty answer | Chat shows the reason under the user's message | — |
| Crash recovery | Process killed mid-answer, or with a task still queued | On next start each such task gets `TaskFailed(INTERRUPTED)`; chat shows "This answer was interrupted." | Finished, failed, cancelled and `Unknown`-ending tasks are untouched |
| Send during recovery | User sends while recovery runs | The new task is never marked INTERRUPTED | — |
| Foreground denied | System refuses to start the FGS | Task still runs; no crash | Failure is caught and logged |

</frozen-after-approval>

## Code Map

- `core/.../TaskRunner.kt` -- private `running: MutableStateFlow<Pair<String, Job>?>`. Add public `active: StateFlow<ActiveTask?>` (`taskId`, `conversationId`, set when the job starts, cleared after `join`) and `busy: StateFlow<Boolean>` (true from `submit` until the last queued or running task ends; count kept in a `MutableStateFlow<Int>`). Recovery runs first in the `init` coroutine (`events.unfinishedTasks()` → append `TaskFailed(id, taskId, now, INTERRUPTED)` per task); `submit` awaits a `CompletableDeferred` completed after recovery, and the queue loop starts after it. A recovery failure is caught, so the queue still runs.
- `core/.../Ports.kt` -- `EventRepository` gains `suspend fun unfinishedTasks(): List<UnfinishedTask>`; `data class UnfinishedTask(taskId, conversationId)` beside `TaskRequest`.
- `core/src/test/.../TaskRunnerTest.kt` -- `InMemoryEvents` keeps a task→conversation map and implements `unfinishedTasks` with the same rule as `:data`. Existing tests stay green.
- `data/.../Dao.kt`, `Repositories.kt` -- DAO query returning each task's id, conversation id and the payload of its last event (`ORDER BY seq DESC LIMIT 1`, subquery filtered by `conversation_id` too so it uses the index). Unfinished = no event, or the last one decodes to `TaskQueued`, `UserMessage` or `AssistantMessage`. `Unknown` counts as finished.
- `data/src/test/.../PersistenceTest.kt` -- add tests in the existing style.
- `app/.../AgentService.kt` -- collects `runner.busy` on `Dispatchers.Main`, skipping the initial value. On true: `startService(self)` so it survives unbind, `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` (0 below API 34), acquire a non-reference-counted `PARTIAL_WAKE_LOCK` with a 60-minute safety timeout. On false: `ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)`, release, `stopSelf()`. Each platform call is wrapped in `runCatching` plus `Log.w`. `onDestroy` releases the lock. Creates channel `task_running` (`IMPORTANCE_LOW`). The notification uses `NotificationCompat`: small icon, title, "Answering…", ongoing, and a content intent to `MainActivity` with `FLAG_IMMUTABLE`. Update the KDoc.
- `app/.../AgentConnection.kt` -- `connect()` only binds; the service starts itself when busy.
- `app/src/main/AndroidManifest.xml` -- three permissions; service `android:foregroundServiceType="specialUse"` with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>` describing the user-requested AI answer.
- New `app/src/main/res/drawable/ic_stat_task.xml` -- simple white vector for the small icon. The app has no drawables yet.
- `app/.../ChatViewModel.kt` -- `ChatState` gains `runningTaskId` (the runner's `active` task when it belongs to the open conversation). `streamingConversationId` comes from `active`, not `live`, which also closes story 7's gap before the first token. Add `stop()`, which calls `runner.cancel(runningTaskId)`.
- `app/.../Screens.kt`, `MainActivity.kt` -- while `runningTaskId != null`, the Send button becomes a Stop button. Failure rows already map every `FailureReason`, including INTERRUPTED, so no change there.
- `res/values/strings.xml` -- `chat_stop`, channel name, notification title and text. `error_interrupted` and `task_cancelled` already exist.
- Do not change: `ContextAssembler` (failed and cancelled turns are already left out), `llm/`, schemas.

## Tasks & Acceptance

**Execution:**
- [ ] `core/.../{Ports,TaskRunner}.kt` -- `UnfinishedTask`, `unfinishedTasks`, `active`, `busy`, recovery gate -- AD-2, AD-3, AD-22
- [ ] `core/src/test/.../TaskRunnerTest.kt` -- recovery closes queued-only, user-message-only and assistant-without-finish tasks; leaves terminal and `Unknown` ones alone; a submit during recovery is not marked; `busy` goes true→false across A+B with no false between; `active` is set before the first token; cancel before the first token records `TaskCancelled` -- I/O matrix
- [ ] `data/.../{Dao,Repositories}.kt` + `PersistenceTest.kt` -- `unfinishedTasks` with every last-event case, including a task with no events -- AD-22
- [ ] `app/.../{AgentService,AgentConnection}.kt`, `AndroidManifest.xml`, `res/drawable/ic_stat_task.xml` -- FGS, wake lock, channel, notification -- AD-2, AD-24, C5
- [ ] `app/.../{ChatViewModel,Screens,MainActivity}.kt`, `strings.xml` -- Stop button, `runningTaskId`, delete guard from `active` -- R3

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` runs, then it passes and no schema file changes.
- Given the merged debug manifest, when its permissions are listed, then they are exactly `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, plus the `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that AndroidX adds.
- Given a debug APK on a phone, when the tester runs the ticket's verify steps (long answer then switch apps, Stop, revoked key), then each behaves as in the matrix.

## Implementation Notes

Built by a Sonnet subagent; review patches by the same subagent. The build follows the Code Map. The `:data` side adds a `TaskLastEventRow` projection in `Entities.kt`. The orchestrator added `cancellingATaskThatAlreadyFinishedChangesNothing` for the "Stop on a task that just finished" row.

Not done here, by scope or environment:
- Not run on a device. The foreground service, notification, wake lock, Stop button and the "Foreground denied" row are compiled but untested, because `:app` has no Robolectric or instrumented setup. The ticket's verify step belongs to the human tester.
- When the service is not already started and the app is no longer in the foreground as `busy` turns true, Android may refuse `startService` or `startForeground`. The failure is logged and the task still runs, but nothing protects it from being killed.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens. The owner's usual reviewer, `omp` with `gpt-6.1-sol`, failed with `usage_limit_reached`, so at the owner's choice the lens ran in a fresh Claude Opus 5.5 subagent. 6 findings: high 0, medium 0, low 5, false 0, maybe-false 0, plus one pre-existing gap deferred. No intent_gap or bad_plan; no loopback. Three patches were sent to the implementation subagent, and full verification was re-run afterwards.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | The `busy` test cannot detect a false→true blip between A and B | low | patch | Real: after A's `join` the loop runs to B's `join` without suspending, so a collector on the standard test dispatcher never sees a gap. Collector moved to `UnconfinedTestDispatcher`. |
| 2 | `onDestroy` cancels the scope, so a task cut off by the system is recorded as `TaskCancelled` ("Stopped.") instead of INTERRUPTED, bypassing `cancel` | low | reject | Real, but only reachable when `startForeground` fails while the app is visible and the system later stops the background service mid-answer; the FGS path is destroyed only after `stopSelf` at idle. The fix needs a user-cancel cause type and a branch in `execute`, more than a direct correction. |
| 3 | One failed append in `recoverInterrupted` leaves the remaining tasks open | low | patch | Real: the `try` wrapped the whole loop. Now caught per task. |
| 4 | A late Stop leaves its id in `cancelled` for the runner's life | low | reject | Pre-existing `cancel` behaviour, now reachable from Stop. Each late tap leaves one UUID. Fixing it needs queued-id tracking. |
| 5 | Delete is allowed for a conversation with a queued (not running) task | medium | defer | Pre-existing since story 1.7, where it is noted. The guard now uses `active` instead of `live`, which is stricter than before. Added to `deferred-work.md`. |
| 6 | Unused `id` parameter and fully qualified `FailureReason` in `PersistenceTest` | low | patch | Real; parameter dropped and import added. |

## Design Notes

- "Busy" is queued or running, not running alone. In this epic a queued task only exists behind a running one, so the two match AD-2. Dropping foreground between FIFO tasks would mean restarting it from the background, which Android 12+ refuses.
- The service stays started only while busy. When idle and unbound (activity gone), it is destroyed. The next bind creates a fresh runner, whose recovery finds nothing to close.
- Stop discards the partial text: the runner records only `TaskCancelled`, and `ContextAssembler` already drops cancelled turns. Keeping partial answers would change the event contract, so it is out of scope here.

## Verification

**Commands:**
- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest` -- expected: all pass
- `./gradlew :app:assembleDebug :app:checkDependencyPolicy` -- expected: BUILD SUCCESSFUL
- `grep -o 'uses-permission[^>]*name="[^"]*"' app/build/intermediates/merged_manifest/debug/*/AndroidManifest.xml` -- expected: only the permissions in the AC
- `git status --short data/schemas` -- expected: no output

**Manual checks (if no CLI):**
- No device on the build host. The ticket's verify step belongs to the human tester: long answer + switch apps (notification or active-apps entry gone afterwards), Stop, and a revoked key showing its reason.
