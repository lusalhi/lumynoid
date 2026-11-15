---
title: 'Add OpenAI-compatible providers and test their connection'
type: feature
ticket: '4'
created: '2026-11-15'
status: built
baseline_revision: '8cfa475477d29d36916e94bf886939fec7a64644'
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

**Problem:** Story 1 saved exactly one provider through a bare form. Users cannot pick a preset, keep several providers, see the provider's models, learn whether a key works before chatting, or choose which provider and model the app uses (R2).

**Approach:** Replace the form with a provider screen backed by `provider_config` + `model_profile` tables in Room schema v2 (first `Migration`, tested with `MigrationTestHelper`). `ProviderAdminPort` gains `listModels` and `testConnection`; one provider and model is active app-wide, read by `LlmRouter` on every message.

## Boundaries & Constraints

**Always:** AD-1 module boundaries; `:core` stays pure Kotlin. Keys only via `SecretPort`/`SecretRef`, never in events, logs, DB rows, schema or messages shown to the user (AD-15). Failures are typed `LlmError`, never exceptions across ports (AD-11); the UI reaches `:llm` only through `ProviderAdminPort` (AD-23). Presets: OpenRouter `https://openrouter.ai/api/v1`, Groq `https://api.groq.com/openai/v1`, Google Gemini `https://generativelanguage.googleapis.com/v1beta/openai`, OpenAI `https://api.openai.com/v1`, plus custom URL. Schema v2 exported and committed, explicit `Migration(1, 2)` that carries the story-1 provider, its model and its active status forward; no destructive fallback (AD-19). UI strings in `strings.xml`, English; Material 3 defaults, no `bmad-ux` design.

**Never:** Anthropic adapter or preset (story 5); rotation, `RouterState`, `fetchQuota`, `supportsTools` filtering (epic-hemat-kuota); conversation history (story 7); per-conversation model choice; deleting providers; a first-launch onboarding screen (story 9); a streaming-cancel button (story 8). Do not edit `data/schemas/**/1.json`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Add from preset | Pick OpenRouter, paste key | URL prefilled; models fetched from `/models`; saved provider appears in the list; the first provider saved becomes active | — |
| Add custom URL | Name, `host/v1` or full URL, key | Scheme `https://` added when missing; saved like a preset | Blank URL or key (new provider) → `INCOMPLETE`, fields flagged |
| Model list | Provider has no `/models`, returns an error, or is empty | Model field becomes manual entry | Never blocks Save or Test |
| Test succeeds | Right key, chosen model | "Connected" shown | — |
| Test fails | Wrong key / unreachable / rate-limited / quota / 5xx | "Failed" plus plain reason from `LlmError` (`AuthFailed` → key rejected, `Network` → cannot reach, …) | Reason text never contains the key or the provider's response body |
| Switch active | Select another provider or model | Next submitted message uses it; an answer already streaming finishes on the old one | — |
| Upgrade from v1 | DB v1 with one `provider_config` row | v2 holds that provider, its model, and it is active; key ref unchanged | Migration test fails on any lost column |
| Test with saved key | Editing a saved provider, key field blank | Test uses the stored key | No stored key → `INCOMPLETE` |

</frozen-after-approval>

## Code Map

