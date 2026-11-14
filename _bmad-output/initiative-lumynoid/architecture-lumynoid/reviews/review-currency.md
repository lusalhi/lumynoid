---
review: currency-and-reality-check
target: architecture-lumynoid.md (+ .memlog.md)
lens: every committed decision web-researched / reality-checked vs. asserted from training data
reviewed: 2026-11-14
verdict: 'Mostly grounded, but the stack is 1 to 3 releases behind on 6 of 11 pinned items. Two load-bearing ADs (AD-14 W^X, AD-18 offline preview) are missing conditions that their claims depend on.'
---

# Currency and Reality-Check Review: Lumynoid v1 Architecture Spine

## Verdict

The spine is mostly grounded. Every named technology still exists and fits an Android, SDK 37, F-Droid app, and most platform claims check out against official docs (specialUse FGS, Keystore AES-GCM, exact-alarm FGS exemption, tool-name regex). However:

- **The stack is behind.** Ktor, kotlinx.serialization, the markdown renderer, the Compose BOM, Kotlin and AGP each have newer stable releases. Gradle, KSP and coroutines are missing from the stack.
- **AD-14 is missing a condition.** The W^X claim only holds with `useLegacyPackaging` and a from-source proot build. The spine states neither.
- **AD-18 overclaims.** WebViewAssetLoader does not by itself block network, and with JS enabled it cannot reliably block WebRTC.

Method: I queried Maven Central and Google Maven metadata directly for versions and file timestamps, read the official developer.android.com, OpenAI spec, Anthropic docs and F-Droid pages, and used the GitHub API for repository activity and commit ancestry. All checks are as of 2026-11-14.

---

## 1. Stack table: version currency

| Item | Spine | Verified latest stable (date) | Status | Source |
| --- | --- | --- | --- | --- |
| Kotlin | 2.4.20 | **2.4.21** (2026-10-08); 2.4.20 = 2026-09-07; 2.5.0-RC out | Outdated (patch) | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml, https://blog.jetbrains.com/kotlin/2026/09/kotlin-2-4-20-released/ |
| AGP | 9.5.0 ("belum terkonfirmasi stabil penuh") | **9.5.1** (2026-11-05). 9.5.0 is stable (2026-10-28). 9.6.0 is alpha. AGP 9.5 needs **Gradle ≥ 9.6.0**, JDK 17, max API 37 | Confirmed stable; patch available; Gradle row missing | https://developer.android.com/build/releases/agp-9-5-0-release-notes, https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml |
| Compose BOM | 2026.08.00 (Compose 1.12, M3 1.4.0) | **2026.09.00** (2026-09-09) → ui/foundation/runtime **1.12.1**, material3 1.4.0. The 2026.08.00 mapping (1.12.0 / 1.4.0) is confirmed | Outdated (one BOM) | https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom, https://developer.android.com/jetpack/androidx/releases/compose-ui |
| compileSdk/targetSdk 37 | 37 | Compose 1.12 compiles against API 37 and requires **AGP ≥ 9.2.0**. AGP 9.5 supports max API 37 | Confirmed | https://developer.android.com/jetpack/androidx/releases/compose-ui (1.12.0-alpha01 note) |
| Ktor client | 3.5.0 | **3.6.1** (2026-10-29); 3.6.0 = 2026-09-16; 3.5.2 = 2026-07-31 | Outdated (minor) | https://github.com/ktorio/ktor/releases, https://repo1.maven.org/maven2/io/ktor/ktor-client-core/maven-metadata.xml |
| kotlinx.serialization | 1.11.0 ("tanggal tak terkonfirmasi") | **1.13.0** (2026-11-11, built on Kotlin 2.4.10). 1.11.0 = 2026-04-09 (Kotlin 2.3.20). **Skip 1.12.0**: it has a breaking change to exception propagation, which 1.13.0 rolls back | Outdated (two minors) | https://github.com/Kotlin/kotlinx.serialization/releases |
| Room | androidx.room3 3.0.3 | **3.0.3** (2026-09-09) is the latest stable; 3.1.0-alpha03 is out. Requires **KSP** and an `SQLiteDriver`; coroutines-only | Confirmed; KSP missing from stack | https://developer.android.com/jetpack/androidx/releases/room3 |
| jsoup | 1.24.1 ("hindari 1.23.x, ada CVE") | **1.24.1** (2026-11-03) is the latest. The CVE rule is overbroad (see F-6) | Confirmed version; rule wording wrong | https://jsoup.org/news/, https://repo1.maven.org/maven2/org/jsoup/jsoup/maven-metadata.xml |
| Readability4J | 1.0.8 | 1.0.8 is still the latest, but it was **published 2021-10-26 and the repo has had no push since then**. It depends on jsoup **1.11.2** | Exists; unmaintained (see F-4) | https://repo1.maven.org/maven2/net/dankito/readability4j/readability4j/1.0.8/readability4j-1.0.8.pom, https://github.com/dankito/Readability4J |
| multiplatform-markdown-renderer (mikepenz) | 0.40.1 ("tanggal tak terkonfirmasi") | **0.46.0** (2026-10-13). 0.40.1 = 2026-04-06; 0.40.2 and 0.41 to 0.45 came in between. 0.46.0 has a breaking change (`feat(markdown)!`, themed GFM highlight spans). Apache-2.0 | Outdated (six minors) | https://github.com/mikepenz/multiplatform-markdown-renderer/releases, https://repo1.maven.org/maven2/com/mikepenz/multiplatform-markdown-renderer/maven-metadata.xml |
| kotlinx.coroutines | not in stack ("tidak terverifikasi" in memlog) | **1.11.0** (2026-05) | Missing row | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml |
| KSP | not in stack | **2.3.14** (KSP uses its own versioning since 2.3.0; Room 3 needs it) | Missing row | https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml |
| Gradle | not in stack | **≥ 9.6.0** required by AGP 9.5. Kotlin 2.4.20 supports Gradle up to 9.7 | Missing row; check upper bound | https://developer.android.com/build/releases/agp-9-5-0-release-notes, https://kotlinlang.org/docs/whatsnew2420.html |
| Koog (evaluated, not used) | "1.0 stabil Mei 2026" | 1.0.0 = 2026-05-21 is correct. Latest is now **1.3.0** (2026-09) | Context only | https://repo1.maven.org/maven2/ai/koog/koog-agents/maven-metadata.xml |

