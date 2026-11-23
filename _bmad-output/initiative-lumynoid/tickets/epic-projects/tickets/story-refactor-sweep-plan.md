---
title: 'Refactor sweep'
type: refactor
ticket: '7'
created: '2026-11-22'
status: done
baseline_revision: '8082eedfeb056f0a187d0e82eb409b701b40088e'
route: oneshot
route_source: auto
risk: low
review: quick
review_source: pinned
lenses_ran: []
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Stories 8.2 and 8.6 require workspace resolution and individual chat cleanup to reject pending adoption and deleting projects. `RoomConversationPort` restates the same availability decision in both paths. Stories 8.5 and 8.6 require each Delete confirmation to remain governed by the same current state as its list action; `ConversationScreens.kt` spells out each predicate twice.

**Approach:** Centralize both rules at their existing call sites. Keep the stored scope decision, missing-chat deletion semantics, admission lock, cleanup ordering, active-row lookup, Resume behavior, UI copy and Room schema unchanged. Do not include other debt or signed handset acceptance (8.8).

</frozen-after-approval>

## Implementation Notes

Oneshot: two files; private helpers only. In `data/.../Repositories.kt`, share the availability predicate without constructing project scope for deletion. In `app/.../ConversationScreens.kt`, use one predicate per screen for its row and confirmation. A missing chat remains an idempotent delete, unlike a present but unavailable chat. A marked project retains the existing Resume action.

- The Room helper checks the persisted row only. The separate missing-row branch remains an idempotent delete; a present row with pending adoption or without active project scope still rejects deletion.
- Each screen uses the same predicate for its row and its dialog. The dialog still resolves the current row, and the Resume action for marked projects is unchanged.

## Plan Change Log

## Review Triage Log

- Inline review: no findings. Checked that plain chats still resolve, pending or deleting scopes stay unavailable, and a missing row still makes deletion idempotent. Both active delete controls use the same current predicate; marked projects bypass it only for the existing Resume action.

## Verification

- Given independent project and ordinary chats, when tasks run, then project files can be shared without sharing history, and ordinary chats do not gain workspace scope.
- Given a pending legacy chat or a deleting project, when scope is resolved or a single chat is deleted, then the operation stays blocked until permitted. When adoption or scoped cleanup completes, unrelated data remains available.
- Given a list action and its open confirmation, when work becomes pending, then both deny deletion; normal and Resume flows retain their existing behavior.
- Run CI build-gate equivalents locally, then run `ProjectChatSubmissionTest`, `PreviewNavigationTest`, and `ProjectDeletionTest` on Redroid to cover routing, stored preview scope and cleanup.

**Host:** 209 JVM tests passed; four skipped. APK and instrumentation builds, dependency policy, core runtime classpath, APK alignment/signature, and rootfs fixture verification passed locally. Schema-claim script self-test passed; the pull-request-only schema comparison did not run.

**Android:** `ProjectChatSubmissionTest`, `PreviewNavigationTest`, and `ProjectDeletionTest` passed on Redroid arm64 API 34 (3/3): project chat routing, event-bound offline preview, and interrupted deletion with isolated cleanup and resume. Signed APK handset acceptance remains in 8.8.