- `core/.../Ports.kt` -- holds story-1 `ProviderConfig{baseUrl, model, apiKey}`, `ProviderConfigStore`, `ProviderInput`, `ProviderAdminPort{current, save}`, `ProviderSaveResult`. Reshape: config loses `model`; add `name`, `presetId`; add `ModelProfile{providerId, modelId, supportsTools: Boolean? = null}`, `ProviderPreset`/`ProviderPresets`, `ModelListResult`, `ConnectionTestResult{Success, Failure(LlmError)}`; `listModels` and `testConnection` on the port.
- `llm/.../ProviderAdmin.kt` -- trims key, `withScheme`, keeps old key on blank (reuse all three). Add `listModels`/`testConnection`, and `select(providerId, modelId)`.
- `llm/.../OpenAiCompatibleAdapter.kt` -- `stream(config, apiKey, request)` hard-wires `config.model`; pass the model explicitly. Add `GET {base}/models` (Bearer, parse `data[].id`, map failures with `toError`). Test via `stream`: success on first `TextDelta` or `Done`, then stop collecting; do not use `/models` for the test, since OpenRouter lists models without a key.
- `llm/.../LlmRouter.kt` -- reads `configs.active()` per `stream` call, so a switch applies from the next message with no change to `TaskRunner`; it emits `Routed(provider.id, model)`.
- `data/.../{Entities,Dao,Repositories,LumynoidDatabase,LumynoidData}.kt` -- v1 entity `ProviderConfigEntity{base_url, model, secret_ref}`, `RoomProviderConfigStore`, `.databaseBuilder(...)` with no migrations. Add tables `provider_config`, `model_profile`, `active_model` (single row), `MIGRATION_1_2` (copy the old row, then drop its `model` column), `version = 2`, `.addMigrations(MIGRATION_1_2)`.
- `data/src/test/.../PersistenceTest.kt` -- host-JVM Room on `BundledSQLiteDriver`; its `providerConfig…` test uses the v1 shape and must move to v2. New `MigrationTest.kt` beside it.
- `data/build.gradle.kts`, `gradle/libs.versions.toml` -- add `androidx.room3:room3-testing-jvm:3.0.3` (test-only; JVM `MigrationTestHelper(schemaDirectoryPath, databasePath, driver, databaseClass)`, schema dir `schemas`). Test-only, so the AD-17 release-classpath gate is unaffected.
- `app/.../{ChatViewModel,Screens,MainActivity}.kt`, `res/values/strings.xml` -- `ProviderFormState` and `ProviderScreen` are the story-1 single form with a `Screen.PROVIDER` route and `providerConfigured` gate. Turn it into list (cards, active marker, "Use for chat", Add) plus edit form (preset chips, name, URL, key, model picker or text field, Test, Save). Keep the missing-field pattern (`showMissing`).
- `app/.../LumynoidApp.kt` -- composition root; no new wiring beyond constructor changes.
- `.github/scripts/check_schema_claims.py` -- passes only if `2.json` is added and `1.json` is untouched.

## Tasks & Acceptance

