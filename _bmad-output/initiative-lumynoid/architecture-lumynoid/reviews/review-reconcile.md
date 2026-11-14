---
type: review
lens: reconcile (spine vs input tickets)
target: _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
inputs:
  - _bmad-output/initiative-lumynoid/initiative-lumynoid.md
  - _bmad-output/initiative-lumynoid/tickets/tickets.toml
  - _bmad-output/initiative-lumynoid/tickets/epic-*/epic-*.md (7)
  - _bmad-output/lumynoid-decisions.md
date: 2026-11-14
---

# Reconcile review: Lumynoid v1 architecture spine

## Verdict

The spine covers R1–R12 and the shared contracts the initiative asked for (harness, tool registry, provider adapter, userland lifecycle, card model, key storage), but several quiet inputs were lost or contradicted. The worst are: the F-Droid check that epic-rilis-publik handed to architecture fails on the spine's own prebuilt `libproot.so`. Skill commands have no defined route to a FINANCIAL assessment. AD-2 quietly moves the TaskRunner and FGS into epic 1, which conflicts with the ticket slicing and with R2's connection test. R6's battery and autostart onboarding is missing. C11's "empty tool call" case would end a task silently under the loop as drawn. Constraints C2, C3 and C13 are never mentioned.

Severity: **H** = contradicts a source or blocks a Done-when; **M** = a dropped input that an epic will have to re-decide; **L** = wording or traceability.

## 1. Findings

### F1 (H): The F-Droid inclusion check fails on the spine's own stack

- **Sources:** epic-rilis-publik Notes l.42 ("Aturan inklusi F-Droid ... dicek saat architecture memilih dependensi, bukan di epic ini"); initiative Notes l.100; C14 l.52 (F-Droid, anti-fitur NonFreeNet); Done when l.61.
- **Spine:** Stack row "proot + loader | dibundel di jniLibs"; source tree `app/src/main/jniLibs/arm64-v8a/libproot.so, libproot-loader.so`; AD-17 "Build reproducible dari tag git"; AD-17 then hands the check on: "Dependensi baru dicek ... di PR yang menambahkannya".
- **Gap:** F-Droid builds from source and rejects prebuilt binaries committed to the repo. Prebuilt `.so` files in `jniLibs` fail inclusion unless they are built from source during the Gradle/NDK build. The spine also never records the result of checking the chosen stack (Ktor, Room3, jsoup, Readability4J, mikepenz renderer). It passes the check on to future PRs, but the epic note handed that check to architecture. The anti-features NonFreeNet (default DuckDuckGo scraping, LLM providers) and runtime download of the rootfs and npm packages are not discussed, although C14 names NonFreeNet explicitly.
- **Fix:** Add to AD-14/AD-17 a rule that proot and its loader are built from pinned source (a git submodule plus NDK `externalNativeBuild`, or an F-Droid `srclibs` recipe) and that no prebuilt `.so` is committed. Add a one-line "stack checked against F-Droid inclusion: pass" statement and list the expected anti-features (NonFreeNet; possibly the downloading of executables).

### F2 (H): Skill CLIs run through the shell, but FINANCIAL classification is attached to `skill_` tools that the format does not produce

- **Sources:** R7 l.30; Done when l.59 ("`execute` hanya jalan setelah konfirmasi"); epic-skill-pihak-ketiga Done when l.26–27; K5.
- **Spine:** AD-6 prefix `skill_`; ERD `INSTALLED_SKILL ||--o{ TOOL`; Deferred "SkillPolicy per perintah skill (mis. `baw contract-call execute` = FINANCIAL) | epic-skill-pihak-ketiga"; AD-7 "Heuristik per tool ... milik epic pemilik tool"; AD-1 "Adapter tidak saling bergantung".
- **Gap:** An Agent Skills `SKILL.md` is instructions plus scripts, and it does not declare tools. The agent will run `baw contract-call execute ...` through `shell_*`. That call's `assess` belongs to `:userland` (epic-agent-shell), while SkillPolicy belongs to `:skills`. AD-1 forbids `:userland` from depending on `:skills`, and no port is defined between them. As a result, the spine cannot show that the reference Done-when (execute gated as FINANCIAL) is reachable.
- **Related losses:**
  - Skill credentials such as the `baw` pairing session have no home. AD-15 covers only LLM API keys, `HOME` is undefined (workspace per conversation vs shared `/opt/skills`), and any shell command can read those credentials.
  - The Binance endpoints that `baw` calls are missing from the topology and the egress allowlist, so every `baw` call would be gated as EGRESS.
  - The skill install source (URL, file, npm) and its trust model are neither decided nor deferred.
