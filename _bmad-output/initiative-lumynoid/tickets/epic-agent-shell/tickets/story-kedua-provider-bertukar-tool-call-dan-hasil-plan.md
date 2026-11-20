---
title: 'Kedua provider bertukar tool call dan hasil'
type: feature
ticket: 3
created: '2026-11-20'
status: built
baseline_revision: '6e2aadfc0116f6447bc23a0187ffa26fa839db26'
route: full
route_source: auto
risk: medium
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

TaskRunner can dispatch structured calls and feed back results. The adapters need to carry tool definitions, call fragments, and correlated results through the normal provider streams.

## Boundaries & Constraints

Use Ktor, kotlinx.serialization, and the internal types from 2.2. Keep the runner-issued IDs and map them to provider-specific IDs for outgoing assistant calls and matching results, per AD-11. Keep stream state request-local. Keep text-only requests, provider administration, tool registration and execution policy as they operate today.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Structured round | Two tool calls with fragmented arguments followed by assistant and result messages | The correct definitions and schemas are sent. Fragment indexes keep calls separate. Each result is serialized immediately after its assistant turn with the same wire ID as its call. The resumed stream can return text. | A malformed batch fails as a unit. |
| Anthropic empty input | A completed tool block with an empty input object and no JSON deltas | The runner receives an argument object of `{}`. | An incomplete block never emits Done. |
| Interrupted tool round | Cut-off stream, provider error event, or explicit truncated tool stop | A typed failure ends the stream. | Preserve Network for EOF without a terminal signal and existing provider error mappings. An explicit incomplete or empty declared tool round fails as MalformedToolCall. |
| Plain chat | No tools or structured history | Existing text body, streamed text, usage, and Done behavior. | Existing transport and decoding failure behavior. |

</frozen-after-approval>

## Code Map

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/Llm.kt` and `TaskRunner.kt` define the portable schema, call deltas, and results. TaskRunner assembles and validates whole argument documents by index after Done. ContextAssembler supplies adjacent assistant/result groups from both current and older turns. Preserve these contracts.
- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/Wire.kt` supplies JSON configuration, SSE data reading, and typed HTTP errors. `LlmRouter.kt` forwards the full request already.
- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/OpenAiCompatibleAdapter.kt` owns Chat Completions JSON and SSE. Send function definitions and assistant function calls, plus role-tool messages. Stream the function name and argument fragments by index. The terminal frame may be a finish reason or the Done marker.
- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/AnthropicAdapter.kt` owns Messages JSON and SSE. Use assistant tool-use blocks and group adjacent results into the following user turn. Preserve string content on plain turns. Stream tool-use block names and JSON fragments by content index. Treat an empty initial input as a placeholder when JSON deltas follow. Wait for message_stop and completed tool blocks.

## Tasks & Acceptance

- [x] Extend `OpenAiCompatibleAdapter.kt` request and response mapping. Use a stable wire ID derived from the local call ID on both sides of the outgoing transcript. Type empty and prematurely terminated tool completions.
- [x] Extend `AnthropicAdapter.kt` request and response mapping. Use a stable wire ID derived from the local call ID on both sides, and keep input arguments JSON objects in the outgoing tool-use blocks. Group all results for one round.
- [x] Extend `OpenAiCompatibleAdapterTest.kt` and `AnthropicAdapterTest.kt` with Ktor fixtures covering the matrix. Drive both the initial request and the continuation with two calls and verify names, arguments, schemas, correlated results, and the resumed response.

- Given a provider completes a two-call round, when TaskRunner sends a continuation or reconstructed history, then the adapter serializes a valid transcript with one matched result for each call.
- Given an incomplete or malformed tool round at either provider, when the stream terminates, then TaskRunner cannot dispatch that batch.

## Implementation Notes

- Both adapters derive stable, provider-prefixed wire IDs from runner-issued IDs for outgoing assistant calls and their results. The same mapping applies when a later task reconstructs history. The persisted call and event models stay as defined in story 2.2.
- OpenAI Chat Completions uses one role-tool message per result. Anthropic uses one user turn containing consecutive tool-result blocks and omits the optional content for an empty result. [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling) · [Claude tool calls](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
- Both adapters share the strict JSON object parser with TaskRunner. The adapters can report malformed completed rounds at the stream boundary, and TaskRunner still validates the full batch before dispatch.

## Plan Change Log

## Review Triage Log

Quick review confirmed three findings routed to patches.

| Verdict | Finding | Evidence |
|---|---|---|
| Medium | OpenAI can dispatch a call when an earlier slot is missing. | TaskRunner accepts valid populated indexes, so the adapter must check for a complete zero-based sequence before successful termination. |
| Medium | OpenAI can dispatch a tool fragment received after the choice's terminal frame. | Choice contents continued contributing after the first finish reason. Only usage and the stream marker are expected after that terminal frame. |
| Medium | Both adapters can emit Done for unparseable argument JSON. | Structural checks accepted a nonblank fragment. TaskRunner prevented execution, but the adapter terminal should carry MalformedToolCall per AD-11. |

All three findings were fixed and covered by regression fixtures. No review finding was deferred.

## Verification

- `./gradlew :llm:test :core:test` should pass the adapter fixtures and the core tool/ordinary-chat regression tests.
- `git diff --check` should report no whitespace errors.
- Final run on 2026-11-20: `./gradlew :llm:test :core:test :data:testDebugUnitTest :app:assembleDebug` succeeded with 107 tests, zero failures or skips, and a debug APK. Provider verification used local HTTP fixtures through TaskRunner.
