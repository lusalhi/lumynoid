---
title: 'Tracer bullet: first chat answer streams through every layer'
type: feature
ticket: '1'
created: '2026-11-14'
status: done
baseline_revision: 'faba4fcd00f1c55f917c3c6516711f6b3c87bba0'
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

**Problem:** The repo has no application code. Stories 4–10 all build on a first end-to-end path, and until one exists the hexagonal boundaries (AD-1) and the single execution path (AD-2) are only paper.

**Approach:** Build the thinnest vertical slice: a Gradle multi-module skeleton (`:core`, `:llm`, `:data`, `:app`) where one settings form feeds one chat message through `TaskRunner.submit` in `AgentService`, an OpenAI-compatible Ktor adapter behind a pass-through `LlmRouter`, and a Room v1 event log, shown as streaming text. CI builds the debug APK and runs the `:core` JVM tests.

## Boundaries & Constraints

**Always:** Follow the spine: `:core` is pure Kotlin with no `android.*`, `androidx.*`, Ktor or Room (AD-1); adapters depend only on `:core`; `:app` wires everything in a manual composition root. Stack versions are those pinned in the spine. Only `TaskRunner` appends events, and streaming deltas are not persisted (AD-4). The API key goes through `SecretPort` (AES-GCM, Keystore alias `lumynoid_secret_v1`) and never reaches an event, log or request body except the auth header (AD-15). Room is the single `RoomDatabase` with `exportSchema` committed at v1 (AD-19). Debug uses `applicationIdSuffix ".debug"` and a committed shared debug keystore; `applicationId` is `dev.lusalhi.lumynoid`; `allowBackup="false"` with `dataExtractionRules`; `dependenciesInfo.includeInApk = false` (AD-25). UI strings live in `strings.xml`, in English.

**Decisions (human, 2026-11-14):** Keep the full plan, no split. The implementer creates a **private** GitHub repo `lusalhi/lumynoid` with `gh repo create` and pushes branch `initiative-lumynoid` so CI can run; Actions stay enabled, nothing is made public.

**Never:** No tools, tool registry, approval gate or cards (epic-agent-shell). No foreground promotion, notification or notification permission (story 8). No conversation history in the request: one turn only (story 7). No provider list, preset, model list or connection test (story 4), no markdown rendering (story 6), no Anthropic adapter (story 5). No Google/Firebase dependencies, no DI framework, no copied Operit code. Do not make the repo public, create releases or tags, or add signing keys or secrets to GitHub.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Streaming answer | Valid base URL, key, model; user sends one message | Text grows incrementally in the chat screen; on completion `UserMessage`, `AssistantMessage` and `TaskFinished` are in the event log | No error expected |
| Process death | App force-closed after the answer | On reopen, the message and answer render from Room | No error expected |
| Bad key | Provider answers HTTP 401/403 | Task ends `TaskFailed` with a typed `AuthFailed`; chat shows a plain-language error | Typed `LlmError`; no exception crosses the port |
| Unreachable | No network or timeout | `TaskFailed` with `Network`; chat shows a plain-language error | Typed `LlmError` |
| Missing config | Send with empty URL, key or model | Send is blocked and the form says what is missing | No task is submitted |
| Secret hygiene | Any run | The key string appears in no event, logcat line or exported schema | N/A |

</frozen-after-approval>

## Code Map

