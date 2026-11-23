---
title: 'Hapus percakapan tanpa menghapus file proyek'
type: feature
ticket: '5'
created: '2026-11-22'
status: done
baseline_revision: 'ec701cfe324727de575aa1608fd976a6c52c6041'
route: oneshot
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

**Problem:** Story 8.1 already provides chat-only cleanup and the shared admission gate, but the confirmation describes shared Project data even for ordinary chats, and existing race coverage only proves submission delays, not rejection when deletion wins.

**Approach:** Keep the existing `TaskRunner` gate and Room cleanup. Use the selected conversation’s persisted project membership to choose confirmation copy. Keep Delete disabled from the pending-task state both in the menu and in an open dialog. Exercise both gate orderings and verify that only the target’s history, log and blobs disappear while the sibling can still use shared files and packages. Whole-project deletion is out of scope.

</frozen-after-approval>

## Implementation Notes

- The menu keeps Delete visible but disabled while the runner reports unfinished work or has not recovered. Confirmation resolves the selected ID from the current list rather than using a stale row after legacy adoption.
- The runner tests suspend task recording to verify deletion loses after admission, then suspend cleanup to verify submission loses after deletion. Production admission and cleanup are unchanged.

Oneshot: the storage boundary and dependency wiring already exist; the production delta is in the conversation list and strings, with focused coverage at the runner and Android UI boundaries.

## Plan Change Log

## Review Triage Log

- Inline quick review: verified that both Delete controls use the same pending state, an absent row cannot be confirmed, and the repository independently goes through the runner gate before removing chat-owned storage. No findings deferred.

## Verification

- `./gradlew --offline :core:test --tests '*TaskRunnerTest' :data:testDebugUnitTest --tests '*PersistenceTest' :app:assembleDebug :app:assembleDebugAndroidTest` — admission, deletion, and chat-only retention checks pass; builds the Android surface.
- Android smoke: a pending project chat cannot be deleted; after it becomes idle, the project confirmation identifies what is removed and retained. Confirm and check that the sibling remains present with its own log and shared files. An ordinary chat confirmation does not mention Project data.

**Host:** 46 runner and 17 persistence tests passed. Both debug APKs built; the app APK was rebuilt after the dialog was bound to the current row.

**Android (Redroid arm64, API 34):** Tapping Delete on a chat with a running request did not open confirmation. After Stop, the dialog named the retained data; confirmation removed the target conversation, tasks, events, and blob directory. The sibling’s action log opened in Compose, and the shared workspace file and installed-package fixture were unchanged. The ordinary-chat dialog omitted Project data and Cancel kept the chat. Signed handset acceptance remains in Story 8.8.