**Licences / F-Droid fit of the named libraries.** All are OSI-licensed and come from Maven Central or Google Maven:

- Ktor, kotlinx.*, androidx (incl. Room 3, webkit, Compose), Readability4J, multiplatform-markdown-renderer: Apache-2.0
- jsoup: MIT

None of them pulls Google Play Services. The image modules of the markdown renderer (Coil) are optional. Spine AD-9 says remote images are not fetched, so do not add the Coil transformer. I did not confirm the no-Coil default behaviour from the docs; verify it in a test.

---

## 2. Technical claims behind the ADs

| Claim (AD) | Result | Evidence / correction |
| --- | --- | --- |
| Executing proot from `nativeLibraryDir` satisfies W^X (AD-14) | **Conditionally confirmed. The spine is missing the condition.** | The Android 10 doc forbids `execve()` on files in the writable app home dir, and forbids `PROT_EXEC` mappings from a writable fd. `nativeLibraryDir` is executable, but **only if the libs are extracted to disk**. Since AGP 3.6, native libs are stored uncompressed and *not* extracted by default, so `libproot.so` will not exist on disk unless `packaging { jniLibs { useLegacyPackaging = true } }` is set (the manifest equivalent is `extractNativeLibs="true"`). Rootfs binaries are never `execve`'d directly: they rely on proot's loader (itself in `nativeLibraryDir`) mapping them from a read-only fd. That is consistent with the doc wording, but nobody has shown it on API 37 in our searches, so the epic-2 spike must prove it. Android 17's "Safer Native DCL" only covers `System.load()` of writable files, so it does not affect exec of proot. Sources: https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission, https://developer.android.com/about/versions/17/behavior-changes-17 (Safer Native DCL), https://dev.to/ai2th/pockr-part-2-executing-binaries-on-android-3b4k, https://developer.android.com/ndk/guides/wrap-script.html |
| FGS `specialUse` has no system timeout, unlike `dataSync` (AD-2) | **Confirmed** (removes the memlog `[ASSUMPTION]`) | Timeouts apply only to `dataSync` and `mediaProcessing` (6 h per 24 h, targetSdk 35+) and to `shortService`. The official FGS changes page lists no Android 17 FGS changes. Android 16's only FGS change is that jobs started from an FGS obey job quotas. `specialUse` is **not** in the Android 15 list of types that `BOOT_COMPLETED` receivers cannot launch. Sources: https://developer.android.com/develop/background-work/services/fgs/timeout, https://developer.android.com/develop/background-work/services/fgs/changes, https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed |
| Exact alarm → receiver → start FGS (AD-16) | **Confirmed** | The docs say exact alarms "aren't affected by foreground service launch restrictions", and "invokes an exact alarm to complete an action that the user requests" is a listed exemption. Sources: https://developer.android.com/develop/background-work/services/alarms#exact, https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start |
| Reschedule on `BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` (AD-16) | **Confirmed, but incomplete** | All alarms are cleared on reboot. The three broadcasts are deliverable to manifest receivers and are also FGS-start exemptions. **Missing:** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, which the official doc says should trigger the same reschedule logic as boot. Also recommended: `ACTION_MY_PACKAGE_REPLACED`, which is in the FGS exemption list. `SCHEDULE_EXACT_ALARM` is **not pre-granted** for targetSdk 33+ and is denied after backup/restore to Android 14+. Revoking it **kills the app and cancels all exact alarms**, so check `canScheduleExactAlarms()` on every launch. Off-Play alternative: `USE_EXACT_ALARM` is auto-granted and non-revocable. Its use is restricted only by Google Play policy, which matters if Play distribution is ever pursued. Source: https://developer.android.com/develop/background-work/services/alarms |
| `WebViewAssetLoader`, no universal `file://`, all network blocked, JS on (AD-18) | **Partially confirmed. "All network blocked" is not delivered by the named mechanism.** | `InternalStoragePathHandler` serves only a dedicated subdir of `getDataDir()` or `getCacheDir()`. It forbids exposing the whole data dir and `app_webview/`, `databases/`, `lib/`, `shared_prefs/`, `code_cache/`. Paths outside the mount return 404. However, **if no `PathHandler` matches, WebView falls back to loading from the network**, so a deny-all `shouldInterceptRequest` plus `WebSettings.setBlockNetworkLoads(true)` is required. Even then, `shouldInterceptRequest` does not see WebSocket or WebRTC traffic, and a test repo reported WebRTC ICE traffic despite `setBlockNetworkLoads(true)` (Chromium 96, 2021; current WebView unverified). With JS allowed, egress blocking is **unconfirmed**. Also set `setAllowFileAccess(false)` and `setAllowContentAccess(false)` explicitly. Sources: https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader.InternalStoragePathHandler, https://developer.android.com/develop/ui/views/layout/webapps/load-local-content, https://github.com/rmst/android-webview-exfiltration (via https://awesome.ecosyste.ms/projects/github.com%2Frmst%2Fandroid-webview-exfiltration) |
| Android Keystore AES-GCM, non-exportable key (AD-15) | **Confirmed** | Key material never enters the app process. AES-128/256 is supported, including on StrongBox. Android 17 adds a per-app key cap of 50,000 for apps targeting 37, which is irrelevant at our scale. Keystore keys do not survive backup/restore, which matches AD-15's exclusion from Auto Backup. With minSdk 29, exclusion needs **both** `dataExtractionRules` (API 31+) and `fullBackupContent` (API ≤30). Sources: https://developer.android.com/privacy-and-security/keystore, https://developer.android.com/about/versions/17/behavior-changes-all |
| Tool-name regex `^[a-z][a-z0-9_]{0,63}$` is safe for OpenAI (AD-6) | **Confirmed** | OpenAI `FunctionObject.name`: "Must be a-z, A-Z, 0-9, or contain underscores and dashes, with a maximum length of 64", so dots are not allowed and the spine's reasoning is right. Anthropic: `^[a-zA-Z0-9_-]{1,128}$`. The spine's regex is a strict subset of both. Sources: https://github.com/openai/openai-openapi (manual_spec/openapi.yaml, `FunctionObject`), https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools |
| Phantom process killer "32 child" (AD-3) | **Confirmed (community sources)** | `max_phantom_processes` defaults to 32 **system-wide across all apps**, not per app. That strengthens AD-3. Source: https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md |
| "Batas memori Android 17" (AD-3) | **Confirmed (official)** | Android 17 adds RAM-based per-app memory limits for all apps. The exit reason is `REASON_OTHER` with `MemoryLimiter:AnonSwap`. The doc does not say whether proot child processes count toward the app's limit; the spike should measure this. Source: https://developer.android.com/about/versions/17/behavior-changes-all |
| No proprietary deps / F-Droid inclusion (AD-17) | **Confirmed in principle. Three operational gaps.** | (a) F-Droid requires binaries to be built from source. A prebuilt `libproot.so` and loader committed to `jniLibs/` will be rejected, so the F-Droid recipe must build proot + talloc with the NDK. (b) The Inclusion Policy forbids downloading "additional executable binary files" without **opt-in user consent**. The rootfs download (AD-14) and `npm install` need an explicit consent step. (c) AGP's encrypted dependency-metadata signing block makes F-Droid builds fail. Set `dependenciesInfo { includeInApk = false; includeInBundle = false }`. NonFreeNet anti-feature: likely avoidable if self-hosted OpenAI-compatible endpoints are first-class and no proprietary provider is the default. Sources: https://f-droid.org/docs/Inclusion_Policy/, https://f-droid.org/docs/Anti-Features/, https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/DependenciesInfo, https://forum.f-droid.org/t/build-fails-with-found-extra-signing-block/29220 |

