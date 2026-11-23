---
title: 'Upgrade mengadopsi chat dan data proyek Epic 2 tanpa menyalin file'
type: feature
ticket: '2'
created: '2026-11-22'
status: built
baseline_revision: '62a9ae208fad4acdfcfaaaa29b96c6561856dbfe'
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

**Problem:** Epic 2 stores coding files and installs under each conversation ID. Story 8.1 resolves workspaces only through Projects, so upgrade leaves existing files orphaned from their chat until adopted.

**Approach:** Classify legacy chats using the contents of both private roots. A chat with data becomes a member of one project with its original workspace ID; a chat without data remains ordinary. Run adoption only from the main process, with persistent retry state per chat.

## Boundaries & Constraints

**Always:** Preserve chat, task, event and blob identity and content. Never copy, move, or modify workspace/install files. Keep task admission, text/HTML workspace resolution and chat deletion closed while classification is unknown. Retry when the main UI starts again; unrelated ready chats remain usable. Android live handset acceptance is batched into 8.8, not a per-story APK install.

**Never:** No extra preview rules (8.3), instruction UI (8.4), project deletion (8.6) or release. No fallback to chat ID for pending rows. No filesystem probes inside a Room schema migration or from the HTML preview process.

## I/O & Edge-Case Matrix

| Given | When | Then |
|---|---|---|
| Legacy chat with files or only a local install | Both original roots are inspected | One project uses that chat ID for its workspace. Use the preserved title or `Untitled Project` if blank. Equal titles do not merge. |
| Legacy chat with missing/empty roots | Both probes succeed | A durable ordinary-chat result, with no directories created. |
| Scan is interrupted or encounters a read error | Task, preview or delete uses an unclassified chat | Reject, without losing data or treating the chat as ordinary; retry after reopen without duplicating completed projects. |
| New ordinary chat or existing 8.1 project member | A legacy pass is pending | Keep the current scope; no legacy scan for that chat. |

</frozen-after-approval>

## Code Map

- `data/src/main/kotlin/dev/lusalhi/lumynoid/data/`: `MIGRATION_4_5` retains legacy task workspace IDs and adds nullable membership. `LumynoidData` opens v5. `RoomConversationPort.workspace` feeds `TaskRunner` and `AppGraph.previewWorkspace`; `delete` already uses the runner's idle gate.
- `userland/src/main/kotlin/dev/lusalhi/lumynoid/userland/`: `UserlandRuntime.workspaceRoot` and `installRoot` own the exact paths; `CodingWorkspace.isValidId` validates the legacy ID without creating storage. The filesystem reader must distinguish missing from unreadable and not follow symlinks.
- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/`: `MainActivity.onStart` is the main-process entry to refresh adoption; `ArtifactHtmlActivity` shares `AppGraph.previewWorkspace` in `:preview` but does not run `MainActivity`. Room reads there must honor the pending marker.

## Tasks & Acceptance

**Execution:**
- [x] `data/…/{Entities,Dao,Repositories,LumynoidData,LumynoidDatabase,Migrations}.kt`, `data/schemas/` — add the pending marker via 5→6 for pre-existing unassigned rows only. Expose a serialized adoption pass; probe outside transactions and atomically commit the project and membership or a plain decision. Refuse workspace resolution and deletion while pending, with the existing idle gate intact.
- [x] `userland/…/{UserlandRuntime,CodingWorkspace}.kt` — probe both owned roots read-only on IO using safe IDs, with absent/empty distinguished from read failure. No runtime download/start required.
- [x] `app/…/{LumynoidApp,MainActivity}.kt` — start/retry adoption from the main activity entry with app-owned scope. Use existing resolver on both preview surfaces. Prevent the preview process from initiating the scan.
- [x] `data/…/LegacyWorkspaceAdoptionTest.kt`, `userland/…/LegacyWorkspaceProbeTest.kt` — use the exported v4 schema on disk, original workspace/package fixtures and an independently reopened DB reader, plus real path tests for empty roots, package-only data and uncertain IO. Cover interruption and retry without duplicate projects.

**Acceptance Criteria:**
- Given a baseline v4 fixture with titled and untitled chats, event/blob, historical task and both root kinds, when adoption completes, then the original data is still readable at the original IDs and paths with exactly one project for each data owner.
- Given a failed or interrupted scan, when the DB is opened in another reader before or after retry, then pending scope and deletion deny access until the atomic decision, and already resolved chats retain their scope.

## Implementation Notes

- Room v6 keeps an unclassified bit only for pre-existing chats without membership. Each successful probe commits the project, membership and cleared bit together. Unreadable chats keep the bit, without preventing other chats from adopting.
- The main activity retries using both original roots without starting userland. The shared preview reader and TaskRunner see no workspace while a chat is pending, and deletion cannot remove its blobs or rows in that state.
- The probe ignores only unpublished npm staging directories in the package root. It leaves those directories untouched and still counts files under the same name in a user workspace.
- The composer clears submitted input only after TaskRunner admits it, so an unclassified chat can retry without retyping.

## Plan Change Log

## Review Triage Log

- Medium, patched — an abandoned `.install-*` directory was mistaken for a local package. Excluded unpublished staging at the install root only; covered by the filesystem test.
- Medium, patched — the composer erased text before a pending chat could reject admission. It now retains unsent input and allows retry; queued project submissions still use one chat.
- Medium, verified with a device fixture — the Room tests inject the probe to simulate cancellation. The Android upgrade smoke additionally runs the production probe against the v4 schema with an empty package-only workspace and the bundled files under the original digest and marker layout.
- The second reviewer did not return findings; the Room and admission paths were inspected inline and exercised by the scoped tests.

## Verification

- `./gradlew --offline :data:testDebugUnitTest --tests '*MigrationTest' --tests '*LegacyWorkspaceAdoptionTest' --tests '*PersistenceTest' :userland:testDebugUnitTest --tests '*LegacyWorkspaceProbeTest' :app:assembleDebug :app:assembleDebugAndroidTest` — migrate exported schema, verify persistent decisions and read-only probing, and compile the actual Android wiring and test APK.
- Signed 2.12 → Projects upgrade and the live 8.1 two-chat tracer on a newly created project run in the same handset session at 8.8.

**Host verification:** 27 tests passed: 6 Room migration, 15 persistence, 2 legacy adoption and 4 filesystem probe; debug APK and Android test APK built.

**Android smoke (Redroid arm64, API 34):** With the exported v4 database, the HTML-only process refused the legacy artifact and kept all chats pending. A blocked send kept the composer text. After repairing the simulated unreadable install path and restarting, the file-only and valid package-only chats appeared as separate projects; the empty and staging-only chats stayed ordinary. The original HTML event rendered its file, and workspace paths, package bytes, task IDs, event IDs and payloads stayed unchanged. This was a debug fixture, not a signed handset upgrade.

`ProjectChatSubmissionTest` also passed on the rebuilt APK (1/1): two queued sends from a new project draft were persisted in one chat.
