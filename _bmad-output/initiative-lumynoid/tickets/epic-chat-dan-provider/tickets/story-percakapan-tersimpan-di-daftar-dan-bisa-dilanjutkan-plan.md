---
title: 'Saved conversations in a list that can be resumed'
type: feature
ticket: '7'
created: '2026-11-15'
status: done
baseline_revision: '93fb95650be7ac9f879309905865ea9b442d5e3a'
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

**Problem:** Every request carries only the current message, so a reopened conversation forgets what was said, and the app only ever reopens the latest conversation: there is no list, no new, no rename, no delete (R3).

**Approach:** A naive `ContextAssembler` in `:core` builds each `LlmRequest` from the conversation's event log. `ConversationPort` gains list, rename and delete; Room schema v4 adds a conversation title, backfilled by `MIGRATION_3_4`. The app gets a conversation list screen.

## Boundaries & Constraints

**Always:** AD-1 boundaries, `:core` pure Kotlin. Only `TaskRunner` appends events (AD-4); the UI writes conversations only through `ConversationPort` (AD-23). Delete removes the conversation row, its tasks and all its events in one transaction. Schema v4 exported and committed, explicit tested `MigrationTestHelper` migration, no destructive fallback, `1.json`–`3.json` untouched (AD-19). Title = first user message, no extra LLM call. UI strings in `strings.xml`, English, Material 3 defaults.

**Never:** Context trimming, token budgets, system prompt or tool turns (epic-hemat-kuota, epic-agent-shell); blob or workspace deletion (none exist yet); a stop-streaming button (story 8); first-launch onboarding (story 9); search or pinning conversations; editing or deleting single messages.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Resume | Open an old conversation, send a message | Request holds every earlier answered turn, in order, then the new message | — |
| Unanswered turn | An earlier task failed or was cancelled | Its user message is left out of later requests | — |
| Queued after | Messages B then C queued while A streams | B's request holds A and B, never C | — |
| New | Tap New | Empty chat; the conversation is created on its first message and titled from it | Nothing appears in the list until a message is sent |
| Title | First message long or multi-line | First non-blank line, whitespace collapsed, cut to 60 chars plus "…" | Empty title shows "New conversation" |
| List order | Several conversations | Most recent activity first (last event, else creation); on launch the top one opens | — |
| Rename | New name | List and chat header show it | Blank name → rejected, old title kept |
| Delete | Confirm delete | Gone from the list after restart; its events and tasks are gone; if it was open, chat goes to an empty New state | Not offered for the conversation whose answer is streaming |
| Upgrade v3 → v4 | Existing conversations with events | Each gets its first user message as title; events untouched | No user message → empty title |

</frozen-after-approval>

## Code Map

- `core/.../Ports.kt` -- `Conversation(id, createdAt)` and `ConversationPort{create, latest}`. Becomes `Conversation(id, title, createdAt, lastActivityAt)`; port: `create(firstMessage)`, `latest()`, `observeAll(): Flow<List<Conversation>>`, `rename(id, title): Boolean`, `delete(id)`. `EventRepository` gains read-only `suspend fun history(conversationId): List<TaskEvent>`.
- `core/.../TaskRunner.kt:77` -- builds `LlmRequest(listOf(USER input))`; replace with `ContextAssembler.assemble(events.history(conversationId), taskId)`. Nothing else in the runner changes.
- `core/.../ContextAssembler.kt` (new) -- pure object; `ConversationTitle.from(text)` beside it (also used by `:data` migration).
- `core/src/test/.../TaskRunnerTest.kt` -- `InMemoryEvents` fake ignores `conversationId`; add `history`, keep existing tests green.
- `data/.../{Entities,Dao,Repositories,LumynoidDatabase,Migrations,LumynoidData}.kt` -- `ConversationEntity` adds `title TEXT NOT NULL DEFAULT ''`; version 4; `.addMigrations(..., MIGRATION_3_4)`. Order by `COALESCE((SELECT MAX(at) FROM task_event e WHERE e.conversation_id = c.id), c.created_at) DESC, id DESC`, shared by `latest()` and the observed list. Delete via a `@Transaction` DAO method over `task_event`, `task`, `conversation`.
- `data/src/test/.../{MigrationTest,PersistenceTest}.kt` -- extend in the existing style (`MigrationTestHelper` on `BundledSQLiteDriver`).
- `app/.../ChatViewModel.kt` -- holds `conversationId`; `send` creates on first message. `visibleLive` does not filter by conversation: it must, or a live answer shows in the wrong conversation. Add `Screen.CONVERSATIONS`, open/new/rename/delete actions.
- `app/.../{Screens,MainActivity}.kt`, `res/values/strings.xml` -- chat header gets "Conversations" and "New"; new list screen (newest first, tap opens, overflow menu → rename dialog / delete confirm). Put the list in a new `ConversationScreens.kt`.
- `llm/` -- both adapters already map USER/ASSISTANT turns; no change.

