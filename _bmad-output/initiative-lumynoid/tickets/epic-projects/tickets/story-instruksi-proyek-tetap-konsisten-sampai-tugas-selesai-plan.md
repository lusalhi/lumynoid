---
title: 'Instruksi proyek tetap konsisten sampai tugas selesai'
type: feature
ticket: '4'
created: '2026-11-22'
status: built
baseline_revision: '9fd2b2ed20b9fa06fa9319f125c52316361132ab'
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

**Problem:** Projects already store shared instructions, but users cannot edit them and tasks do not send them to the model.

**Approach:** Edit instructions from the Project page. Read the stored value at task execution before the first model request and keep it in the context already carried through tool rounds and approval continuations.

## Boundaries & Constraints

**Always:** Use the existing 2,000 Unicode code-point limit; save multiline text without trimming, and allow clearing. Only send the instructions for the resolved project, and only the history of the task's own conversation. Preserve the existing tool registry, scope and approval checks.

**Never:** No Room schema change, new event type, context budget, or resumption after process death. Do not load files, other projects, or other conversations into model context.

## I/O & Edge-Case Matrix

| Given | When | Then |
|---|---|---|
| A project with saved instructions | Its editor is reopened | The stored multiline value is shown. Cancel leaves it unchanged; saving empty clears it. |
| The draft has 2,000 Unicode code points | The user saves it | The full text is persisted, including supplementary code points. |
| The draft has 2,001 code points | The user tries to save | The editor visibly rejects it without truncating or replacing the stored value. |
| A project task gets an invalid tool response or waits for approval while instructions change | The runner retries or resumes | The model sees the original instruction, and the tool still requires the same approval. |
| A new task starts after the save | The model is called | Project chat uses the new value without sibling history. Ordinary chat gets no project instruction. |

</frozen-after-approval>

## Code Map

- `core/…/TaskRunner.kt`: `runSlice` assembles fresh input once and reuses `Continuation.request` after revalidating workspace scope. Repair adds a temporary system message without rebuilding history. `ContextAssembler` builds conversation-local messages. Both adapters already serialize `Role.SYSTEM`.
- `data/…/{Dao,Entities,Repositories}.kt`: `project.workspace_id` is indexed and unique; `RoomProjectPort` already saves and validates instructions. Story 8.2 gates pending adoption before the runner gets a workspace; keep the instruction read out of that resolver and out of preview.
- `app/…/{ConversationScreens,MainActivity,ChatViewModel,LumynoidApp}.kt`: `Screen.PROJECT` reuses `ConversationListScreen` with the observed `Project`; `AppGraph` supplies TaskRunner's ports. Use existing Material dialog, string resources and Room observation.

## Tasks & Acceptance

**Execution:**
- [x] `core/…/{Ports,ContextAssembler,TaskRunner}.kt`, `data/…/{Dao,Repositories}.kt`, `app/…/LumynoidApp.kt` — share the storage limit, read the instructions once by resolved workspace, and prepend nonblank text as a system message only for fresh requests. Keep request continuation and workspace equality separate.
- [x] `app/…/{ConversationScreens,MainActivity,ChatViewModel}.kt`, `app/src/main/res/values/strings.xml` — add a Project-only editor with a live Unicode count, over-limit error and save handling that keeps the draft on failure.
- [x] `core/…/{TaskRunnerTest,ContextAssemblerTest}.kt`, `data/…/PersistenceTest.kt` — exercise an edit during repair and approval, next-task refresh, conversation and project isolation, and Unicode persistence without overwrite on rejection.

**Acceptance Criteria:**
- Given a project chat waiting for overwrite approval, when instructions are changed and the original action is approved, then the provider still receives that task's original instruction, and the next project task receives the updated value without sibling history.
- Given an open Project page, when a user saves or clears instructions and reopens the editor, then it displays the stored value, with a visible limit and an error for over-limit input.

## Implementation Notes

- The task's first request reads the instructions by its resolved workspace ID. Repair and approval resume reuse that request's snapshot. Workspace revalidation and tool approval are unchanged; instructions are not written to events. No schema change.
- The editor saves the exact draft to the displayed project ID. Room and Compose use the same Unicode limit.

## Plan Change Log

## Review Triage Log

- Inline quick review: constrained the field height so the count and error stay visible for long drafts; bound saves to the displayed project and guarded duplicate taps in the dialog. No product findings deferred.

## Verification

- `./gradlew --offline :core:test --tests '*TaskRunnerTest' --tests '*ContextAssemblerTest' :data:testDebugUnitTest --tests '*PersistenceTest' :app:assembleDebug :app:assembleDebugAndroidTest` — **70 tests passed** (45 runner, 8 context, 17 persistence); both APKs built. The provider fixture observed original instructions on repair/resume and updated instructions on the next task, with no sibling history or project instructions in ordinary chats.
- Redroid arm64 API 34 — **1 smoke passed** through the actual Project editor: saved and reopened exactly 2,000 code points including supplementary characters, visibly rejected 2,001 on a Save tap, preserved the saved value on cancel, cleared it, and verified the ordinary chat list has no editor. This was a disposable debug install; signed handset acceptance remains in Story 8.8.
