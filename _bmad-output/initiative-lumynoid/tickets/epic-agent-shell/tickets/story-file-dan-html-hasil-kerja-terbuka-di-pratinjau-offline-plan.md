---
title: 'File dan HTML hasil kerja terbuka di pratinjau offline'
type: feature
ticket: 10
created: '2026-11-21'
status: done
baseline_revision: ef4d43dfdee1be77580b575c7a9b8529fa7ced32
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

Open persisted coding artifacts from chat or action details without leaving Lumynoid or contacting a server. Show text and JSON as selectable text, and render HTML in a dedicated offline preview. The card names the file; it never parses tool output or assistant Markdown to create a preview action.

## Boundaries & Constraints

**Always:** Resolve the selected result's relative path using the active conversation's private workspace. Apply the same file boundary to every HTML subresource. Enforce AD-18: `WebViewAssetLoader`, deny-all interception outside its allowlist, network loads blocked, no file/content access, no external navigation, and restrictive response CSP. JavaScript begins off and requires an explicit choice for the current preview only.

**Never:** Accept a path or URL from an intent, raw tool output, or assistant text as preview authority. Expose a JS bridge, external intents, file pickers, downloads, new windows, or privileged storage. Do not change which coding operations run, broaden PRoot, or snapshot workspace contents into events.

## I/O & Edge-Case Matrix

| Given | When | Then |
| --- | --- | --- |
| A persisted text, JSON, or HTML artifact | The user taps its card in chat or action details | The in-app viewer opens the current workspace file, including after app restart. Text and JSON have no executable interpretation. |
| Missing, nonregular, oversized, escaped, or symlinked path | It is requested directly or as a local resource | It is not read; the main viewer reports unavailable and subresource requests get a local denial. |
| HTML with a sibling stylesheet or script | The user opens it, opts into JS, then closes and reopens it | Local styling works without JS; the script runs only after opt-in, and the new preview starts with JS off. |
| HTML with an external resource, link or file/content URL | It loads or the user taps the link, with or without JS | No network request succeeds, no private file or content provider is read, and no external app opens. |

</frozen-after-approval>

## Code Map

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/ChatRows.kt`: the log preserves `ArtifactIdentity(conversationId, resultEventId, path, mediaType)`; chat joins the same persisted results but currently drops the result ID.
- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/{MainActivity,Screens,ToolCards,ActionLogScreen}.kt`: Compose route and card/detail entry points. Keep existing chat draft and log selection on return.
- `userland/src/main/kotlin/dev/lusalhi/lumynoid/userland/{CodingWorkspace,CodingTools,UserlandRuntime}.kt`: workspace identity equals conversation ID; existing component checks reject symlinks and multiple hard links, and reads are bounded. `directory()` creates trees, so preview needs an existing-only path. Coding writes currently label all created HTML as `text/plain`.

## Tasks & Acceptance

- [x] `userland/.../{CodingWorkspace,CodingTools,UserlandRuntime,WorkspacePreview}.kt`: expose a scoped, bounded, existing-only read API; publish HTML media types and recognize earlier HTML artifacts by extension.
- [x] `app/.../{ChatRows,ToolCards,Screens,ActionLogScreen,MainActivity}.kt` and `app/src/main/res/values/strings.xml`: project typed `PreviewCard` actions from persisted results in chat and Log. Revalidate the selected artifact and keep opt-in out of persisted state.
- [x] `app/.../{ArtifactPreviewScreen,ArtifactHtmlActivity,OfflinePreviewWebView}.kt`, manifest and Gradle files: render native text and use a disposable WebView on an exact synthetic origin in an internet-denied process.
- [x] `userland/src/androidTest`, `app/src/test`, `app/src/androidTest`, and `README.md`: verify file boundaries, persisted identity, UI navigation, JavaScript reset and network denial; document entry points.

**Acceptance Criteria:**
- Given a conversation with a text and HTML artifact and a second conversation with its own files, when Lumynoid is relaunched and the first conversation's previews are opened, then both files render in-app while attempts to read the peer workspace or launch an external URL are denied.
- Given the handset fixture attempts a network resource load and a local script updates visible text, when JS is enabled for one preview, then the visible text updates, the controlled network sink sees no request, and a newly opened preview leaves JS off.

## Implementation Notes

- HTML runs in a non-exported `:preview` Activity. Its Intent carries only conversation ID, result ID and artifact index; the Activity reloads the stored result before using its path. Neither a caller-supplied URL nor tool/model text grants access.
- Android denies INTERNET to `:preview`; the Activity verifies socket creation is denied before constructing a WebView. This covers speculative connections outside the normal resource-interception callback. Android 10 or an incompatible WebView fails closed for HTML; native text preview is unchanged.
- The synthetic origin serves only scoped files with a restrictive CSP and exact authority checks. JavaScript opt-in recreates the WebView and is discarded when leaving preview.

## Plan Change Log

## Review Triage Log

Quick review: four medium and one low finding, all patched.

| Finding | Verdict / route | Evidence and action |
| --- | --- | --- |
| Android selection could forward HTML text to another app. | Medium / patch | Native Share, Web Search, and Process Text actions are disabled in WebView in addition to blocking page navigation. |
| Extension guessing overrode an explicit JSON media type. | Medium / patch | Restrict `.html`/`.htm` fallback to old `text/plain` results. The projection test checks a JSON artifact named `.HTM`. |
| Selecting across lazy text chunks could drop or join bytes. | Medium / patch | Add **Copy file** backed by the exact decoded text, and verify a clipboard payload spanning multiple chunks. |
| The HTML Activity hydrated unrelated event blobs on every open. | Medium / patch | Resolve only the scoped result row by indexed ID and conversation ID and reuse verified blob hydration. |
| The navigation test could confuse Chat with Log detail. | Low / patch | Check the stored output label, which is visible only in Log details in this fixture. |

## Verification

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:checkDependencyPolicy :userland:assembleDebugAndroidTest` — projection tests pass, APKs assemble and dependencies pass the release policy.
- On a connected Android device, run the preview-specific instrumentation and exercise chat → preview → back and log → preview → back after force-stop. Record the WebView version and distinguish emulator results from API 37 handset acceptance.
- Debug and both instrumentation APKs built. App unit run: 53 passed, four existing Markdown host-assumption tests skipped. Dependency policy passed for 182 modules; debug APK signature verified.
- Redroid arm64 Android 14/API 34, WebView 125.0.6422.113: two userland tests and three app instrumentation tests passed. Verified bounded reads, path/link rejection, local CSS and script, unavailable file errors, no external navigation, no loopback connection from the preview (including preconnect), and opt-in reset.
- After `am force-stop`, drove the installed app via Android UI: native text opened from chat; offline HTML opened from Log; JavaScript ran only on opt-in and was off again after closing and reopening.
- Post-review: 74 JVM tests passed across app and data, four existing skips; dependency policy and final debug APK signature passed. The Android WebView tests and navigation fixture passed with the fixes, including exact whole-file copy across text chunks and return to Log details. Repeated the force-stop scenario against the final APK.
- Handset acceptance: the user reported that all checks passed with Lumynoid Fixture 2.10 (arm64). The checklist covered offline text and JSON, denial of a symlink into another workspace, local HTML and CSS, JavaScript opt-in, blocked external navigation and images with connectivity restored, and reopening the persisted preview with JavaScript off after force-stop. This verifies the fixture APK, not a public release.