## Tasks & Acceptance

**Execution:**
- [ ] `core/.../{Ports,ContextAssembler,TaskRunner}.kt` -- reshape the port, add assembler + title, runner uses assembler -- R3, AD-13 (naive)
- [ ] `core/src/test` -- assembler: order, unanswered turns dropped, queued-after excluded, `Unknown` ignored; title rules; runner sends history -- I/O matrix
- [ ] `data/...` -- v4 entity, DAO, `RoomConversationPort`, `MIGRATION_3_4` (Kotlin backfill via `TaskEventJson` + `ConversationTitle`), committed `schemas/.../4.json` -- AD-19
- [ ] `data/src/test` -- migration 3→4 with titled and message-less conversations, `runMigrationsAndValidate`; persistence: ordering by activity, rename, blank rename rejected, delete removes rows and leaves another conversation intact -- AD-4, AD-19
- [ ] `app/...` -- list screen, new/open/rename/delete, live-answer filter, strings -- R3

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` runs, then it passes and `data/schemas/**/4.json` is the only schema file added.
- Given a debug APK on a phone, when the tester makes three conversations, closes and reopens the app, resumes the second and asks about its earlier content, renames the first and deletes the third, then the answer uses the earlier content, the new name shows, and the third is gone from the list.

## Implementation Notes

Built by a Sonnet subagent; review patches by the same subagent. Verified after the patches: `:core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` pass (73 tests, 0 failed); `4.json` is the only schema file added.

Where the build differs from the Code Map:
- `ConversationTitle` lives in `ContextAssembler.kt`. `ChatViewModel` serialises the first-send `create` behind a `Mutex`, so two quick sends cannot create two conversations.
- `createTask` and `append` write only while the conversation row exists (`INSERT … SELECT … WHERE EXISTS`), so a task queued in a conversation that is then deleted leaves no orphan rows. The UI still hides Delete for the conversation whose answer is streaming.
- The DAO gained `taskCount()`, used only by a test.

Not done here, by scope or environment:
- Not run on a device or emulator. `:app` has no ViewModel or Compose test setup, so these matrix parts are compiled but untested: New creates nothing until the first send, the "New conversation" placeholder, hiding Delete while streaming, and the live-answer filter.
- A queued task whose conversation is deleted still runs: with no history it sends an empty request, the provider rejects it, and the failure is dropped by the guard.
- The rename dialog disables Save on blank input, so the port's blank-name `false` is only reached from tests.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run through `omp` with gpt-6.1-sol (read and grep tools) on the staged diff and the plan. 4 findings: high 0, medium 1, low 2, false 1, maybe-false 0. No intent_gap or bad_plan; no loopback. Two patches applied by the implementation subagent; full verification re-run after: 73 tests, 0 failed.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Conversations with a queued task, or one waiting for its first token, can be deleted; the runner then writes orphan task/event rows | medium | patch | Real: `TaskRunner` sets `live` only on the first `TextDelta`, so the delete guard misses both cases, and `task_event` has no foreign key. The DAO now inserts tasks and events only when the conversation exists, in one atomic statement each; `PersistenceTest.writesForADeletedConversationLeaveNoRows` added. |
| 2 | Switching conversations briefly shows the old transcript under the new title | low | patch | Real: `header` emits the new id at once while `combine` keeps the old `events` until Room emits for the new id. The events flow now carries its conversation id and is used only when it matches. Not unit-tested (`:app` has no ViewModel tests). |
| 3 | A pending first send can switch the chat back after the user taps New or opens another conversation | low | reject | Real but the window is one Room insert (milliseconds), and a fix needs a generation counter or a similar guard. |
| 4 | Diff bumps the version to 4 without `4.json` | false | reject | The staged diff left `data/schemas` out on purpose; `data/schemas/.../4.json` exists in the working tree as a new file. |

## Design Notes

- Creating the conversation on first send (not on "New") keeps empty conversations out of the list and gives the title at creation; story 9 reuses the same "New" action.
- Deleting the streaming conversation is blocked rather than cancelled: a cancel appends `TaskCancelled` after the delete and would leave orphan events.
- Activity order is computed in SQL, not stored, so `TaskRunner` keeps writing only events.

## Verification

**Commands:**
- `./gradlew :core:test :llm:test :data:testDebugUnitTest` -- expected: all pass, including `MIGRATION_3_4`
- `./gradlew :app:assembleDebug :app:checkDependencyPolicy` -- expected: BUILD SUCCESSFUL
- `git status --short data/schemas` -- expected: only `4.json` added

**Manual checks (if no CLI):**
- No device on the build host: the ticket's verify step (three conversations, restart, resume, rename, delete) belongs to the human tester.
