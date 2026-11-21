---
title: 'Chat menampilkan aktivitas dan persetujuan tool coding'
type: feature
ticket: 8
created: '2026-11-21'
status: built
baseline_revision: 7801c9fe3e93e762321c81fec92d26458e52e936
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

Connect chat to the existing coding tools. Show what the agent requests and what actually runs, and let the user approve or deny a risky call from its card. Keep project files across turns in the same conversation.

## Boundaries & Constraints

**Always:** Use the existing runner, event log, and approval gate. Only app-generated typed events create cards; tool data is plain text, not Markdown or a link. A notification opens the exact card and never submits a decision. Rootfs transfer can start only through the approved setup call. Bundled npm and built-in scripts stay offline.

**Never:** Add a second execution path, grant reusable approval, replay after process death, expose a shell, or add the action log or preview UI (2.9/2.10).

## I/O & Edge-Case Matrix

| Scenario | Expected behavior |
|---|---|
| Tool reply followed by results | Show a localized activity title and correlated result, target, exit code and artifacts when present. No result is not success. |
| Rootfs already installed | Normal offline operations do not prompt for a download or fetch rootfs. |
| Waiting, expired or interrupted | Same-process reopen shows the same pending card. Resolved, expired and terminal requests are non-actionable; repeated taps cannot dispatch twice. |
| Forged assistant Markdown or tool output | No card or buttons from strings. A structured risky follow-up still waits for approval. |

</frozen-after-approval>

## Code Map

- `app/.../LumynoidApp.kt` builds both provider adapters and a process-owned runner, but passes userland only for cleanup. `ChatViewModel.send` generates a fresh workspace ID per turn; the existing conversation UUID satisfies `CodingWorkspace` and task storage.
- `TaskRunner.publishApproval/resolveWaiting` own durable approval and resource release; `AgentService` watches `busy` separately. `ChatViewModel` observes Room events, and `Screens.kt` hides tool events today.
- `UserlandRuntime` already registers `shell_runtime_setup` as `EGRESS`; `RootfsInstaller` checks the pinned size and digest and skips transfer on an installed runtime. Other tools never install rootfs implicitly.

## Tasks & Acceptance

- [x] `app/src/main/kotlin/dev/lusalhi/lumynoid/app/{LumynoidApp,ChatViewModel}.kt`, `userland/src/main/kotlin/dev/lusalhi/lumynoid/userland/{UserlandRuntime,CodingRuntime}.kt` — register the runtime as provider and release hook, use conversation ID for workspace, and make unmet setup discoverable through the tool descriptions/results. Keep the setup call gated.
- [x] `app/src/main/kotlin/dev/lusalhi/lumynoid/app/{Screens,ToolCards,ChatViewModel,MainActivity}.kt`, `app/src/main/res/values/strings.xml` — project request/result/approval events into cards, correlate in log order within a task, map `UiText` keys centrally with a generic fallback, and show operation data as expandable labeled fields, including the proposed overwrite. Resolve only from a current card via `ApprovalPort`.
- [x] `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt`, `app/src/main/kotlin/dev/lusalhi/lumynoid/app/{LumynoidApp,ApprovalNotifications,MainActivity}.kt`, `app/src/main/AndroidManifest.xml` — observe committed live approvals independently of the FGS, post and retire separate notifications, and route an explicit immutable pending intent through a non-exported entry point. Validate task/event identity and scroll to the card; the exported launcher must not accept external approval routes.
- [x] `core/src/test/kotlin/dev/lusalhi/lumynoid/core/TaskRunnerTest.kt`, `app/src/test/kotlin/dev/lusalhi/lumynoid/app/ToolCardsTest.kt`, `README.md` — cover stale/repeated decisions, ordered result matching and forged text where not covered; document setup and verify on Android.

**Acceptance Criteria:**
- Given an arm64 app with a fixture provider, when chat creates JSON, installs the bundled npm package and runs the formatter, then the user sees the cards, artifact and final answer. A follow-up reads it in the same conversation; another conversation cannot.
- Given a pending overwrite, when the user leaves chat until the service stops without killing the process and taps its notification, then the original card opens. Approving produces one result; denying a separate request produces no write.
- Given a fresh runtime, when chat requests setup, then the card shows the pinned version and source. Denial leaves the source untouched; approval allows the pinned download and verified install.

