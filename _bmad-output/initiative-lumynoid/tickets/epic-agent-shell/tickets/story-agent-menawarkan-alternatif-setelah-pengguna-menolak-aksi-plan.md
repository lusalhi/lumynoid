---
title: 'Agent menawarkan alternatif setelah pengguna menolak aksi'
type: feature
ticket: 13
created: '2026-11-23'
status: done
baseline_revision: '90c04d5a249b9b78dfde9054d9eb291312686111'
route: full
route_source: auto
risk: high
review: quick
review_source: pinned
lenses_ran: []
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Decline stops the task and records the decision, but it gives the model no chance to offer a safe alternative.

**Approach:** After persisting Decline, let the original task request at most one text answer using its remaining model budget. The model receives factual outcomes for the denied call and the skipped remainder; the action log remains authoritative.

## Boundaries & Constraints

**Always:** Commit the typed decision before queueing any follow-up. Preserve the original task, workspace admission checks, captured instructions, completed tool results, and single-slot runner. Give the follow-up zero tool definitions. Show only a complete text-only reply. If no reply is available, finish with the existing decline notice.

**Never:** Execute or reassess the rejected or skipped calls on the denial path. Create a second approval, repair request, or synthetic persisted tool result. Change Approve, Stop, expiry, crash recovery, retry policy, or the Room schema.

## I/O & Edge-Case Matrix

| Scenario | Expected behavior |
|---|---|
| Deny midway through a batch | Completed output is retained; the denied call and each skipped call have their own model-visible non-execution outcome. No additional tool executes. |
| Complete nonblank text | Save one ordinary chat answer; Log still shows Declined. |
| No budget, blank/failed/incomplete reply, or tool output from the model | No extra call when out of budget; otherwise at most one call, with no partial or tool-driven answer and no regeneration. Keep the decline notice. |
| Stop, expiry, process restart | No denial follow-up triggered by these outcomes. Stop can cancel queued or running follow-up. Do not replay after restart. |

</frozen-after-approval>

## Code Map

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt` — `resolveWaiting` owns the durable commit and single-slot handoff; `ToolRound.messages` already holds the call IDs and completed results. Branch before approved dispatch/preflight. `readReply` has one stream collector; the text-only path must not publish partial deltas.
- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/ContextAssembler.kt` — durable history includes only complete tool groups. Keep the non-execution outcomes request-local; retain existing history assembly.
- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/{OpenAiCompatibleAdapter,AnthropicAdapter}.kt` — preserve correlation without tool definitions. OpenAI-compatible keeps `Role.TOOL`; Anthropic projects the same calls and outcomes into a JSON transcript carried as text.
- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/{ChatViewModel,AgentService,ChatRows}.kt` — decline already goes through the bound runner; foreground, answer and log use existing busy/event state.

## Tasks & Acceptance

**Execution:**
- [x] `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt` — queue the denial without increasing the budget; pair outstanding calls with app-generated outcomes; request and accept only a complete text answer.
- [x] `core/src/test/kotlin/dev/lusalhi/lumynoid/core/TaskRunnerTest.kt` — cover one-shot admission, preserved prior output, decline notice on failure, Stop and budget; retain durable resolution and no-replay coverage.
- [x] `llm/src/test/kotlin/dev/lusalhi/lumynoid/llm/` — fixture both provider families end to end through the runner, inspect correlated wire outcomes and omitted tools, and prove a tool-shaped reply cannot create execution or another request.
- [x] `README.md` — document the Decline versus Stop behavior.

**Acceptance Criteria:**
- Given a file overwrite awaiting approval, when the user taps Decline and the provider replies with an alternative, then chat shows one answer, Log shows Declined after reopen, and the file still has its original bytes.
- Given the same prompt, when the user taps Stop instead, then no alternative is requested and the file is not touched.

## Implementation Notes

- A committed decline rejoins the ordinary queue if the task has model budget; approval continuations keep priority. The runner builds an in-memory tool-role result for each unexecuted call so both providers receive complete pairs without writing fictitious results to Room.
- Both adapters reject explicit provider cutoff for this request only. No partial text is exposed while the reply is pending; tool deltas terminate the read without dispatch or repair.
- Anthropic must not receive native tool blocks without definitions ([provider confirmation](https://github.com/anthropics/anthropic-sdk-python/issues/494)). This one request carries the correlated data as plain text; ordinary tool rounds retain their native blocks.

## Plan Change Log

## Review Triage Log

Quick review (inline): high 1, medium 0, low 0, false 0, maybe-false 0.

Second review pass: the Cubic review on [PR #10](https://github.com/lusalhi/lumynoid/pull/10) covered this risk-high story in place of lens reviews. Its valid findings were fixed on that PR: Anthropic text transcripts now key on an empty tool list, the `requireCompleteText` contract is documented, and the adapter and runner tests assert exact outcomes. Keeping `APPROVAL_DENIED` when the follow-up reply fails is intended: a failed reply keeps the decline notice (see the outcome table above).

| Finding | Verdict / route | Evidence and resolution |
|---|---|---|
| Anthropic rejects native tool blocks when there are no tool definitions. | High / patched before final verification | Anthropic confirmed the API rejects this combination. The adapter now sends correlated transcript data as text for the Decline-only request. The HTTP fixture proves plain-text bodies with no tool definitions and rejects tool-shaped replies. |

## Verification

- `./gradlew --offline :core:test :llm:test` — approval/routing regression and both fixture HTTP exchanges pass.
- Outstanding: physical handset acceptance with a signed APK, owned by the combined 3.9 + 4.7 release.

- Passed 69 core, 58 provider and 17 action-log JVM tests; assembled the debug fixture APK with a temporary local-only verification overlay (not a production manifest change).
- On isolated arm64 Redroid API 34, the Compose Decline button yielded exactly one alternative. After force-stop and relaunch the answer and Declined log entry were still visible. The original file was unchanged; the skipped file was absent. In a separate active turn, tapping Stop showed Stopped and no alternative. This used a loopback OpenAI-compatible fixture, not a signed APK or live provider.
- Final `./gradlew --offline :llm:test :app:assembleDebug` passed after the Anthropic fix; the APK was rebuilt with the regular debug manifest.
