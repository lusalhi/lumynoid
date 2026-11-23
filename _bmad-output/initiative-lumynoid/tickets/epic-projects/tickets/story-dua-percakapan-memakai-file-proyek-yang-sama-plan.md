---
title: 'Dua percakapan memakai file proyek yang sama'
type: feature
ticket: '1'
created: '2026-11-22'
status: built
baseline_revision: '37ef6bcb6839b450d6ea91d4ac954ab9aeae142e'
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

**Problem:** The conversation ID currently owns the coding workspace. A fresh chat cannot continue the same work without carrying the old chat history.

**Approach:** A named project owns one durable workspace. Conversations belong to at most one project and keep their own history and log. A task derives its workspace from stored membership, never from the caller.

## Boundaries & Constraints

**Always:** Keep the restricted, bundled coding operations and approval policy. Ordinary chats have no workspace, but can still chat. Preserve v4 rows, event payloads, and files. Deleting a project chat must not clean its project's workspace. Store project instructions; do not use them as model context in this story.

**Never:** No arbitrary code, moving chats, partial release, legacy filesystem adoption (8.2), extra preview provenance policy (8.3), instruction UI/snapshots (8.4), or project deletion (8.6).

## I/O & Edge-Case Matrix

| Given | When | Then |
|---|---|---|
| Two chats in Project A | Chat A2 uses a known path/package from A1 | Both use A's workspace; only A2 history and results enter A2 requests |
| Project B or New Chat | Model requests A's file | B reads only B's workspace; New Chat cannot dispatch a workspace tool and still supports text replies |
| Missing conversation or invalid project membership | Task or preview resolves scope | Reject; do not fall back to the conversation ID |
| Project A has two chats and files | Existing idle-chat deletion removes A1 | A2 and shared files/packages remain; A1 history/log is removed |

</frozen-after-approval>

## Code Map

- `core/…/Ports.kt`, `TaskRunner.kt`, `Tool.kt`: `TaskRequest` trusts a workspace ID today. `admissionLock` already serializes submit/delete; `runSlice` snapshots tools and reads history by conversation. Use the same scoped registry for provider definitions and dispatch.
- `data/…/Entities.kt`, `Dao.kt`, `Repositories.kt`, `LumynoidDatabase.kt`, `LumynoidData.kt`, `Migrations.kt`: Room v4 and explicit migrations; `RoomConversationPort.delete` currently calls userland for each chat. `MigrationTest` validates exported schemas on host.
- `app/…/MainActivity.kt`, `ChatViewModel.kt`, `ConversationScreens.kt`, `LumynoidApp.kt`: existing Compose list and `Screen` routing; conversation created on first send. `ArtifactPreviewScreen.kt` and `ArtifactHtmlActivity.kt` currently derive workspace from the conversation ID.

## Tasks & Acceptance

**Execution:**
- [x] `core/…/Ports.kt`, `TaskRunner.kt`, `Tool.kt` — add project metadata/instruction port and membership/scope contracts. Resolve scope under runner admission, persist nullable task workspace, enforce workspace tool gating and reuse only an unchanged scope on approval resume.
- [x] `data/…/Entities.kt`, `Dao.kt`, `Repositories.kt`, `LumynoidDatabase.kt`, `LumynoidData.kt`, `Migrations.kt`, `data/schemas/` — persist project plus unique workspace, nullable conversation membership, and nullable task workspace. Make 4→5 explicit, editing neither old events nor disk. Join membership to workspace; distinguish missing from plain. Keep the idle gate; never clean shared files from chat deletion or delete old workspace data before adoption.
- [x] `app/…/ChatViewModel.kt`, `MainActivity.kt`, `ConversationScreens.kt`, `LumynoidApp.kt`, `app/src/main/res/values/strings.xml` — add Projects → New Project → project conversation list. Retain plain New Chat. Route draft, reopen, and notification selections without mixing conversation histories. Connect the stored resolver to runner and existing offline preview routes (`ArtifactPreviewScreen.kt`, `ArtifactHtmlActivity.kt`).
- [x] `userland/…/CodingTools.kt`, `CodingRuntime.kt`, `UserlandRuntime.kt` — use only the explicit project workspace for coding, including local installs. Migrate request/runner callers and tests; add migration, scope/history, and shared-file deletion coverage to `MigrationTest.kt`, `PersistenceTest.kt`, and `TaskRunnerTest.kt`.