---

## 3. Findings (prioritised)

### F-1 (High): AD-14 does not state the packaging condition that makes the W^X claim true

- **What's wrong:** "dikirim sebagai .so di jniLibs … dieksekusi dari nativeLibraryDir (lolos W^X)" is true only when native libs are extracted at install. That is not the default since AGP 3.6.
- **Correction:** Add to AD-14:
  - `android.packaging.jniLibs.useLegacyPackaging = true`
  - proot and loader built from source in CI and in the F-Droid recipe, never committed as prebuilts
  - `PROOT_TMP_DIR` pointed at `cacheDir`
  - rootfs binaries run only through proot's loader, never `execve`'d directly
- **Spike items:**
  - prove on an API 37 device that the loader path works
  - check 16 KB page-size devices: proot and loader need `-Wl,-z,max-page-size=16384`, and rootfs ELF segment alignment must be tested. Termux has an open issue for 16 KB support.
- **Sources:**
  - https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
  - https://dev.to/ai2th/pockr-part-2-executing-binaries-on-android-3b4k
  - https://developer.android.com/guide/practices/page-sizes
  - https://f-droid.org/docs/Inclusion_Policy/

### F-2 (High): AD-18 "semua request jaringan diblok" is not achieved by WebViewAssetLoader, and is unconfirmed with JS on

