---
title: 'TaskRunner menuntaskan giliran dengan tool terstruktur'
type: feature
ticket: 2
created: '2026-11-20'
status: built
baseline_revision: 9a8bf036e8e8c4d945e58c1bf2a397cc2d1b5292
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

**Problem:** TaskRunner ends after one model stream; it cannot feed typed tool results back to the model or persist them safely at large sizes.

**Approach:** Continue the existing FIFO turn until a final text answer. Add a provider-neutral tool contract, task-local registry snapshot, and durable typed request/result pairs.

## Boundaries & Constraints

**Always:** Use the existing runner and event log. Snapshot definitions and handlers at task start. Validate completed structured calls before dispatch; allow only deterministically assessed read-only fixture work. Persist requests before execution and results before the next model call. Keep tool output in role `TOOL`, never interpret prose as a call. No replay on recovery.

**Never:** Register production tools or expose shell, network, file, or process capabilities. Provider wire mapping (2.3), approvals (2.4), retry/budget policy (2.6), and action-log/deletion integration (2.9) stay with their tickets. No Room schema bump.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Tool round | Interleaved fragments for two calls, later continuation | One assistant call batch, ordered results with runner IDs, then final reply | No partial execution if the batch is invalid |
| Queued sibling | A later user message is logged during an active turn | Resume from that turn's own pairs; include only earlier answered turns | Omit incomplete pairs and failed tool turns from later context |
| Interrupted | Last event is a tool request or result | Startup appends `TaskFailed(INTERRUPTED)`; leaves original audit events | Never invokes model or tool for recovered work |
| Large event | Encoded UTF-8 payload exceeds 256 KiB | First Room insert contains only reference and bounded preview; reopen reads the full event | Missing or invalid blob projects `Unknown`; known nonterminal envelope still recovers |

</frozen-after-approval>

## Code Map

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/`: `Llm.kt` has text-only messages/chunks; `TaskRunner.kt` owns FIFO, cancellation, and startup recovery; `ContextAssembler.kt` currently stops at the active user row; `TaskEvent.kt` defines stable names and unknown decoding.
- `data/src/main/kotlin/dev/lusalhi/lumynoid/data/`: `Repositories.kt` owns encoding/projection and classifies `Dao.kt#taskLastEvents`; `LumynoidData.kt` supplies the app-private file root. Store a versioned blob envelope in the existing `payload` column; keep legacy inline JSON readable.
- `llm` adapters and `LlmRouter` remain text-only/pass-through. `app/.../AgentService.kt` supplies no tool provider. `ChatViewModel` treats `AssistantMessage` as final; keep tool-round text on the request batch, not as final bubbles.

## Tasks & Acceptance

**Execution:**
- [x] `core/.../Tool.kt`, `Llm.kt`, `TaskEvent.kt` — define AD-6 provider/registry snapshot, schema, assessment/description/outcome, streamed delta slot and runner-issued call ID, typed request/result, and persistent names.
- [x] `core/.../TaskRunner.kt`, `ContextAssembler.kt` — assemble fragments until `Done`, validate the whole batch and resolve only snapshot handlers; persist paired events, build the next request by task ID, and preserve cancellation, live text, and plain-chat behavior. Reject incomplete tool streams without execution.
- [x] `data/.../EventBlobStore.kt`, `Repositories.kt`, `LumynoidData.kt` — publish app-private blobs before Room inserts; store `id/size/sha256`, original event type, and small prefix. Verify bytes on read and classify recovery from the row without loading the blob. Run file I/O off the main thread.
- [x] `core/.../TaskRunnerTest.kt`, `ContextAssemblerTest.kt`, `SerialNamePolicyTest.kt`, `data/.../PersistenceTest.kt` — exercise multi-call continuation, snapshot stability, invalid streams, task isolation, restart, and large UTF-8 storage/readback. Reuse existing chat tests. Document the fixture-only boundary in `README.md`.

**Acceptance Criteria:**
- Given a fragmented in-process fixture turn, when the model resumes, then it sees each full, correctly correlated result and its final answer uses those results.
- Given a pending tool request or result at process restart, when the runner recovers, then it closes the task as interrupted without executing anything from it.
- Given a large event, when Room first writes it and later reopens, then the row is bounded and both history and observation recover the original event.
- Given no registered tools, when a user sends ordinary text, then streaming, persistence, cancellation, and FIFO chat still work unchanged.

## Implementation Notes

- One `ToolCallRequested` records an assistant round with its calls in slot order; each `ToolResult` links by runner-issued call ID. Continuations extend the current immutable request after persistence; the assembler reconstructs equivalent complete groups from history.
- `event_blob_v1` wraps the first Room write for large encoded events. Small/legacy events remain inline. Blob files are grouped by conversation for the ownership and deletion work in 2.9.

## Plan Change Log

## Review Triage Log

### Quick review, 2026-11-20

Verdicts: high 0, medium 5, low 1, false 0, maybe-false 0.

| Finding | Verdict | Route | Evidence and action |
|---|---|---|---|
| Runner accepted a permissive JSON tree and delegated no explicit schema validation | medium | patch | Kotlinx tree parsing accepts bare values, even those overwritten by a duplicate key. Check JSON tokens before parsing, and make `Tool.assess` responsible for the tool's input contract, rejecting invalid arguments before dispatch. |
| EOF after a tool continuation could persist a partial final answer | medium | patch | A text-bearing EOF passed the no-fragments branch. Require `Done` for a tool turn's final reply; retain the existing plain-chat EOF behavior. |
| Room insert for a deleted conversation silently persisted no tool event | medium | patch | The DAO guard inserted zero rows but returned Unit. Check the insertion result for tool request/result; no execution or continuation can rely on a missing audit event. |
| Snapshot schema shared mutable backing maps | medium | patch | `JsonObject` delegates to its supplied map. Detach nested schema containers and test a provider mutating its schema during the first reply. |
| Blob directory entries were not synced before Room could reference them | medium | patch | File sync preceded rename but did not cover the filename or newly created parents. Sync the directory chain before returning the reference. |
| Blob IDs were UUIDv4 | low | patch | AD-19's blob ref is an entity ID; use the existing UUIDv7 generator and check the persisted ID. |

## Verification

**Commands:**
- `./gradlew :core:test :data:testDebugUnitTest --tests '*PersistenceTest' :app:assembleDebug` — tool/context/recovery and real Room tests pass; app still compiles with an empty registry.
- 2026-11-20: `BUILD SUCCESSFUL`; `TaskRunnerTest` 21, `ContextAssemblerTest` 6, `SerialNamePolicyTest` 2, `PersistenceTest` 10, no skips or failures. All matrix rows exercised; debug APK assembled.
- After review: `BUILD SUCCESSFUL`; 49 JVM tests across core and Room, no skips or failures. Debug APK assembled. Directory sync was exercised on the host; no device power-loss test was run.
