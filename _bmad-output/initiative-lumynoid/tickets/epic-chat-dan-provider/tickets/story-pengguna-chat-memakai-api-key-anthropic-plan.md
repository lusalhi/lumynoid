---
title: 'Chat with an Anthropic API key'
type: feature
ticket: '5'
created: '2026-11-15'
status: done
baseline_revision: 'cdd6acba96001fec1c2824e9e7387d15cc0a01f7'
route: full
route_source: auto
risk: low
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 4's provider screen only speaks the OpenAI Chat Completions wire format, so a user with an Anthropic API key cannot chat (R2).

**Approach:** Add an Anthropic Messages streaming adapter on Ktor, mapped to the same `LlmChunk`/`LlmError` model, and a provider `kind` (stored in Room schema v3) that `LlmRouter` and `ProviderAdmin` use to pick the adapter. Anthropic appears as a preset on the story-4 screen with model list, connection test and active-model choice unchanged.

## Boundaries & Constraints

**Always:** AD-1 module boundaries; `:core` stays pure Kotlin. Key only via `SecretPort`, sent only in the `x-api-key` header, never in events, logs, DB rows or user-visible messages (AD-15). Failures are typed `LlmError`, never exceptions across ports (AD-11). Schema v3 exported and committed, with an explicit `Migration(2, 3)` that keeps every existing provider as `openai_compatible`; no destructive fallback (AD-19); do not edit `1.json` or `2.json`. Direct Ktor and kotlinx.serialization, no Anthropic SDK (AD-11, AD-17). API key only, no subscription login (C10). Strings in `strings.xml`, English.

**Never:** Tools, tool calls, thinking or prompt-caching parameters; conversation history (story 7); other providers or the first-launch screen (story 9); rotation or quota (epic-hemat-kuota); a Room version other than 3 (story 7 must take v4 after this).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Add Anthropic | Pick the Anthropic preset, paste key | URL prefilled with `https://api.anthropic.com/v1`; models listed from `/models`; saved with kind `anthropic` | Blank key or model → `INCOMPLETE` |
| Streaming answer | Active provider is Anthropic | `TextDelta`s from `content_block_delta` text, then `Usage`, then `Done` on `message_stop`; `system` messages go to the top-level `system` field | — |
| Wrong key | HTTP 401 or 403 | Test and chat fail with `AuthFailed` | Reason shown without key or body |
| Other HTTP errors | 402 / 429 (+`retry-after`) / 529 / other 5xx / network | `QuotaExhausted` / `RateLimited` / `Server` / `Server` / `Network` | Typed, no exception |
| Mid-stream error | SSE `error` event after text | `Failed` (`overloaded_error` → `Server`, `rate_limit_error` → `RateLimited`, `authentication_error` → `AuthFailed`) and no `Done` | Partial text is not recorded as an answer |
| Cut-off stream | Ends with no `message_stop` | `Failed(Network)` | — |
| Model list | `/models` fails or is empty | Manual model entry, as in story 4 | Never blocks Save or Test |
| Upgrade from v2 | Existing OpenAI-compatible providers | All become kind `openai_compatible`; active model unchanged | Migration test fails on any lost column |
| Switch kind | Active provider changes between Anthropic and another kind | The very next message uses the other adapter | — |

</frozen-after-approval>

## Code Map

- `core/.../Ports.kt`, `ProviderPresets.kt` -- `ProviderConfig`, `ProviderDraft`, `ProviderPreset` carry no wire format. Add `enum ProviderKind {OPENAI_COMPATIBLE, ANTHROPIC}` (persisted names `openai_compatible`, `anthropic`); `kind` on config, draft, summary and preset; fifth preset `anthropic`. `ProviderPresetsTest` asserts exactly four presets and must change.
- `llm/.../OpenAiCompatibleAdapter.kt` -- `stream(baseUrl, model, apiKey, request)` and `models(baseUrl, apiKey)`; reuse its error mapping idea and `readLine` SSE loop. Extract a small internal interface with those two functions so the router and admin hold one adapter per kind, not two named types.
- `llm/.../AnthropicAdapter.kt` (new) -- `POST {base}/messages` with `x-api-key`, `anthropic-version: 2023-06-01`, body `{model, max_tokens, stream, system?, messages}`; `GET {base}/models?limit=1000`.
- `llm/.../LlmRouter.kt`, `ProviderAdmin.kt` -- both call the one adapter directly; route by the provider's `kind` (router) or the draft's (admin: `listModels`, `testConnection`, `save`).
- `data/.../{Entities,Repositories,Migrations,LumynoidDatabase}.kt` -- `ProviderConfigEntity` gains `kind` with `@ColumnInfo(defaultValue = "openai_compatible")`; `MIGRATION_2_3` is `ALTER TABLE ... ADD COLUMN`; `version = 3`; register it in `LumynoidData`. `MigrationTest` and `PersistenceTest` extend to v3.
- `app/.../{ProvidersViewModel,ProviderScreens}.kt`, `res/values/strings.xml` -- `choosePreset` copies the preset's kind into the form; the `Custom URL` chip means OpenAI-compatible; model hint and intro text mention Anthropic.
- `llm/src/test/.../OpenAiCompatibleAdapterTest.kt` -- `MemoryStore`, `draft(...)` and `admin(...)` helpers to reuse for the new tests.

## Tasks & Acceptance