- **What's wrong:** WebViewAssetLoader falls back to the network for unmatched paths. `shouldInterceptRequest` misses WebSocket and WebRTC, and WebRTC was reported to bypass `setBlockNetworkLoads`.
- **Correction:** The rule should name the mechanisms:
  - deny-all `shouldInterceptRequest` (an empty 403 for any non-asset-host URL)
  - `setBlockNetworkLoads(true)`
  - `shouldOverrideUrlLoading` → block
  - `allowFileAccess` and `allowContentAccess` set to `false`
  - a CSP header injected on served HTML
- **JS default:** Either default JS to **off** with a per-preview "enable scripts" toggle, or keep JS on and accept a documented residual WebRTC risk until a spike proves otherwise on current WebView.
- **Sources:**
  - https://developer.android.com/develop/ui/views/layout/webapps/load-local-content
  - https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader.InternalStoragePathHandler
  - https://awesome.ecosyste.ms/projects/github.com%2Frmst%2Fandroid-webview-exfiltration

### F-3 (Medium): Stack table is stale on six rows and missing three

Corrected values (all stable as of 2026-11-14):

| Item | Correction |
| --- | --- |
| Kotlin | **2.4.21** |
| AGP | **9.5.1** (9.5.0 is stable, which resolves the memlog doubt) + **Gradle 9.6.x** (AGP min 9.6.0) |
| Compose BOM | **2026.09.00** (Compose 1.12.1, material3 1.4.0) |
| Ktor | **3.6.1** |
| kotlinx.serialization | **1.13.0** (do not use 1.12.0) |
| multiplatform-markdown-renderer | **0.46.0** (breaking change vs 0.40.x) |
| Missing rows to add | kotlinx.coroutines **1.11.0**, KSP **2.3.14** (Room 3 requires KSP) |

Room3 3.0.3 and jsoup 1.24.1 are confirmed current. Sources are in section 1.

### F-4 (Medium): Readability4J is effectively abandoned

- **What's wrong:** The last publish and push were 2021-10-26. It pins jsoup 1.11.2, which Gradle will silently upgrade to 1.24.1. Thirteen jsoup minor versions of API drift are untested, which is a build or runtime risk in epic-browsing-web.
- **Correction:** Keep it behind the `:web` extraction port. Add a contract test against jsoup 1.24.1 before committing, or plan to vendor or port it (Apache-2.0 allows this).
- **Sources:**
  - https://github.com/dankito/Readability4J (API: `pushed_at` 2021-10-26)
  - https://repo1.maven.org/maven2/net/dankito/readability4j/readability4j/1.0.8/readability4j-1.0.8.pom

### F-5 (Medium): AD-16 reschedule triggers and permission lifecycle are incomplete

