---
title: 'Hapus proyek secara terbatas dan lanjutkan jika terputus'
type: feature
ticket: '6'
created: '2026-11-22'
status: built
baseline_revision: 'bbca0459ba482bd1de78c7d04294bcaa96ab49fb'
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

**Problem:** Users can delete a chat but not its complete project. Whole-project deletion spans Room, event blobs, workspace files and project-local npm installations; interruption must not leave a partially deleted project available for use.

**Approach:** Ask for confirmation naming the project and owned data. Acquire the runner's admission gate, reject pending work, and persist deletion intent before touching owned storage. Keep an actionable deletion row until cleanup and metadata removal succeed.

## Boundaries & Constraints

**Always:** Pending includes admission, queued/running tasks and approval waits. A deleting project cannot accept tasks, members, instruction writes or new previews. Finish other work normally. Retry from persisted identity using idempotent storage-owner operations.

**Never:** Delete ordinary chats, other projects, provider settings, or the shared Linux runtime. Add no scheduler integration in this story.

</frozen-after-approval>

## Code Map

- `core/.../TaskRunner.kt` owns the admission lock and pending snapshot; `Ports.kt` owns Project and its API.
- `data/.../Dao.kt`, `Repositories.kt`, `Entities.kt`, `Migrations.kt`, `LumynoidDatabase.kt`, and `LumynoidData.kt` own lifecycle persistence, chat/blob cleanup, and project/conversation visibility. Keep the persisted workspace ID, including legacy IDs.
- `app/.../LumynoidApp.kt` wires the runner and `UserlandRuntime.deleteWorkspace`; `previewArtifact` already requires project scope in both app processes. `ConversationScreens.kt`, `MainActivity.kt` and `ChatViewModel.kt` own confirmation, progress and resume.

## Tasks & Acceptance

**Execution:**
- [x] `core/src/main/kotlin/dev/lusalhi/lumynoid/core/{Ports,TaskRunner}.kt` — expose deletion status and action, and reuse one admission lock for project state and membership changes.
- [x] `data/src/main/kotlin/dev/lusalhi/lumynoid/data/` — add a persistent flag and migration 6→7; refuse deleting scope and hide its chats; remove chat blobs before their owning rows, then workspace and installed packages through userland, then project metadata. Keep long cleanup outside admission.
- [x] `app/src/main/` — wire boundary, add a named confirmation and a resume action for marked rows. Disable deletion while related work is pending or changing and report failure without dropping the resume affordance.
- [x] `data/src/test/` and `app/src/androidTest/` — exercise legacy upgrade, contention, interruption, preview rejection, and project isolation through the production graph.

**Acceptance Criteria:**
- Given a project with unfinished work, when Delete is offered or races task admission, then deletion cannot start; another project's tasks do not make it busy.
- Given an idle project, when deletion is requested, then the dialog names the project, chats, logs, instructions, files and packages; cancel preserves the project.
- Given cleanup is interrupted after deletion is marked, when the app restarts, then it rejects new work, members and previews for that project, allows another project to work, and offers Resume deletion.
- Given an interrupted project is resumed, when cleanup completes, then only its chats, logs, instructions, files, packages and metadata are gone; providers and shared runtime remain.

## Implementation Notes

- Room v7 defaults existing projects to active. The runner's admission lock serializes the membership/idle check with the persistent deletion mark. Cleanup releases that lock and excludes duplicate deletions of the same project.
- A marked project remains visible for retry, but its conversations are hidden and scope lookup returns unavailable. Chat cleanup removes blobs before dropping their owner rows; userland removes the recorded workspace and package trees before Room drops project metadata.

## Plan Change Log

## Review Triage Log

- Inline quick review: Room can conflate the deletion mark and final removal. The UI now reconciles selection on successful cleanup or a persisted mark rather than waiting for the intermediate emission.
- Confirmation resolves the current active row and uses the same pending-project state as the list action. The repository rechecks admission before the durable mark. No findings deferred.

## Verification

- `./gradlew --offline :core:test --tests '*TaskRunnerTest' :data:kspDebugKotlin` — 46 runner tests passed; Room exported schema 7.
- `./gradlew --offline :data:testDebugUnitTest --tests '*PersistenceTest' --tests '*LegacyWorkspaceAdoptionTest' --tests '*MigrationTest' --tests '*ProjectDeletionTest'` — 33 tests passed, including 7 deletion scenarios and the 6→7 migration.
- `./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest` — both APKs built.
- Android (Redroid arm64, API 34): `dev.lusalhi.lumynoid.app.ProjectDeletionTest` passed. Exercised named confirmation and cancel, a filesystem failure after the mark, task/member rejection, denial in the separate preview process, and resume after Activity recreation. Confirmed that only the target lost its files, packages, blobs and log; the other project could still preview its artifact, and provider and shared runtime storage were unchanged. Host tests exercised database reopening. Signed handset acceptance stays in Story 8.8.
