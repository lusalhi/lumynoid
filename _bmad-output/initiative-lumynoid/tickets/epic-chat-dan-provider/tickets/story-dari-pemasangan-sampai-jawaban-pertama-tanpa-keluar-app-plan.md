---
title: 'First run: from install to first answer without leaving the app'
type: feature
ticket: '9'
created: '2026-11-20'
status: done
baseline_revision: 'c22bf3b941f6ed7d3bada75895b3d7b24ae4da9b'
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

**Problem:** A first launch with no provider drops a layperson on a bare "Add provider" form with empty URL and name, no hint of what to do, and no required test. After saving, the app reopens the *latest* conversation instead of a fresh one. Other screens do not point back to setup (R1).

**Approach:** Turn the existing no-provider path into a guided first run on the same provider form: a welcome line, OpenRouter preselected, one "Connect and start" action that tests the connection and saves only on success, then opens an empty new conversation. Every screen that needs a provider points to that setup.

## Boundaries & Constraints

**Always:** Reuse the story 4/5 provider form, `ProviderAdminPort` and story 7 `newConversation()`; no new screen, route library or Room change. UI strings in `strings.xml`, English, Material 3 defaults. `:core` stays pure Kotlin. No step names a terminal, a file or documentation; key-finding hints are plain text, not links. A failed test never blocks the user for good: it shows the reason and offers "Save anyway".

**Never:** Bundled default model names (they go stale); notification permission (epic-tugas-terjadwal); Play or sign-in flows; changing the form for users who already have a provider (add, edit and Save stay as they are); a tutorial carousel; analytics.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First launch | No provider saved | Provider form with a welcome line, OpenRouter preselected (name and URL filled), "Connect and start" | — |
| Connect ok | Key and model filled, test passes | Provider saved and active; chat opens as an empty new conversation | — |
| Connect fails | Test returns a failure | Not saved; failure reason shown; "Connect and start" retries; "Save anyway" saves and opens the chat | Reason text as story 4 |
| No model yet | Key filled, model blank | Model list loads and its suggestions show; nothing is saved | Unavailable → type the name |
| Missing field | URL, key or model blank | Field-level hints as today; nothing sent | — |
| Old conversations, no provider | Conversations exist, provider missing | Chat and conversation list both show the setup prompt with its button | — |
| Existing provider | Opens the form to add another | Today's form and buttons, unchanged | — |

</frozen-after-approval>

## Code Map

- `core/.../ProviderSetup.kt` (new) -- `suspend fun connectAndSave(admin: ProviderAdminPort, draft): ConnectOutcome` {`Started`, `TestFailed(error)`, `Incomplete`}: `testConnection`, then `save` only on `Success`. `saveAnyway` is plain `admin.save`.
- `core/src/test/.../ProviderSetupTest.kt` (new) -- fake `ProviderAdminPort`; style of `ProviderPresetsTest.kt`.
- `app/.../ProvidersViewModel.kt` -- `refresh()` already builds `ProviderFormState(makeActive = true)` when no provider exists: preselect `ProviderPresets.all.first()` there. Add `isFirstRun` (form open, `providers.isEmpty()`), `connect(onStarted)` via `connectAndSave`, `saveAnyway(onStarted)`. Reuse `fresh`-result guards of `testConnection`.
- `app/.../ProviderScreens.kt` -- `ProviderFormScreen`: when first run, welcome text, key hint under the preset chips, "Connect and start" replaces Test and Save, "Save anyway" after a failed test. `ProvidersRoute` passes `onLeave`.
- `app/.../ChatViewModel.kt:~120` -- `closeProvider()` keeps `conversationId` (latest). Add `providerReady()` used by first-run completion: sets `providerConfigured`, `newConversation()`.
- `app/.../Screens.kt`, `ConversationScreens.kt` -- chat empty text and list empty text switch to a "set up AI provider first" prompt plus button when `!providerConfigured`; list screen needs `providerConfigured` and `onOpenProvider` params wired in `MainActivity.kt`.
- `app/src/main/res/values/strings.xml` -- new strings.
- Do not touch: `data/`, `llm/`, Room schemas, `AgentService`, `LinkPolicy`.

## Tasks & Acceptance