**Execution:**
- [ ] `core/...` -- `ProviderKind` and its plumbing through config, draft, summary, preset; update `ProviderPresetsTest` -- AD-11
- [ ] `llm/.../AnthropicAdapter.kt` + shared interface -- streaming, `models`, error mapping -- AD-11, C10
- [ ] `llm/.../LlmRouter.kt`, `ProviderAdmin.kt` -- choose adapter by kind -- AD-12
- [ ] `llm/src/test/.../AnthropicAdapterTest.kt` -- `MockEngine` tests for every matrix row reachable here, headers (`x-api-key`, version), key absent from body and failures, `system` field, router switching between kinds -- AD-15
- [ ] `data/...` -- kind column, `MIGRATION_2_3`, committed `3.json`; migration test from v2 with two providers and an active model -- AD-19
- [ ] `app/...`, `strings.xml` -- Anthropic chip and kind in the form state -- R2

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` runs, then it passes, `3.json` is new, and `1.json` and `2.json` are unchanged.
- Given the `LumynoidData` builder, when inspected, then it registers `MIGRATION_1_2` and `MIGRATION_2_3` and no destructive fallback.
- Given a debug APK and a valid Anthropic key, when the tester adds the Anthropic preset, tests it, selects it and sends a message, then the test shows connected and the answer streams; with a wrong key the test fails with the key-rejected reason.

## Implementation Notes

Implemented directly in the main session (no subagent was requested). Verified: `:core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` pass (62 tests, 0 failed); `3.json` is the only new schema file; `:core` runtime classpath has no android/ktor/room.

Where the build differs from the Code Map:
- The shared interface is `WireAdapter` plus `ProviderAdapters(client).forKind(kind)` in `llm/Wire.kt`; `LlmRouter` and `ProviderAdmin` now take `ProviderAdapters`. The same file holds the pieces both wire formats share: HTTP status mapping, the JSON config and the `/models` reader, so `OpenAiCompatibleAdapter` lost its private copies.
- `ProviderKind` keeps its persisted names in `wireName`; unknown names read back as `OPENAI_COMPATIBLE`.
- The new `kind` parameter is the last, defaulted parameter of `ProviderConfig`, `ProviderDraft`, `ProviderSummary` and `ProviderPreset`, so story-4 call sites did not change.
- The model hint is per kind (`claude-sonnet-5-5` for Anthropic) and the screen intro mentions Anthropic.

Not done here, by scope or environment:
- Not run against the real Anthropic API or on a device; the SSE shapes come from the Messages API documentation as I know it, tested against `MockEngine` only. A real-key run is the ticket's verify step.
- `max_tokens` is fixed at 8192; a model with a lower output cap (older Haiku) would answer HTTP 400, which shows as a provider error.
- The Anthropic chip, kind switching and the hint are compiled but unexercised (`:app` has no unit-test setup).

Marked done by the owner on 2026-11-15 without a live Anthropic check: the owner has no Anthropic credits, so the adapter was never run against the real API. Only the `MockEngine` tests back it. Epic story 12 (release test on a real phone) is the place to catch a wire-format mismatch, ideally with a funded Anthropic key.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run through `omp` (print mode, no tools, thinking low; a first run at default effort timed out with no output). 3 findings: high 0, medium 0, low 2, false 1, maybe-false 0. No intent_gap or bad_plan; no loopback. One patch applied by the main session; full verification re-run after.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | `/models?limit=1000` exceeds Anthropic's cap of 100 | false | reject | The official Models list reference (docs.anthropic.com/en/api/models-list) says `limit` "Defaults to 20. Ranges from 1 to 1000", so 1000 is valid. |
| 2 | Fixed `max_tokens = 8192` is rejected by models with a lower output cap | low | reject | Real only for legacy models (e.g. Claude 3 Haiku, 4096), and then shows as a provider error. A lower constant would silently truncate long answers on every model; per-model caps need a table. Recorded in Design Notes and Implementation Notes as a known limit. |
| 3 | `loadModels` freshness ignores `kind`; switching Anthropic and Custom with the same URL can show the old adapter's list | low | patch | Real: `choosePreset` changes `kind` without always changing the URL. `kind` is now part of the freshness check. Not unit-tested (`:app` has no test setup). |

## Design Notes

- Anthropic requires `max_tokens`; use `8192`. A reply that stops on `max_tokens` is still a finished answer (`message_stop`), so it ends in `Done`.
- `Role.TOOL` has no use before epic-agent-shell; drop it from the Anthropic request instead of inventing a mapping.
- Usage: `input_tokens` arrives in `message_start`, cumulative `output_tokens` in `message_delta`; emit one `LlmChunk.Usage` just before `Done`.
- Models in this family are named like `claude-sonnet-5-5`; use that as the model hint.

## Verification

**Commands:**
- `./gradlew :core:test :llm:test :data:testDebugUnitTest` -- expected: all tests pass, including `MigrationTest` and `AnthropicAdapterTest`
- `./gradlew :app:assembleDebug :app:checkDependencyPolicy` -- expected: BUILD SUCCESSFUL
- `git status --short data/schemas` -- expected: only `3.json` added

**Manual checks (if no CLI):**
- No device or emulator on the build host. With the debug APK and an Anthropic key: add the Anthropic preset, test with the right key (Connected) and a wrong one (key rejected), select it and confirm the answer streams. This is the ticket's verify step and belongs to the human tester.