**Acceptance Criteria:**
- Given a clean arm64 debug installation, when A1 creates a file and installs the bundled npm package, then A2 can read, update, and format that file using the same package. After relaunch both histories/logs reopen separately and the shared files remain.
- Given a v4 DB, when 4→5 runs, then existing chat IDs, tasks (including historical workspace IDs), and event payloads are retained, with no fabricated project memberships or file moves.

## Implementation Notes

- Room v5 has a unique project workspace and nullable conversation membership. It preserves task workspace IDs and existing event payloads. Project list and chat creation do not create workspace files; the first tool access does.
- The same stored resolver backs the runner and the two preview processes. Chat deletion invokes only the chat-owned blob and row cleanup. Legacy workspace adoption is outside this tracer.
- Matched `app/build.gradle.kts` to the published `v0.3.1` baseline: the runtime builds its rootfs download URL from `versionName`, and the failed `v0.3.0` tag has no rootfs asset. No new release was made.

## Plan Change Log

## Review Triage Log

- App review — medium, patched: two sends captured the same draft; promotion replaced the selection and silently dropped the queued send. Captured drafts now retain their saved ID; the Android regression ran both sends in one main-thread callback and verified both messages persisted in the same conversation.
- Core/data review — medium, same patch: confirmed the second send returned before `TaskRunner.submit`; fixed by the same preserved target ID.
- App review — handset acceptance pending: emulator checks are not the required clean-install physical handset run with live tool calls, relaunch, and separate logs.
- Core/data review — handset acceptance pending: the arm64 native fixture and separate UI smoke do not substitute for the full shared file/package and negative checks using a provider on the handset.

## Verification

- `./gradlew :core:test --tests '*TaskRunnerTest' :data:testDebugUnitTest --tests '*MigrationTest' --tests '*PersistenceTest' :app:assembleDebug` — scoped runner/persistence proof and debug APK. Compile or run affected provider, userland, and app test targets if their fixtures change.
- Baseline only: signed Story 2.12 [`v0.3.1`](https://github.com/lusalhi/lumynoid/releases/tag/v0.3.1), SHA-256 `815c57331583028f860722e19a5ded6aed4da8d8b53a6f29385e6228ba39ba08`. No upgrade in this story. On a fresh debug installation on an arm64 handset, execute the two-chat tracer with bundled npm, relaunch/log checks, and cross-project/plain-chat negatives. Record handset evidence before acceptance.

**Observed:** 42 TaskRunner, 21 Room migration/persistence, and 54 LLM provider tests passed. Debug app and Android test APKs built. On an isolated Redroid arm64 API 34 device, the two native coding tests passed: independent conversation IDs reused one workspace and npm install, and a peer workspace was denied. Android navigation smoke created a named project, distinguished project and ordinary drafts, and reopened the persisted project after force-stop.

**Preview:** `PreviewNavigationTest` passed text copy and first HTML open/JavaScript opt-in, then timed out waiting for the HTML heading on reopen in Redroid's accessibility tree. A separate Android UI run directly observed both HTML opens, the script-on marker, and the original heading on reopen with JavaScript off; the instrumentation test was not marked passed.

A second Redroid UI smoke sent two independent prompts through Compose into the same named project, reopened one without the other's text, and confirmed a plain send appeared only in the ordinary conversation list. It used the reserved `.invalid` fixture provider; it verifies persisted chat routing, not live model responses. `ProjectChatSubmissionTest` subsequently passed on the rebuilt APK (1/1).

**Handset handoff:** `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `c3e58879ef2ee7cb23834772831a242159dd5f217d7a6e743d0878ae6eba7427`. Physical handset acceptance is still pending; this is not a release.

**Updated handset schedule:** Run the live tracer on a newly created project during the signed 2.12 → Projects upgrade session in Story 8.8. This replaces the separate clean debug install above, not the file/package, relaunch, log, or isolation checks. See [handset acceptance](../../../../../docs/release.md#projects-handset-acceptance-story-88).