**Execution:**
- [ ] `core/.../ProviderSetup.kt` + test -- test-then-save helper; cases: success saves, failure never saves, incomplete never saves -- R1
- [ ] `app/.../ProvidersViewModel.kt` -- preselect first preset, `connect`, `saveAnyway`, blank-model path calls `loadModels` -- R1
- [ ] `app/.../ProviderScreens.kt`, `res/values/strings.xml` -- first-run form copy and buttons -- R1
- [ ] `app/.../ChatViewModel.kt`, `Screens.kt`, `ConversationScreens.kt`, `MainActivity.kt` -- new conversation after first provider; setup prompt on both screens -- R1

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` runs, then it passes.
- Given a debug APK on a phone with app data cleared, when the tester opens it, picks a provider, pastes a key, picks a model and taps "Connect and start", then an empty conversation opens and the first message gets a streamed answer, with no terminal, browser or documentation needed.
- Given a wrong key on first run, when the tester taps "Connect and start", then the reason shows, nothing is saved, and the app still shows the setup form after a restart.

## Implementation Notes

Built by a Sonnet subagent; the review patch by the same subagent. Verified after the patch and a /simplify pass: `:core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` pass (`ProviderSetupTest` 4, `FirstRunTest` 3, 0 failed); no schema file changed.

Where the build differs from the Code Map:
- `ProvidersRoute` got a separate `onFirstRunDone` parameter instead of reusing `onLeave`: `onLeave` is also the back-from-list action and must keep the current conversation.
- `firstRunForm()` is an `internal` top-level function so `FirstRunTest` can reach it. The first-run form hides the "Use for chat" switch (the first provider is always active) and an empty chat with no provider hides the bottom banner, since the centred prompt already has the button.
- `SetupBanner` and `SetupPrompt` live in `ConversationScreens.kt` and are shared by the chat and the list.

Not done here, by scope or environment:
- Not run on a device or emulator. `:app` has no Compose or ViewModel tests, so these are compiled but untested: the connect flow in `ProvidersViewModel` (blank-model path, stale-result handling, Save anyway), the setup prompts, the tap on the prompt's button, and the new conversation after the first provider.
- The ticket's verify step (a fresh tester installs the APK and gets a first answer unaided) and the wrong-key case belong to the human tester.
- Known and left: edits made while "Connect and start" runs are dropped when it succeeds (triage row 1); the older `refresh()` race that can recreate the form from a stale empty overview.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run through `omp` with gpt-6.1-sol (read and grep tools) on the diff and the plan. 2 findings: high 0, medium 1, low 1, false 0, maybe-false 0. No intent_gap or bad_plan; no loopback. One patch sent to the implementation subagent.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Edits made while "Connect and start" runs are discarded when the connect succeeds | low | reject | Real but the saved provider is exactly the settings that were tested, the window is one test request, and a fix needs a freshness guard or disabled fields (more than a direct correction). The failure path already drops stale results. |
| 2 | The empty-chat setup button sits under the full-size `LazyColumn` in the same `Box`, so taps do not reach it | medium | patch | Real: a scrollable sibling drawn later takes pointer hit-testing from the sibling below; the old empty text was not interactive, the new button is. The "AI provider" header button still worked, so users were not stuck. `LazyColumn` now composes only when there are items. Not testable on the JVM (`:app` has no Compose tests); checked by the manual run. |

## Design Notes

- First-run is not a new screen: `ProvidersViewModel.refresh()` already sends a provider-less app straight to the form, so the work is copy, one combined action, and where the flow ends.
- "Save anyway" exists because a provider can fail the tiny test request yet chat fine (odd model names, rate limits at test time).
- `:app` has no ViewModel or Compose test setup (story 7); the testable decision is kept in `:core`, UI parts are verified by compile and the manual check.

## Verification

**Commands:**
- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest` -- expected: all pass, including `ProviderSetupTest`
- `./gradlew :app:assembleDebug :app:checkDependencyPolicy` -- expected: BUILD SUCCESSFUL
- `git status --short data/schemas` -- expected: no output

**Manual checks (if no CLI):**
- No device on the build host: the ticket's verify step (a fresh tester installs the APK and gets a first answer unaided, plus the wrong-key case) belongs to the human tester.