- **Fix:** Define a `CommandPolicyPort` in `:core`. `:skills` contributes rules to it and the shell tool's `assess` consults it, so shell commands that match an installed skill's policy are classified by that policy. Either drop the "skill registers tools" ERD edge or state when it applies. Add a rule on where skill state and credentials live, and that they are excluded from backup and from other conversations' workspaces. Add Binance to the topology. Add "skill install source and trust" to Deferred.

### F3 (H): AD-2 moves the TaskRunner and AgentService into epic 1, contradicting the ticket slicing, and forbids R2's connection test

- **Sources:** tickets.toml l.15 (epic 2: "satu titik masuk task runner yang hidup di FGS"); epic-chat-dan-provider Boundaries l.32 ("Bukan di sini: tool call dan agent"); epic 3 `after` (needs "task runner di FGS" from epic 2); R2 l.25 (tes koneksi from the app); epic-chat Done when l.26 ("berhasil atau gagal beserta alasannya"); C5 l.43 ("FGS hanya selama ada tugas").
- **Spine:** AD-2 "Setiap giliran LLM — chat polos ... masuk lewat `TaskRunner.submit` ... di-host `AgentService` (FGS)"; "UI dan scheduler tidak pernah memanggil `LlmPort` langsung"; AD-5 "Task, TaskEvent | epic-agent-shell (skema awal epic-chat-dan-provider)".
- **Gap:**
  - Epic 1 must now build the TaskRunner, AgentService (FGS `specialUse`) and the TaskEvent log. That is a scope change the tickets do not reflect, and it should go back to the PO.
  - It is unclear whether every plain chat turn raises an FGS and its notification. Under C5 the FGS exists "hanya selama ada tugas", and this case reads as the opposite.
  - The provider connection test and model listing (needed for `ModelProfile.supportsTools`) have no allowed path. The UI may not call `LlmPort`, and a connection test is not a task.
- **Fix:** In AD-2, state which epic builds the minimal TaskRunner and AgentService and that the tickets need a replan note. State whether a CHAT origin with no tool calls starts the FGS; one option is to start it only after the first tool call or when the app goes to the background. Add a `ProviderAdminPort` in `:llm` (`testConnection`, `listModels`, quota read) that sits outside "giliran LLM" and that the UI may call.

### F4 (H): R6 reliability inputs dropped: battery and autostart onboarding, notification permission, missed alarms, and an approval-blocked queue