- **Add `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.** This is required by the official guidance.
- **Add `ACTION_MY_PACKAGE_REPLACED`** (recommended).
- **Handle permission state.** `SCHEDULE_EXACT_ALARM` is not pre-granted on API 33+. Revocation kills the app and cancels its alarms. Check `canScheduleExactAlarms()` at app start.
- **Consider `USE_EXACT_ALARM` for the off-Play builds.** It is auto-granted.
- **Source:** https://developer.android.com/develop/background-work/services/alarms

### F-6 (Low): jsoup CVE rule is overbroad

- **What's wrong:** CVE-2026-71497 (XSS) is fixed in 1.23.1. CVE-2026-75140 (XmlTreeBuilder heap exhaustion, CVSS 8.7) is fixed by commit 862ba2f, which the GitHub compare API shows **is contained in 1.23.2** as well as 1.24.1.
- **Correction:** Change "hindari 1.23.x" to "jsoup ≥ 1.23.2 (pin 1.24.1)".
- **Sources:**
  - https://osv.dev/vulnerability/CVE-2026-75140
  - https://advisories.gitlab.com/maven/org.jsoup/jsoup/CVE-2026-71497/
  - https://api.github.com/repos/jhy/jsoup/compare/jsoup-1.23.2...862ba2f1d48ee95609183dbcfc848c9fd7afc76a

### F-7 (Medium): targetSdk 37 adds a local-network permission that the spine does not mention

- **What's wrong:** Apps targeting API 37 need the runtime permission `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group) to reach LAN hosts. This breaks self-hosted OpenAI-compatible endpoints (Ollama or LM Studio on a LAN IP) and SearXNG on LAN, both of which AD-11 and the SearchPort imply.
- **Other API 37 changes to note:** certificate transparency is on by default, and ECH is enabled. Neither should break public HTTPS providers, but test Ktor's engine.
- **Correction:** Add a convention: the `:llm` and `:web` adapters request `ACCESS_LOCAL_NETWORK` when the configured host is RFC1918 or `.local`.
- **Source:** https://developer.android.com/about/versions/17/behavior-changes-17 (Local network permission)

### F-8 (Medium): AD-17 F-Droid gaps

- **Prebuilt binaries:** No prebuilt `.so` in the repo (build proot from source).
- **Runtime downloads:** Opt-in consent is needed before downloading the rootfs or npm packages.
- **Dependency metadata:** Set `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
- **Sources:**
  - https://f-droid.org/docs/Inclusion_Policy/
  - https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/DependenciesInfo

---

## 4. Corrections to `.memlog.md` (do not edit the spine; for the next spine revision)

- "Compose 1.12 butuh compileSdk 37 & AGP>=9.1.1" → the Compose UI 1.12.0-alpha01 notes say **AGP ≥ 9.2.0**.
- "AGP 9.5.0 (Okt 2026, belum terkonfirmasi stabil penuh)" → 9.5.0 is stable (2026-10-28) and 9.5.1 is out (2026-11-05).
- "kotlinx.serialization 1.11.0 (tanggal tak terkonfirmasi)" → 1.11.0 = 2026-04-09; latest is 1.13.0 (2026-11-11).
- "multiplatform-markdown-renderer 0.40.1 (tanggal tak terkonfirmasi)" → 0.40.1 = 2026-04-06; latest is 0.46.0.
- "kotlinx.coroutines tidak terverifikasi" → 1.11.0 (2026-05).
- "[ASSUMPTION] specialUse tanpa batas waktu sistem" → can be promoted to verified (see section 2).
- "Android 15 seccomp dilaporkan merusak proot untuk glibc (set_robust_list)" → the only source found is one dev.to post (May 2026) about a **rooted Galaxy S10e on a custom ROM** (ExtremeROM). Treat it as weak evidence: keep it as a spike question, not a finding. Musl/Alpine was not covered. Source: https://dev.to/opassoca/running-native-glibc-debian-binaries-on-android-15-without-proot-1hmo

## 5. Not verified / residual unknowns

- **DuckDuckGo HTML endpoint:** `html.duckduckgo.com/html/` still answers, but a scripted request got **HTTP 202**, which is DDG's anomaly/bot-challenge response. That supports the spine's "[ASSUMPTION: tidak resmi, rapuh]"; plan for SearXNG or Brave as the realistic default.
- **Brave Search API:** current free-tier terms not checked.
- **Remote images in the markdown renderer:** whether multiplatform-markdown-renderer fetches remote images with no Coil module was not confirmed from docs. Verify in a test.
- **Proot child processes and the Android 17 memory limiter:** whether they count toward the app's limit is undocumented.
- **Kotlin 2.4.20 + AGP 9.5 built-in Kotlin + KSP 2.3.14 + Room3 3.0.3:** no published compatibility matrix was found. Confirm with a skeleton build in the first story.
