---
title: 'Refactor sweep'
type: refactor
ticket: '11'
created: '2026-11-20'
status: built
baseline_revision: '9cd9627336896763a7ef041640cfa2064a4932d9'
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

**Problem:** Stories 1.1–1.10 left duplicated stream scaffolding, repeated lookups and guards, dead code and misplaced tests. No deferred review finding is a pure refactor, so the sweep scope comes from reading the code.

**Approach:** Behavior-preserving cleanup of the items in Tasks. No user-visible change; every existing test keeps its name and passes.

## Boundaries & Constraints

**Always:** `:core` stays free of android, ktor and room. Persisted names (`@SerialName`, Room entities, `data/schemas/**`) are untouched. Keep the `try/catch` inside each adapter's `flow` so a `wireJson` decode error still maps to `Server`. Keep `before.active == null` in `ProvidersViewModel.save`. Keep `loadModels`' narrow freshness check separate from `isFresh`. Do not touch `ChatViewModel`'s `visibleLive` logic.

**Never:** No new behavior, UI text, navigation or Room schema change. No window-inset modifier extraction (Compose modifier order, no test coverage), no `LlmError`/`FailureReason` string-mapping merge, no deletion of the unused `ToolsUnsupported`, `MalformedToolCall` and `supportsTools` placeholders (epic-hemat-kuota). The two `deferred-work.md` entries are behavior or hand-off items, not part of this sweep.

</frozen-after-approval>

## Code Map

- `llm/.../Wire.kt` -- holds `listModelIds` (the GET twin) and internal `HttpResponse.toLlmError()`; home for the shared POST and `data:` line reader.
- `llm/.../OpenAiCompatibleAdapter.kt:35-92`, `AnthropicAdapter.kt:49-111` -- same preparePost, `isSuccess`, three-way catch and `data:` loop; `trimEnd('/')` twice each; Anthropic's private `String?.toLlmError()` at `:113` shares a name with Wire's.
- `llm/.../ProviderAdmin.kt:44-96` -- `save`, `testConnection` repeat the blank guard; `save` rebuilds what `keyFor` returns; preset lookup at `:96`.
- `core/.../ProviderPresets.kt`, `ProviderPresetsTest.kt` -- add `byId`.
- `app/.../ProvidersViewModel.kt:99-283`, `ProviderScreens.kt:69,185` -- four overview reloads, `saveAnyway` alias, `alreadyActive = false` default at `:108`, preset lookup at `:138`.
- `app/.../ChatViewModel.kt:23,35,98,109` -- unused `update` import, dead `ChatState.sending`, repeated configured check.
- `app/.../ConversationScreens.kt:119,128` -- `SetupPrompt`, `SetupBanner`, also used by `Screens.kt:94,111`.
- `llm/src/test/.../OpenAiCompatibleAdapterTest.kt` -- 483 lines; `:197-483` are `ProviderAdmin` and `LlmRouter` tests; fixtures `MemoryStore`, `CountingIds`, `draft()`, `sse()` at `:42-77`; unused import `HttpRequestData` at `:24`.

## Tasks & Acceptance

**Execution:**
- [ ] `llm/.../Wire.kt`, both adapters -- add internal `streamPost` and a `data:` line reader; adapters keep only their own parsing; share the `trimEnd('/')`; rename Anthropic's private mapper (e.g. `anthropicError`) -- one copy of the scaffolding
- [ ] `llm/.../ProviderAdmin.kt` -- private `isComplete(draft)`; `save` uses `keyFor(draft) == null` -- one guard
- [ ] `core/.../ProviderPresets.kt` + test, `ProviderAdmin.kt`, `ProvidersViewModel.kt`, `ProviderScreens.kt` -- `ProviderPresets.byId(id: String?)` replaces the three lookups
- [ ] `app/.../ProvidersViewModel.kt`, `ProviderScreens.kt` -- `reloadOverview(closeForm)`; drop `saveAnyway`, call `save`; drop the redundant default
- [ ] `app/.../ChatViewModel.kt` -- delete `sending` and unused imports; `refreshConfigured()` for the two checks, branches on `screen` unchanged
- [ ] `app/.../ConversationScreens.kt` -> `Screens.kt` -- move `SetupPrompt`, `SetupBanner`
- [ ] `llm/src/test/...` -- move admin and router tests into `ProviderAdminTest.kt` and `LlmRouterTest.kt`, fixtures into `TestFixtures.kt`; delete the unused import; keep test method names