Greenfield: nothing exists besides `.gitignore`, so every path below is created. Host checks: JDK 17, `/opt/android-sdk` (platforms up to 36, build-tools 36, NDK 28.2; platform 37 installable via `sdkmanager`), x86-64 `aapt2` runs through binfmt on this aarch64 host, no device, no KVM. All pinned stack versions resolve from Google Maven / Maven Central.

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*` -- Gradle 9.6.0 wrapper, version catalog with the spine's Stack table, four modules.
- `core/` -- `TaskRunner`, `TaskRequest{origin, conversationId, workspaceId, input}`, `TaskEvent` (sealed, `@SerialName` snake_case, `Unknown`), ports `LlmPort`/`LlmChunk`/`LlmError`, `SecretPort`/`SecretRef`, `EventRepository`, `ConversationPort`; JVM tests with a scripted fake `LlmPort`.
- `llm/` -- OpenAI Chat Completions streaming adapter (Ktor, SSE `data:` lines, kotlinx.serialization), pass-through `LlmRouter` implementing `LlmPort`; contract test against Ktor `MockEngine`.
- `data/` -- one `RoomDatabase` v1 (conversation, task, task_event), `schemas/` committed, Keystore-backed `SecretPort`, repository implementations.
- `app/` -- `AgentService` hosting `TaskRunner`, composition root, provider form, chat screen, `AndroidManifest.xml` with `${applicationId}`, `debug.keystore`.
- `.github/workflows/ci.yml` -- on push and PR: `:core:test`, `:app:assembleDebug`; no signing key access.

## Tasks & Acceptance

**Execution:**
- [ ] `gradle/`, `settings.gradle.kts`, root and module `build.gradle.kts` -- scaffold the four modules with the pinned stack; install platform 37 if absent -- AD-1 boundaries enforced by module dependencies
- [ ] `core/` -- ports, `TaskEvent`, `TaskRunner` (FIFO, one RUNNING, `submit`/`cancel`), streaming into a hot `StateFlow` and persisting `UserMessage`, `AssistantMessage`, terminal event -- single execution path
- [ ] `core/src/test` -- scripted fake `LlmPort` tests for each I/O row reachable in `:core` (success, `AuthFailed`, `Network`, serialization round-trip including `Unknown`) -- JVM-only proof of AD-1
- [ ] `llm/` -- OpenAI-compatible SSE adapter, error mapping to `LlmError`, pass-through `LlmRouter`; `MockEngine` contract tests (stream, 401, network failure, key only in header) -- AD-11, AD-12
- [ ] `data/` -- Room v1 with exported schema, repositories, `SecretPort` AES-GCM impl -- AD-4, AD-15, AD-19
- [ ] `app/` -- `AgentService`, composition root, provider form (base URL, key, model; save via `SecretPort`), chat screen observing the stream and the event log, manifest and backup rules, committed debug keystore -- AD-2, AD-25
- [ ] `.github/workflows/ci.yml` -- build debug APK and run `:core:test` -- CI gate

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew :core:test :llm:test :app:assembleDebug` runs, then it succeeds and `app/build/outputs/apk/debug` holds an APK with package `dev.lusalhi.lumynoid.debug`.
- Given `:core` sources, when its dependencies are listed, then it has no `android.*`, `androidx.*`, Ktor or Room.
- Given the exported Room schema, when inspected, then `data/schemas/**/1.json` exists and is committed.

## Implementation Notes

Implemented directly in the main session (no subagent was requested). Platform `android-37.0` and build-tools 37 were installed into `/opt/android-sdk`; the Gradle 9.6.0 wrapper was generated from a downloaded distribution.

Additions beyond the plan's file list, each forced by the slice itself:
- `LlmChunk.Failed(error)` carries typed errors through the `Flow` (exceptions must not cross ports); the spine's chunk list had no error variant.
- `ConversationPort.latest()`, `ProviderAdminPort.current()` and `ProviderSaveResult` (`SAVED`/`INCOMPLETE`) so the missing-config rule is enforced and tested below the UI.
- `TaskRunner` catches non-cancellation exceptions per task and records `TaskFailed(SERVER)`; without it a failed write or a Keystore read error would end the queue and crash the process (test added, seen failing without the fix).
- `workspaceId` is a fresh UUIDv7 per message for now (AD-23 keeps it separate from `conversationId`; real workspaces are epic-agent-shell).
- `:data` has a host-JVM Room test on `sqlite-bundled-jvm` (test-only). It closes and reopens a real SQLite file, standing in for the process-death row; CI runs it as `:data:testDebugUnitTest`.
- Debug keystore: alias `androiddebugkey`, password `android`, SHA-256 `21:39:0C:41:EE:6C:2A:A6:C2:DA:00:8F:37:CA:5A:FE:A1:CF:0C:94:2C:1C:97:4D:F1:F5:69:CA:1E:4D:37:A0`.

