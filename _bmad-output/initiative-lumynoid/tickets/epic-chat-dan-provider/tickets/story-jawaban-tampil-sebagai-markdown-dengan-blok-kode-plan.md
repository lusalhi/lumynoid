---
title: 'Assistant answers render as markdown with code blocks'
type: feature
ticket: '6'
created: '2026-11-15'
status: done
baseline_revision: '0cbc533ad3eb03c718e3110a6e13386732e5322c'
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

**Problem:** Assistant bubbles on the story-1 chat screen show raw text, so headings, lists, tables and code arrive as markdown syntax (R3). Rendering markdown naively would open an egress path: raw HTML, auto-loaded remote images and one-tap links (AD-9).

**Approach:** Render assistant bubbles, live and saved, with multiplatform-markdown-renderer 0.46.0 (core + m3), hooking it so HTML shows as literal text, images become links, and every link tap goes through an in-app dialog that shows the full URL before anything opens.

## Boundaries & Constraints

**Always:** AD-9: no raw HTML rendering, no remote image fetch, every outgoing link through the app's full-URL dialog. Only artifacts `com.mikepenz:multiplatform-markdown-renderer` and `-m3` at 0.46.0, in `:app` only; `:app:checkDependencyPolicy` must stay green (AD-17). Strings in `strings.xml`, English. User bubbles stay plain text.

**Never:** No image loader (Coil, the `-coil*` modules), no syntax highlighting (`-code` module), no WebView. No stop button (story 8), no change to `:core`, `:llm` or `:data`. Never open a link without the dialog, and never open a scheme other than `http`, `https` or `mailto`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rich answer | Headings, bullet and numbered lists, GFM table, fenced code | Styled headings and lists; table and code block scroll sideways inside the bubble; code in monospace | No error expected |
| Streaming | Answer arrives in chunks, incl. an unclosed code fence | Bubble re-renders as text grows, no blank flash; becomes the saved bubble without a jump | No error expected |
| Raw HTML | `<div>x</div>` block, inline `<b>y</b>`, `<img src=…>` | Shown as literal text including the tags; nothing fetched | N/A |
| Remote image | `![chart](https://e.com/a.png)` | Link labelled `Image: chart` (URL if no alt); no network request | Tap follows the link row below |
| Link tap | `[x](https://e.com/p?q=1)`, autolink, reference link | Dialog shows the full resolved URL; Open launches the browser; Cancel does nothing | No app can handle it: dialog shows a plain error, no crash |
| Unsafe scheme | `javascript:…`, `intent:…`, `file:…`, relative `foo` | Dialog shows the URL with "can't be opened"; no Open button | Nothing is launched |

</frozen-after-approval>

## Code Map

- `gradle/libs.versions.toml` -- add `markdownRenderer = "0.46.0"` and libraries `markdown-renderer` / `markdown-renderer-m3` (`com.mikepenz:multiplatform-markdown-renderer[-m3]`). POMs: Apache-2.0; transitive `org.jetbrains:markdown-jvm` 0.7.17, `kotlinx-collections-immutable` 0.5.2, `org.jetbrains.compose.foundation` 1.12.1 (Android variant redirects to androidx).
- `app/build.gradle.kts` -- add both libraries; add `testImplementation(libs.junit)` (`:app` has no unit tests yet).
- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/Screens.kt` -- `Bubble(text, fromUser)` is used for user, saved assistant and `state.live`; assistant paths switch to the new markdown bubble. Keep `widthIn(max = 320.dp)`, colours and the auto-scroll `LaunchedEffect`.
- New `app/src/main/kotlin/dev/lusalhi/lumynoid/app/MarkdownBubble.kt` -- the markdown bubble and link dialog.
- New `app/src/main/kotlin/dev/lusalhi/lumynoid/app/LinkPolicy.kt` -- pure Kotlin, no Compose: `fun openableUri(url: String): String?` (trimmed, scheme case-insensitive in `http`/`https`/`mailto`, else null) and `fun imageLinkLabel(alt: String?, url: String): String`.
- `app/src/main/res/values/strings.xml` -- dialog title, Open, Cancel, "can't be opened", "no app can open", `Image: %1$s`.
- Library hook points (0.46.0 sources): `annotatorSettings()` default listener calls `LocalUriHandler.current.openUri(resolvedUrl)` for every link (inline, reference, autolink), so providing `LocalUriHandler` around `Markdown` catches all taps; `markdownAnnotator(annotate = …)` sees each inline child before default handling, where `HTML_TAG` (default: dropped) and `IMAGE` (default: inline image slot) are intercepted; `HTML_BLOCK` has no default component (dropped) and goes through `markdownComponents(custom = …)`; `components.image` covers block-level images; `NoOpImageTransformerImpl` (the default) never loads anything; code fences and tables already use `horizontalScroll`.
- Do not change: `ChatViewModel` (live/saved de-duplication), `ProviderScreens.kt`.

## Tasks & Acceptance

**Execution:**
- [ ] `gradle/libs.versions.toml`, `app/build.gradle.kts` -- add the two renderer libraries and `testImplementation(libs.junit)` -- AD-17 pinned version
- [ ] `app/.../LinkPolicy.kt` -- `openableUri`, `imageLinkLabel` -- AD-9 scheme allowlist
- [ ] `app/.../MarkdownBubble.kt` -- `AssistantMarkdown(text)` using m3 `Markdown` with: annotator appending `HTML_TAG` text verbatim and turning `IMAGE` into a `LinkAnnotation.Url` labelled by `imageLinkLabel`; custom `HTML_BLOCK` component showing its source text; `image` component showing the same link; a `LocalUriHandler` that sets the pending URL for `LinkDialog`; `LinkDialog` (Material 3 `AlertDialog`, full URL selectable, Open only when `openableUri` is non-null, opens through the platform handler captured before the override and catches failures) -- AD-9
- [ ] `app/.../Screens.kt` -- assistant and live bubbles use `AssistantMarkdown`; user bubbles unchanged -- R3
- [ ] `app/src/main/res/values/strings.xml` -- new strings -- conventions
- [ ] `app/src/test/kotlin/.../LinkPolicyTest.kt` -- every scheme row of the matrix (incl. mixed case, whitespace, relative) and both label cases -- I/O matrix
- [ ] `app/src/test/kotlin/.../MarkdownSafetyTest.kt` -- parse matrix inputs with `GFMFlavourDescriptor` and run the bubble's annotator through the library's `buildMarkdownAnnotatedString`: inline `<b>` and `<img>` text present verbatim, image yields a `LinkAnnotation.Url` with the label, no annotation for HTML -- AD-9

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:checkDependencyPolicy` runs, then it passes.
- Given the resolved `:app` release runtime classpath, when listed, then it has no `coil`, `highlights` or `multiplatform-markdown-renderer-code` artifact.
- Given a debug APK on a phone, when the tester asks for an answer containing a heading, list, table, code block, HTML tags, a remote image and a link, then it renders as in the matrix and the link opens only after the full-URL dialog.