## Implementation Notes

- Conversation UUIDs also identify their private workspaces; no Room migration. The production rootfs URL, digest checks and per-call approval gate are unchanged.
- Approval notifications follow the runner's committed pending events, not the service. The private activity passes a one-shot route in process; the launcher ignores intent-supplied approval data and the ViewModel checks the live task/event pair.
- Backgrounding unbinds the activity only after an in-flight submission or approval decision hands off to the runner.
- Chat correlates tool results to typed request rows. Tool details use plain text. Chat previews long output; the complete action log and artifact previews remain in 2.9 and 2.10.

## Plan Change Log

## Review Triage Log

Quick review: four medium findings patched, two limitations recorded, and one remaining device acceptance gate confirmed.

| Finding | Verdict / route | Evidence and action |
| --- | --- | --- |
| Granting notifications after an approval is pending does not post it | Medium / patch | The observer now combines the live request set with permission/channel state, refreshed on resume and permission result. |
| Notification route can carry an unsent draft into a different workspace | Medium / patch | Compose keys draft save state by conversation identity. |
| Expired request can remain actionable until the expiry event commits | Medium / patch | The runner supplies a monotonic deadline; the card disables its controls at the deadline, including after resume. The runner still makes the actual decision. |
| Formatter can exit zero without publishing an artifact | Medium / patch | The collapsed card explicitly says no output file was published. |
| Text-only provider models can pass connection test but reject tool-bearing chat | Medium / limitation | The connection check exercises text; this story registers structured tools on arm64. The model requirement is documented; a text-only mode is not part of this tracer. |
| Deleting a conversation does not reclaim its workspace or npm install | Medium / defer to 2.9 | Ticket 2.9 already owns `ConversationPort.delete` cleanup for blobs and workspace. |
| No real handset or released rootfs path exercised | High / acceptance pending | The existing release tag has no rootfs asset. The loopback fixture verifies the gated transfer against the pinned bytes, not GitHub delivery on API 37. |

## Verification

- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :userland:assembleDebugAndroidTest` — tests pass and APKs build. Run `UserlandInstrumentedTest` on the connected arm64 device for setup and cleanup regressions.
- Run the app on Android with scripted HTTP provider responses and the exact pinned rootfs in a test-only environment; no production source override. Check the card, notification and workspace flows above, including zero transfers on denial. The public `v0.2.1` tag predates the rootfs asset; do not treat its download failure or a Redroid API 34 pass as API 37 acceptance. Repeat the tracer on an arm64 API 37 handset and record observed results.
- Gradle verification passed: 162 tests passed, four existing host-assumption Markdown tests skipped, and both debug APKs built. The first Android instrumentation launch on the existing Redroid device failed before test execution with `DeadObjectException`.
- The isolated Redroid arm64 API 34 fixture used a local scripted OpenAI-compatible endpoint and the production-pinned archive served over loopback; neither endpoint override is in the repository build. In the actual chat UI, declining setup made zero transfers, approving setup made one transfer, and create, bundled npm, format, generated artifact, final answer, and a follow-up read were observed.
- On the isolated emulator, `UserlandInstrumentedTest` returned `OK (8 tests)`. In the chat tracer, declining an overwrite preserved bytes, a follow-up read saw the last approved bytes, and a new conversation could not read the original artifact.
- The emulator exposed a chat keyboard covering the notification's target. After the fix, the final APK dismissed the keyboard and opened the exact approval from another conversation; a repeat tap refocused the card. Android rejected an external launch of the private activity. The service exited while waiting, an approval followed by immediate backgrounding dispatched once, and process restart showed interrupted with no replay. The app unit test and debug build passed again after the fix.
- Final Gradle verification passed again after quick-review patches: 162 passed, four skipped, and the debug and instrumentation APKs built. `apksigner verify` accepted the app debug APK.
- On Redroid API 34 after the review fixes, granting notification permission posted an existing pending approval; opening it did not import a draft from another conversation; the formatter card showed that no output file was published despite process exit code zero.
- Physical arm64 API 37 acceptance remains pending; all device evidence above is from arm64 Redroid API 34.