**Execution:**
- [ ] `core/.../Ports.kt`, `core/src/test` -- reshape provider types, add presets, results and `ModelProfile`; test the preset table (four entries, https, no trailing slash) -- AD-11, AD-23
- [ ] `llm/.../OpenAiCompatibleAdapter.kt`, `llm/.../ProviderAdmin.kt`, `llm/.../LlmRouter.kt` -- model parameter, `listModels`, `testConnection`, `select`, router reads the active pair -- R2
- [ ] `llm/src/test` -- `MockEngine` tests for every I/O row reachable here: list parses, list 404 and empty → manual, test success, 401 → `AuthFailed`, network, 429, 402, key only in the `Authorization` header, key absent from every failure value, blank key keeps the stored key, first saved provider becomes active, switch changes the next stream's model -- AD-15
- [ ] `data/...` -- v2 entities, DAO, stores, `MIGRATION_1_2`, committed `schemas/.../2.json` -- AD-19
- [ ] `data/src/test/.../MigrationTest.kt` -- `MigrationTestHelper`: create v1, insert a provider row, migrate to 2, assert provider, model, active row and validated schema; update `PersistenceTest` to v2 -- AD-19
- [ ] `app/...` -- provider list, edit form, test result display, `strings.xml` -- R2

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` runs, then it passes and `data/schemas/**/2.json` is new while `1.json` is unchanged.
- Given the v2 `LumynoidDatabase` built from `LumynoidData`, when its builder is inspected, then `addMigrations(MIGRATION_1_2)` is present and no destructive fallback is.
- Given a debug APK on a device, when the tester adds OpenRouter from the preset and one custom URL, tests each with a right and a wrong key, selects one as active and sends a message, then the answer comes from the selected provider and model.

## Implementation Notes

Implemented directly in the main session (no subagent was requested). Verified: `:core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` pass (44 tests, 0 failed); `1.json` untouched, `2.json` added; `:core` runtime classpath has no android/ktor/room.

Where the build differs from the Code Map:
- `MigrationTestHelper` runs on the host JVM (no `androidTest` fallback needed), but only with `room3-runtime-jvm` and `sqlite-jvm` added to `:data` test dependencies, ahead of the Android runtime; without them it fails with `NoSuchMethodError` on `DatabaseConfiguration`. All three are test-only.
- `ProviderAdminPort` is `overview / save(draft) / listModels(draft) / testConnection(draft) / select`. `save` takes `makeActive`; the first provider and any edit of the active provider also set the active model. `ProviderOverview` and `ProviderSummary` carry no key.
- `model_profile` holds only models the user saved (the fetched list is not cached); "Use for chat" on a card picks the provider's most recently saved model. Fetching the list needs a button press, not an automatic call.
- `MIGRATION_1_2` recreates `provider_config` (new `name`, `preset_id`; `model` removed). The migrated provider is named "Provider".
- The UI is split out: `ProvidersViewModel.kt` and `ProviderScreens.kt` replace the form in `ChatViewModel`/`Screens`.

Not done here, by scope or environment:
- Not run on a device or emulator: the provider list, form, preset chips, model suggestions, the Test button and its result text are compiled but unexercised. `:app` has no unit-test infrastructure, so `ProvidersViewModel` logic is untested.
- `testConnection` sends a real one-word chat request, so it can spend a few tokens on a paid provider.
- Unhandled: a Room write failure inside `save` is not caught (the story-1 review accepted the same risk for the tracer).

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run through `omp` (print mode, no tools) on the staged diff and the plan. 2 findings: high 0, medium 1, low 1, false 0, maybe-false 0. No intent_gap or bad_plan; no loopback. Patches applied by the main session; full verification re-run after: 45 tests, 0 failed, debug APK builds, dependency policy OK.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Test and model-list results are applied to whichever form is open, not the one that asked | medium | patch | Real: `testConnection`/`loadModels` wrote their result into `_state.form` unconditionally, so editing the key mid-test or opening another provider could show "Connected" for untested settings. A result is now applied only when the form's id, URL, key (and model, for the test) still equal what was sent; a stale result resets a `Running`/`Loading` state to idle. Not unit-tested (`:app` has no test setup). |
| 2 | A saved provider whose secret is gone looks "key saved" and Test silently does nothing | low | patch | Real: `startEdit` always sets `keySaved`, `testConnection` returned `Incomplete` and nothing was flagged; `save` also accepted a blank key for such a provider. `ProviderAdmin.save` now returns `INCOMPLETE` when no new key is given and the stored secret is missing; the view model clears `keySaved` on `Incomplete`, so the key field flags itself. Test added in `:llm`. |

## Design Notes

- Gemini's OpenAI-compatible `/models` returns ids prefixed `models/`; chat accepts them as given, so store the id verbatim.
- If `room3-testing-jvm` clashes with `room3-runtime-android` on the host unit-test classpath, put `MigrationTest` in `data/src/androidTest` with `room3-testing`, make sure it compiles, and record in Implementation Notes that it could not run here (no device). Do not drop the test and do not use a destructive fallback.
- `active_model` stays a single-row table rather than a flag on `provider_config`, so story 5 (Anthropic) and epic-hemat-kuota can change what "active" means without another provider migration.

## Verification

**Commands:**
- `./gradlew :core:test :llm:test :data:testDebugUnitTest` -- expected: all tests pass, including `MigrationTest`
- `./gradlew :app:assembleDebug :app:checkDependencyPolicy` -- expected: BUILD SUCCESSFUL
- `git status --short data/schemas` -- expected: only `2.json` added

**Manual checks (if no CLI):**
- No device or emulator on the build host (as in story 1). With the debug APK: add OpenRouter from the preset and a custom URL, test with a right key (Connected) and a wrong key (Failed with a reason), select each as active and confirm the next chat answer uses it. This is the ticket's verify step and belongs to the human tester.