## Implementation Notes

- `imageLinkLabel(alt, url, template)` takes the localized `Image: %1$s` template as a third argument so the pure function stays free of hardcoded English.
- The renderer ships Java 21 bytecode; the project toolchain and CI run JDK 17, so `MarkdownSafetyTest` is skipped there (4 skipped, never run). Its rows are unverified until run on JDK 21 or by the manual phone check. A CI bump to JDK 21 would let it run.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run through `omp` with model `gpt-6.1-sol` (print mode, thinking medium, read-only tools `read,grep`; the owner chose the model). 3 findings: high 0, medium 1, low 2, false 0, maybe-false 0. No intent_gap or bad_plan; no loopback. Two patches sent back to the implementation subagent; full verification re-run after.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Unclosed code fence loses its last line | low | patch | Real: 0.46.0 `MarkdownCodeFence` ends at `children[size - 2]`, so a fence without `CODE_FENCE_END` drops its final `CODE_FENCE_CONTENT` (a saved truncated answer loses it for good; while streaming it lags one line). Fixed with a `codeFence` component that ends at the last non-`CODE_FENCE_END` child. |
| 2 | Auto-scroll can finish before the async parse grows the live bubble | low | reject | Real but transient: the next chunk scrolls again, and at the end the `AssistantMessage` and `TaskFinished` events change the item count while the saved bubble parses immediately, so the final view is right. Fixing mid-stream lag needs layout-driven scrolling, more than a direct correction. |
| 3 | Long URL clipped in the link dialog while Open stays enabled | medium | patch | Real: Material 3 `AlertDialog` does not scroll its text slot; a URL taller than the dialog is cut, breaking AD-9's full-URL rule. Text column made vertically scrollable. |

## Design Notes

- Use `rememberMarkdownState(text, retainState = true, immediate = true)`: `immediate` parses synchronously only on first composition, so a saved bubble scrolled in or replacing the live one never flashes empty; later live updates parse off-thread, conflated, keeping the previous render meanwhile.
- One `LinkDialog` state per chat screen (hoisted in `ChatScreen` or a small `remember` in `AssistantMarkdown`) is enough; the URL shown is the one the library passes after reference resolution, which is what would open.
- If `MarkdownSafetyTest` cannot run on the local JVM because Compose text classes need Android, record that in Implementation Notes, keep `LinkPolicyTest`, and cover the annotator rows in the manual check instead.

## Verification

**Commands:**
- `./gradlew :core:test :llm:test :data:testDebugUnitTest :app:testDebugUnitTest` -- expected: all pass, including `LinkPolicyTest` and `MarkdownSafetyTest`
- `./gradlew :app:assembleDebug :app:checkDependencyPolicy` -- expected: BUILD SUCCESSFUL
- `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -Ei 'coil|highlights|renderer-code'` -- expected: no output

**Manual checks (if no CLI):**
- No device on the build host. On a phone with the debug APK, ask for an answer with a heading, list, table, fenced code, `<b>x</b>`, `![img](https://example.com/a.png)` and a link; confirm the matrix rows, that the code block and table scroll sideways, and that the link opens only after the dialog shows the full URL. This is the ticket's verify step and belongs to the human tester.