Not done here, by scope or environment:
- Not run on a device or emulator: the streaming UI, the provider form, Keystore encryption and the service binding. The debug APK installs-ready but is unexercised.
- Repo creation, push and the first CI run (authorised in Decisions) happen after review.
- Deferred spine rules this story does not need: event payload blob offload above 256 KB (AD-19), crash-recovery `INTERRUPTED` sweep at start (AD-22), launcher icon.
- The APK carries one extra permission, `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, merged in by AndroidX; the AD-24 allowlist check in story 2 must account for it.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens (run externally, findings in `story-jawaban-chat-pertama-mengalir-lewat-semua-lapisan-review.md`). 24 findings: high 0, medium 4, low 19, false 1, maybe-false 0. No intent_gap or bad_plan; no loopback. Patches applied by the main session (no implementer subagent existed to re-engage); full verification re-run after: 28 tests, 0 failed.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| A2, E1 | `cancel()` racing dequeue/registration loses the cancel | medium | patch | Real: `cancelled.remove` check and `running.value =` were separate steps. Story 8's Stop button would intermittently fail. Worker now registers the job first (ATOMIC start) then re-checks the id; `cancel()` adds first then reads `running`. Queued-cancel test added. |
| A9 | Scheme-less URL saved, later shown as a server error | medium | patch | Real: only blankness was checked, so `openrouter.ai/api/v1` could not become a request. `ProviderAdmin` now prepends `https://` when no `://` is present. Residual: other malformed URLs still surface as a provider error (inline URL validation needs a new result variant and string; not done). |
| A10 | EOF treated like `[DONE]`; truncated answer recorded as finished | medium | patch | Real: `readLine() ?: break` then `Done`. Now `Done` only after `[DONE]` or a `finish_reason`; otherwise `Failed(Network)`. Three adapter tests added. Risk: a server sending neither marker now fails every answer. |
| E6 | Corrupt/short secret file throws across `SecretPort` | low | patch | Real, breaks the spine's no-exceptions-across-ports rule. `get()` returns null on IO, bad record length, crypto or provider errors. Not unit-testable on the JVM; compiled only. |
| A7 | Non-atomic secret write can leave a truncated file | low | patch | Real but narrow. Now written through `AtomicFile`. Not exercised on a device. |
| A4 | Live bubble and saved message can both show briefly | low | patch | Real: the event can be observed before `finally` clears `live`. The view model hides the live bubble when an `AssistantMessage` for that task exists. |
| E2 | `emptyFlow()` stalls `combine` until the service connects | low | patch | One-line direct fix: `flowOf(null)`. Practical impact small (same-process binding connects in ms). |
| V1 | No assertion that `live` grows while streaming | low | patch | Gap confirmed. `liveAnswerGrowsWhileStreamingAndClearsWhenDone` added. |
| V2 | Empty-answer branch untested | low | patch | Gap confirmed. `anAnswerWithNoTextFailsInsteadOfSucceeding` added. |
| V3 | Error frame after partial text untested | low | patch | Gap confirmed. `anErrorFrameAfterPartialTextFailsWithoutDone` added. |
| V5 | Key whitespace trimming untested | low | patch | Gap confirmed. `providerAdminTrimsWhitespaceAroundAPastedKey` added. |
| A1, E4 | Port calls in `submit` / view model are unguarded; a Room or Keystore failure crashes the app | low | reject | Real but needs disk-full or an OEM Keystore fault, and the fix adds typed results plus new UI state. Accepted risk for the tracer; revisit with story 4's connection test. |
| A3 | `current()` reports a key as saved when only the config row exists | low | reject | Needs a lost secret file with intact Room data; the resulting message ("provider did not accept your key") still points the user to the right screen. Fix needs a new port status. |
| A5 | CI does not check app classpath or licences (AD-17) | low | reject | Out of scope by intent: ticket 1.2 owns the AD-17 gate; this story's CI is build + `:core` tests. |
| A6 | `Routed` is dropped, no `ProviderSwitched` event | low | reject | No switching exists with a single provider; epic-hemat-kuota adds rotation and the event. Fix adds an event subtype. |
| A8, E3 | Two first sends can create two conversations | low | reject | Needs a second message typed within milliseconds of the first (Send clears the draft). Fix needs a mutex. |
| E5 | `first()` waits forever if the service disconnects | false | reject | `onServiceDisconnected` fires only when the service's process dies; this service is in the app's own process, so the app is gone too. |
| E7 | Secret replaced, then config save fails | low | reject | Needs a Room write failure between two writes; fix needs versioned refs and cleanup. |
| E8 | SSE error frames always map to `Server` | low | reject | Only loses wording; quota/rate-limit semantics belong to epic-hemat-kuota's router. |
| E9 | Double persistence failure swallowed | low | reject | Needs two consecutive DB failures; nothing more can be recorded when the log itself is failing. |
| V4 | `ProviderFormState` key-saved gate untested | low | reject | Cosmetic if wrong (re-enter the key); would need new test infrastructure in `:app`. |

## Design Notes

- The provider form stores base URL and model in the same Room DB (one `provider_config` row) and the key as a `SecretRef`; story 4 migrates this to multiple providers at schema v2.
- `AgentService` here is a started service hosting `TaskRunner` without `startForeground`; story 8 adds the foreground promotion on the same class.
- The fake `LlmPort` lives in `:core` test sources and is the only LLM used by CI.

## Verification

**Commands:**
- `./gradlew :core:test :llm:test` -- expected: all tests pass
- `./gradlew :app:assembleDebug` -- expected: BUILD SUCCESSFUL, debug APK produced
- `./gradlew :core:dependencies --configuration runtimeClasspath | grep -Ei 'android|ktor|room'` -- expected: no output

**Manual checks (if no CLI):**
- No device or emulator is available on the build host (checked: no Android container or image in Docker, no `/dev/kvm`, empty `adb devices`). If an emulator becomes reachable through `adb`, run the same flow there. Otherwise, on a phone with the debug APK, enter an OpenRouter URL, key and model, send one message and watch the answer stream; force-close and reopen and confirm both messages remain. This is the ticket's verify step and belongs to the human tester.