- **Sources:**
  - R6 l.29 ("app memandu pengecualian optimasi baterai dan pengaturan autostart per merek HP").
  - Notes Assumption l.89 (this onboarding is in v1).
  - Open question l.103 (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` policy and battery numbers; "Menunggu: R6").
  - Done when l.57 (7 days on Xiaomi and Samsung).
  - epic-tugas-terjadwal Done when l.26 and l.28.
- **Spine:** AD-16 covers alarms, boot re-arming and the time zone; Deferred covers only "Izin exact alarm ... dan fallback bila ditolak".
- **Gap:**
  - Neither the battery-optimisation exemption nor the per-brand autostart guide appears, as decided or as deferred.
  - The l.103 open question is not answered or deferred.
  - `POST_NOTIFICATIONS` (Android 13+), which R6 results and AD-7 scheduled approvals depend on, is absent.
  - The spine has no policy for a missed or late alarm (run late or skip), for force-stop on OEM ROMs, or for overlapping runs of one ScheduledTask.
  - AD-3 (single FIFO task) combined with AD-7 (a SCHEDULED task "pause" until approval or expiry) means a scheduled task awaiting approval blocks every chat and task behind it, keeps the FGS up and may keep userland alive. That runs against C5/C6. The expiry length is also unspecified.
  - `SCHEDULE_EXACT_ALARM` vs `USE_EXACT_ALARM` is not chosen. `USE_EXACT_ALARM` is fine for GitHub and F-Droid and removes a user step for R1.
- **Fix:**
  - Add a row to Deferred: battery exemption, OEM autostart guide, notification permission. Owner epic-tugas-terjadwal, bound by AD-16.
  - Add to AD-16 a misfire policy and "one run per ScheduledTask at a time".
  - Add to AD-3/AD-7: a task suspended on approval releases the runner slot. It is persisted as `AWAITING_APPROVAL`, userland is stopped, and it resumes as a new queue entry. Set a default expiry.

### F5 (M→H): C11's "empty or broken tool call" ends a task silently under the loop as drawn, and there is no per-task call budget

- **Sources:** C11 l.49 ("tool call kosong, 'no endpoints support tool use' ... harus ditangani"; "satu tugas butuh sekitar 10–20 panggilan" vs 50 req/day); epic-hemat-kuota Done when l.27 ("tidak menghentikan tugas diam-diam; pengguna melihat apa yang terjadi").
- **Spine:** the sequence diagram has `loop sampai tanpa tool_calls`; `LlmError.MalformedToolCall`, `ToolsUnsupported`; there is no TaskEvent for repair or retry.
- **Gap:** A free model that announces an action in prose but emits no `tool_calls` (the "empty tool call" case) satisfies the loop exit and ends as `TaskFinished`, which is exactly the silent failure the epic forbids. The spine does not say whether the router (`:llm`) or the runner (`:core`) owns retry and repair, so epics 2 and 4 can both implement it. The loop has no maximum step or LLM-call budget, so a daily scheduled task can exhaust a 50-request/day quota.
- **Fix:** In AD-11/AD-12, name the owner: the router retries or rotates on `MalformedToolCall`/`ToolsUnsupported`, and the runner owns any "suspected empty tool call" heuristic. Add `TaskEvent` subtypes (`LlmRetried`, `ToolCallRejected`) so the user can see what happened. Add a per-task step and call cap to AD-2/AD-3, with `TaskFailed(BUDGET_EXHAUSTED)`.

### F6 (M): C2, C3 and C13 are never mentioned, and layperson-readable cards conflict with C9

- **Sources:** C2 l.40 (no visible terminal), C3 l.41 (no control of other apps, no Accessibility), C13 l.51 (not an Operit fork; LGPL is reference only); K1 (orang awam; "kesederhanaan adalah nilai utama"); epic-skill-pihak-ketiga Boundaries l.32 (no skill that controls other apps).
- **Spine:** the frontmatter `binds:` lists only R1–R12. No AD binds C2, C3, C4, C5, C10, C11, C12 or C13. C4, C5, C10, C11 and C12 are satisfied implicitly (AD-1/AD-11, AD-2/AD-16, AD-11, AD-12, Stack), but C2, C3 and C13 have no trace at all.
- **Gaps:**
  - **C3:** the spine has no manifest or permission rule, such as no `BIND_ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, Shizuku or MediaProjection. Nothing stops an epic from adding these.
  - **C2 and the plain-language tone:** AD-10 forbids LLM text in cards and says args appear as "field data berlabel". For an orang awam, a raw `npm install foo` or `rm -rf ...` in an approval card is effectively a terminal. The spine gives no deterministic, plain-language description path.
  - **C13:** there is no rule that Operit code is not copied. That matters for the license choice deferred to epic 1.
- **Fix:**
  - Add a short AD on the permission allowlist: forbidden permissions are listed, and a new permission is a spine change.
  - Add `Tool.describe(args): UiText`, a deterministic plain-language summary from `strings.xml` shown first on the card, with raw args under an expandable detail. If the LLM's stated reason is shown at all, it goes in a visually separate area labelled as AI text.
  - Add C2/C3/C13 bindings and a convention line saying Operit is reference only and no code is copied.

### F7 (M): The quota status has two sources (AD-4 vs AD-5)

- **Sources:** R12 l.35 ("sisa kuota dan status provider terlihat"); touch point l.72 (provider quota endpoint, owner epic-hemat-kuota).
- **Spine:** AD-4 "Prevents: status kuota dihitung dari sumber kedua" and "indikator provider adalah proyeksi baca" of the event log; AD-5 "RouterState (rotasi, cooldown, sisa kuota) | `:llm` router". The quota endpoint is absent from the topology and appears only in Deferred as "sumber angka kuota".
- **Gap:** The quota and provider status come from RouterState and a provider endpoint, not from TaskEvents, so AD-4's own "prevents" clause is violated by AD-5.
- **Fix:** Name RouterState (fed by the quota endpoint and by `RateLimited` responses) as the single source of the quota indicator. Narrow AD-4 to "per-task provider history = ProviderSwitched events". Add the quota endpoint to the topology.

### F8 (M): The architecture half of two "Menunggu: architecture" questions is only answered implicitly

- **l.92, Play distribution ("Menunggu: distribusi dan architecture"):** the spine defers Play to epic-rilis-publik and says the architecture "tidak bergantung pada Play". It does not state the architectural facts the PO needs:
  - AD-14's runtime rootfs download and runtime `npm install` are what Play's executable-code-download policy targets (C7 l.45).
  - FGS `specialUse` needs a Play declaration.
  - A Play build would therefore need a separate flavor, such as a bundled rootfs and no runtime native download.

  Fix: add these to the Deferred row so the epic-7 decision has its inputs.
- **l.96, the Kotlin agent library (Koog, LangChain4j, or custom; "Menunggu: architecture"):** AD-11 says "tanpa SDK vendor atau framework agent", which is an answer, but it does not cite the question or give a rationale (C4; jank-free control of the event log and gate; F-Droid; dependency weight). Fix: add one sentence in AD-11 closing l.96.

### F9 (M): The search backend was to be chosen at architecture but is deferred again, and the default is fragile

- **Sources:** epic-browsing-web Notes l.41 ("Layanan pencarian dipilih saat architecture; utamakan yang tanpa key ..."); Open question l.94.
- **Spine:** topology "DuckDuckGo HTML default / SearXNG / Brave opsional"; Deferred "Backend pencarian default final (DuckDuckGo HTML tidak resmi) | epic-browsing-web".
- **Gap:** The choice was assigned to architecture, and the spine hands it back. The provisional default scrapes an unofficial endpoint whose terms disallow automated queries. It may also add a NonFreeNet flag, and it can break or be rate-limited, which would fail the "satu jawaban dari pencarian web" Done-when for the awam tester.
- **Fix:** Either confirm DDG HTML as the v1 default and say so in a Decision line, with the known risks and SearchPort fallback behaviour (backend error leads to an optional-backend prompt), or record explicitly that the decision is returned to epic-browsing-web and why.

### F10 (M): The FGS alone does not keep the CPU awake with the screen off

- **Source:** epic-agent-shell Done when l.26 ("tugas panjang tetap selesai saat layar mati karena berjalan di FGS"); Done when l.57.
- **Spine:** AD-2 covers the FGS only.
- **Gap:** A foreground service keeps the process alive but does not prevent CPU suspend. A long `npm install` or LLM stream with the screen off needs a `PARTIAL_WAKE_LOCK` for the life of the task, released when the task suspends on approval (see F4).
- **Fix:** Add one line to AD-2: AgentService holds a partial wake lock while a task is RUNNING.

### F11 (M): Shell egress depends on command-string heuristics, and the `npm install` example is ambiguous

- **Sources:** R8 l.31 and R10 l.33 (outbound data never without approval); Done when l.58; R4 / epic-agent-shell Done when l.25 (coding with `npm install`).
- **Spine:** AD-9 "Perintah shell yang memanggil klien jaringan di luar allowlist epic-agent-shell (mis. instal paket dari registry) dinilai EGRESS".
- **Gaps:**
  - The sentence can be read two ways: as saying `npm install` is outside the allowlist, so every install needs confirmation, or as an example of what the allowlist covers.
  - Running any agent-written script (`node x.js`, `python y.py`) can open sockets that no command-string heuristic sees, and proot provides no network isolation.
  - The spine does not state whether running agent-authored code counts as EGRESS-capable (default-deny) or is an accepted residual risk.
- **Fix:** Reword the example. Add the rule that running workspace code is assessed as EGRESS unless the task has already received approval for that script, or record the residual risk explicitly so epic-browsing-web's prompt-injection test is designed against it.

### F12 (M): C8 sandbox boundary: proot bind mounts are unspecified

- **Source:** C8 l.46 ("Shell disandbox di folder app"); K10.
- **Spine:** AD-14 defines the workspace path but not what the guest can see.
- **Gap:** proot runs as the app UID. Unless it is configured otherwise, it can reach `/data/data/<app>/` (the Room event log, the encrypted key blob) and `/sdcard` if storage permission were added. AD-15 keeps the key out of env, but not out of reach of the filesystem.
- **Fix:** Add to AD-14 an explicit bind list (rootfs, `/workspace/<conversationId>`, `/opt/skills`, minimal `/proc` and `/dev`). State that there is no bind of the app's `databases/`, `shared_prefs/`, the secret store or external storage. State that proot is not a security boundary and that the AD-7 gate is the real control.

### F13 (L): Chat history storage and backup are only half answered

- **Source:** Open question l.101 (key and chat history: on device, encrypted, backup?).
- **Spine:** AD-15 covers the key and excludes it from Auto Backup. Deferred "Retensi, ekspor, dan backup event log" says "tidak mengubah kontrak".
- **Gap:** With the default `allowBackup` and no rules, Room (chat history, tool outputs) goes into Auto Backup from the first epic, which is a privacy default decided by omission. Whether Room is encrypted at rest is not stated.
- **Fix:** Add a convention that backup rules exclude Room and the secret store until the deferred decision is made, and state "Room not encrypted at rest in v1 (device encryption only)" or the opposite.

### F14 (L): `schedule_create` from chat lets an injection persist

- **Sources:** R6 (create via chat); R10.
- **Spine:** AD-6 `schedule_` prefix; AD-16 `schedule_create`; no risk class is given.
- **Gap:** If `schedule_create` is `LOCAL_WRITE`, an injected page can install a recurring task that will re-run injected instructions unattended.
- **Fix:** Classify `schedule_create`/`schedule_update` as needing confirmation (create from the UI is already a user action).

### F15 (L): Sources for R5 should come from typed events

- **Sources:** R5 l.28 ("pengguna bisa membuka halaman sumbernya"); epic-browsing-web Done when l.25.
- **Spine:** AD-9 renders links as links, and AD-10 covers cards only.
- **Gap:** If source links are only whatever URLs the LLM writes in markdown, an injected page can plant a phishing link presented as a source.
- **Fix:** Render the source list from `ToolResult` metadata of `web_search`/`web_read` (AD-10 style), and open it with an external browser intent.

### F16 (L): AD-14's W^X sentence reads stricter than the design

- **Source:** C7 l.45.
- **Spine:** AD-14 "tidak ada binary yang dieksekusi dari direktori yang bisa ditulis app", while `sh`, `node`, `git` and `baw` live in the rootfs under `filesDir`.
- **Gap:** The design complies only because proot routes guest `execve` through the loader in `nativeLibraryDir`. As written, the sentence will confuse the spike.
- **Fix:** Reword: "No direct kernel `execve` from a writable directory; guest binaries are loaded by the proot loader from `nativeLibraryDir`."

## 2. Constraint matrix C1–C14

| C | Status in spine | Where / note |
| --- | --- | --- |
| C1 open source, free, no server, user endpoint | Respected | AD-17; topology "Tanpa server Lumynoid"; AD-15 |
| C2 no visible terminal | **Not mentioned** | Implicit via AD-10 cards only; see F6 |
| C3 no control of other apps / no Accessibility | **Not mentioned** | No permission rule; see F6 |
| C4 own native Kotlin harness | Respected, implicit | AD-1, AD-11 ("tanpa ... framework agent"); not bound by ID |
| C5 event-driven, FGS only during tasks | Respected with holes | AD-2, AD-16; whether plain chat starts the FGS (F3); approval-wait keeps the FGS (F4); wake lock (F10); `specialUse` vs `dataSync` 6h rationale not stated |
| C6 userland only while shell is used; phantom killer; memory | Respected | AD-3, AD-14; hole: userland kept alive while awaiting approval (F4) |
| C7 W^X; Play native-code download | Respected (wording, F16); Play half deferred without stating impact (F8) | AD-14 |
| C8 shell sandboxed in app folder; web is data | Partial | AD-8 ✓; sandbox bind list missing (F12) |
| C9 cards from tool-call data | Respected | AD-10 |
| C10 API key only; no Claude subscription; no OpenCode Zen promise | Respected, implicit | AD-11, Deferred v1.x; preset list deferral should note "no OpenCode Zen" |
| C11 free-quota design; unreliable tool calling | Partial | AD-11/12 errors; empty tool call and per-task budget missing (F5) |
| C12 Kotlin + Compose, Android only | Respected | Stack |
| C13 new app, Operit reference only | **Not mentioned** | F6 |
| C14 GitHub Releases + F-Droid; Play open | Partial | Topology ✓; F-Droid inclusion fails on prebuilt proot (F1); NonFreeNet not discussed |

## 3. Open questions in initiative Notes

| Line | Question | Waiting on | Spine status |
| --- | --- | --- | --- |
| 92 | Play: FGS + bundled binary + runtime `npm install` | **distribusi dan architecture** | Deferred to epic-rilis-publik; architecture impact not stated (F8) |
| 93 | Developer verification 2027 | — | Deferred (Distribusi row) ✓ |
| 94 | Search service | R5 (+ epic note: architecture) | Provisional default + deferred again (F9) |
| 95 | Userland size, download, npm limits | R4, R7 | Deferred to the spike ✓; download-on-first-use + checksum decided ✓ |
| 96 | Kotlin agent library | **architecture** | Implicitly answered in AD-11; not cited (F8) |
| 97 | Reliable free tool-calling models | R12, R2 | Deferred ✓ |
| 98 | PSE Komdigi | — | Deferred ✓ |
| 99 | Project license | — | Deferred to the epic-1 hitl story ✓ (C13 interaction, F6) |
| 100 | F-Droid inclusion vs dependencies | (epic note: architecture) | AD-17, but the check is handed to future PRs and fails on proot (F1) |
| 101 | Key and history storage, encryption, backup | — | Key ✓ AD-15; history deferred, backup default unaddressed (F13) |
| 102 | UI language, min Android | — | Language deferred ✓; minSdk 29 `[ASSUMPTION]` ✓ |
| 103 | Battery-optimisation policy, battery numbers | R6 | **Absent** (F4) |

## 4. Decisions and epic notes

| Source | Text | Status |
| --- | --- | --- |
| Initiative l.104 | Order: slice → architecture → ux | n/a |
| Initiative l.106 | Platform foundation is the first story of epic 1 | Consistent; but AD-2 adds the runner and FGS to epic 1 (F3) |
| Initiative l.107 | Shared decisions in the spine: harness and registry contract, provider adapter, userland lifecycle, card model, key storage | All present (AD-2/6, AD-11, AD-14, AD-10, AD-15) |
| Initiative l.108 | Epic 3 only calls the epic-2 runner; epic 4 owns context with seams for 5 and 6 | AD-2/AD-16 ✓; AD-13 ✓ |
| epic-agent-shell l.43 | Opening userland spike | Deferred row ✓ |
| epic-agent-shell l.44 | Split harness and cards from userland if more than 12 stories | Supported by AD-1 modules ✓ |
| epic-chat l.42–43 | Foundation first; license hitl first | Deferred row ✓ |
| epic-browsing l.41 | **Search chosen at architecture**, keyless preferred, key optional | Partial (F9) |
| epic-rilis l.42 | **F-Droid inclusion checked at architecture** | Fails / handed on (F1) |
| decisions "Urutan berikutnya" #3 | architecture: harness, provider adapter, userland sandbox, scheduler, **distribution path** | Distribution: GitHub/F-Droid ✓, Play impact missing (F8) |

## 5. Touch points (initiative Boundaries l.71–77)

| Touch point | Owner | In spine |
| --- | --- | --- |
| LLM provider APIs | epic 1 | ✓ topology, AD-11 |
| Provider quota endpoint (OpenRouter) | epic 4 | Deferred only, not in topology (F7) |
| npm registry, rootfs source | epic 2 | ✓ topology; npm egress rule ambiguous (F11) |
| Web search service | epic 5 | ✓ SearchPort (F9) |
| Binance Agentic Wallet (`baw`) | epic 6 | **Not in topology**; Binance endpoints are not in the egress allowlist (F2) |
| GitHub Releases | epic 1 | ✓ CI + Releases |
| F-Droid | epic 7 | ✓ topology; inclusion fails (F1) |
| Tracer path l.78 | — | ✓ runtime sequence matches |

## 6. Done-when items at risk from the spine as written

- l.57, 7-day scheduled run on Xiaomi and Samsung: F4, F10.
- l.59, `baw execute` only after confirmation: F2.
- l.58, injection page cannot trigger an unapproved action: F11, F14, F15.
- l.61, app submitted to F-Droid: F1.
- epic-hemat-kuota l.27, no silent stop on empty or broken tool calls: F5.
- epic-chat l.26, connection test with reason: F3 (no allowed path from the UI).

## 7. Covered well (no action)

- R1–R12 each map to modules and ADs.
- Fixed-component chat UI (K9): streaming markdown, code blocks, activity, approval and preview cards, conversation list, scheduled-task screen.
- Prompt injection: AD-7 + AD-8 + AD-10.
- Single-writer event log for R9.
- Single provider-switching decorator for R12.
- Context seams that epics 5 and 6 depend on (AD-13).
- Secret handling for LLM keys (AD-15).
- Telegram, GenUI and Sign in with ChatGPT deferred to v1.x with named seams.