**Acceptance Criteria:**
- Given the baseline and the change, when the full gradle verification below runs, then it passes with the same test count or more (`byId` adds tests).
- Given the diff, when `data/schemas`, string resources and `AndroidManifest.xml` are compared to baseline, then they are unchanged.
- Given the adapters, when a stream cuts off, a decode error occurs, or HTTP fails, then the same `LlmError` kinds are emitted as before (existing tests cover it).

## Implementation Notes

Built by a Sonnet subagent. Verified (also re-run on my side after one correction): `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:checkDependencyPolicy` is BUILD SUCCESSFUL; `data/schemas`, `app/src/main/res` and `AndroidManifest.xml` are unchanged from baseline. Core gained one test (`byId`); the 28 tests moved out of `OpenAiCompatibleAdapterTest.kt` kept their names.

- `streamPost` and `readDataLines` live in `Wire.kt`, with a small `joinUrl` for the repeated `trimEnd('/')`. The `try/catch` is inside `streamPost`, called within each adapter's `flow`, so decode errors still map to `Server`.
- `ProvidersViewModel.reloadOverview` is used by `refresh`, `connect` and `save`. The implementer also used it in `use()`, which made `use()` refresh `providers` and set `loaded`; I put `use()` back to its original two lines so the sweep stays behavior-preserving.
- `ProviderAdmin.save` now reads the stored provider a second time through `keyFor`; same result, one extra store read.
- `TestFixtures.kt` also holds `SECRET`, `request`, `adaptersFor`, `admin`, `okStream` and `anthropicStream`, which the moved tests share.
- The implementer ran `git stash -u` and `git stash pop` once by mistake; the tree was restored and the stash list is empty.
- Not run on a device; none is needed. The phone check for story 1.10 stays owed.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run by `omp` with `openai-codex/gpt-6.2-sol` (thinking high, read-only tools). 4 findings: high 0, medium 0, low 4, false 0, maybe-false 0. No intent_gap or bad_plan; no loopback. Two patched, two rejected.

| # | Finding | Verdict | Route | Evidence |
|---|---------|---------|-------|----------|
| 1 | `encodeToString` now runs before `streamPost`'s `try`, so a serialization exception would escape the flow | low | reject | Real in principle, but the request classes hold only strings, a bool and lists of strings, which `kotlinx.serialization` cannot fail on. Guarding it needs a lambda parameter on `streamPost`. |
| 2 | `save` reads the stored provider twice (`existing`, then `keyFor`) | low | reject | Real but already named in Implementation Notes: one extra DAO read per save of an edited provider. Removing it would bring back the duplicated key-lookup logic the sweep removed. |
| 3 | A successful connect or save now sets `loaded = true`, which only `refresh()` set before | low | patch | Real; nothing reads `loaded`, but the sweep must not change state. `reloadOverview` now sets it only when `closeForm` is false and keeps the old value otherwise. |
| 4 | No test makes the SSE decoder throw, so the claim that decode errors still map to `Server` was unproven | low | patch | Real: the existing `Server` tests use valid JSON error frames or HTTP statuses. Added `aMalformedStreamPayloadFailsWithServerAfterTheTextSoFar` to both adapter suites; the llm test count went from 38 to 40. |

After the patches: the full verification command passes (core 34, llm 40, data 14, app 28 tests, 0 failures).

## Verification

**Commands:**
- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:checkDependencyPolicy` -- expected: BUILD SUCCESSFUL
- `git diff --stat 9cd9627 -- data/schemas app/src/main/res app/src/main/AndroidManifest.xml` -- expected: empty

**Manual checks (if no CLI):**
- None needed; no user-visible behavior changes. Phone check for story 1.10 stays owed separately.
