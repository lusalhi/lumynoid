---
title: 'Log aksi membuka hasil lengkap setelah app ditutup'
type: feature
ticket: 9
created: '2026-11-21'
status: built
baseline_revision: 68e3c0a2bdb85dea564105ac31c83057397d5e69
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

Open a conversation's action log from chat to see what the agent tried, its target and status, and the full stored result, even after process restart. Deleting a finished conversation reclaims its own results and project files.

## Boundaries & Constraints

**Always:** Use typed persisted events and the existing verified blob store. Render output as plain text and make the entire stored output reachable. Keep artifact references scoped to the originating conversation and result.

**Never:** Add preview UI (2.10), clickable model output, approval actions in historical entries, shell access, or changes to which tools may run.

## I/O & Edge-Case Matrix

| Scenario | Expected behavior |
| --- | --- |
| Inline or blob-backed result | Show target, factual status and exact persisted output; a runtime capture limit stays identified as such. |
| Denied, failed, cancelled or interrupted | Preserve recorded decisions and distinguish actions that did not run or produce a result. Do not infer success from missing evidence. |
| Queued, running or awaiting approval | Deny deletion, including a stale UI action racing a task submission. |
| Completed conversation deleted | Remove its tasks, events, blobs, workspace and bundled-package install without touching another conversation or the global runtime. |

</frozen-after-approval>

## Code Map

- `app/.../ChatViewModel.kt` observes Room events tagged by conversation. `MainActivity.kt` routes screens; `Screens.kt` and `ToolCards.kt` show the chat preview. `ChatRows.kt` joins requests to results by task/call in event order.
- `core/.../TaskRunner.kt` publishes pending conversations through recovery and approval waits. Risk preflight currently returns before emitting the requested calls; `ToolOutcome` has an optional exit code but no typed failure for non-process operations.
- `data/.../Repositories.kt` hydrates inline JSON or digest-checked blobs and falls back to `TaskEvent.Unknown`. `Dao.kt` currently deletes only SQL rows. `LumynoidData.kt` composes storage adapters.
- `userland/.../UserlandRuntime.kt`, `CodingWorkspace.kt`, and `CodingRuntime.kt` own `workspaces/<id>` and `coding-installs/<id>`; chat uses the conversation ID for both. Runtime setup is global, not conversation-owned.

## Tasks & Acceptance

- [x] `core/.../{TaskRunner,TaskEvent,Tool,Ports}.kt`, `userland/.../{CodingTools,CodingRuntime,UserlandRuntime}.kt` — retain typed rejected-call identity without relaxing batch preflight; persist explicit failures where exit status alone is insufficient. Older results remain readable and ambiguous outcomes are labeled as such.
- [x] `data/.../{Dao,Repositories,LumynoidData,EventBlobStore}.kt`, `core/.../TaskRunner.kt`, `app/.../{LumynoidApp,ChatViewModel}.kt`, `userland/.../{CodingWorkspace,UserlandRuntime}.kt` — wire delete through a process-owned gate shared with submission, wait for recovery, and keep the gate through cleanup. Delete only the scoped trees without following links, and surface storage errors. Exercise isolation and deletion while work is pending.
- [x] `app/.../{ChatRows,ActionLogScreen,MainActivity,ChatViewModel,Screens,ToolCards}.kt`, `app/src/main/res/values/strings.xml` — provide a read-only conversation log and full plain-text result detail with bounded composition for large output; derive status from request, result, approvals and task terminal events. Expose stable artifact identity from the actual result plus conversation and relative path.
- [x] `core`, `data`, `app`, and `userland` tests plus `README.md` — cover ordering, denial, bounded display of full results, reopen and deletion isolation; document the entry point and deletion behavior.

**Acceptance Criteria:**
- Given short and blob-backed outcomes plus a denied request, when the process is killed and the conversation reopened, then its action log shows the persisted targets/statuses and permits reading the final byte of either stored output.
- Given conversations A and B with files and results, when deletion is attempted while A is queued, running or awaiting approval, then A stays intact; after completion, deleting A removes only A's data and B remains usable.

## Implementation Notes

- The **Log** button opens a read-only view of persisted tool calls. The detail view renders output in lazy, surrogate-safe chunks and can jump to the tail. Artifact references contain the conversation ID, result event ID, relative path and media type; they do not open a preview.
- `TaskRunner` serializes deletion with submission and holds the boundary through cleanup. The workspace and scoped install are deleted first, then event blobs, then SQL rows. A failed cleanup leaves the conversation reachable for retry; an attempt to enqueue against a deleted conversation fails before tool execution.
- Structured policy denials are audited without dispatching the batch. Tool outcomes record explicit verdicts where available. Legacy zero-exit outcomes can remain unconfirmed because publication may have failed; a missing result alone is not proof that an action did not run.

## Plan Change Log

## Review Triage Log

Quick review: three medium and two low findings patched.

| Finding | Verdict / route | Evidence and action |
| --- | --- | --- |
| Failed storage cleanup could leave files with no reachable conversation, or run blob I/O on the UI thread | Medium / patch | The Room port keeps the row until cleanup succeeds, runs blob I/O on IO, and the failure/retry test preserves readable output after a workspace error. |
| A failed task without a recorded result could be mislabeled as not run | Medium / patch | A failed write after tool execution does not prove non-execution. Show no recorded result unless a typed denial proves the action was blocked. |
| Jumping to the last chunk can stop before the tail of that chunk | Medium / patch | A tail anchor after the last chunk makes the final screenful reachable in one jump. |
| Leaving chat for the log could discard an unsent draft | Low / patch | A saveable-state holder spans chat and the log; its chat provider is disposed when the log opens. |
| Artifact references lacked an explicit conversation identity | Low / patch | References now name conversation, result event, path and media type; the log still cannot open artifacts. |

## Verification

- `./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` — projection, storage and runner cases pass; debug APK builds.
- In Android with a fixture provider, perform short and large reads plus a denied action; force-stop, reopen the log, and inspect status/output. Verify blocking during unfinished work and scoped deletion. Record the device and any acceptance case not observed.
- Final Gradle check: 130 JVM tests passed, four existing Markdown host-assumption tests skipped. Debug app and instrumentation APKs assembled; `apksigner verify` accepted the debug app APK.
- On arm64 Redroid (Android 14/API 34), nine userland instrumentation checks passed. The workspace-deletion check initially used a fixture ID with an invalid dot; with a valid ID it passed on rerun, verifying isolated workspace and bundled-package removal, symlink safety, and retention of the peer workspace and global rootfs.
- The arm64 emulator UI used a scripted OpenAI-compatible provider over loopback, in a temporary fixture APK that allowed cleartext. The production debug APK has no fixture override. The provider received the exact short and 271,904-character tool outputs. The UI hid Delete during a pending overwrite, accepted Decline, and marked an invalid path blocked. After force-stop, the action log displayed succeeded and failed results; a draft survived navigation into and out of the log.
- After force-stop, the short output and the tail of the blob-backed output were visible through **Log** → **Jump to end**; declined and policy-blocked actions retained distinct statuses. While Alpha ran and Beta was queued, neither conversation offered Delete. After both finished, deleting Alpha removed its workspace and blob directory, and Beta retained its file and readable log. No physical API 37 handset was used in this story check.
