---
type: architecture-review
lens: adversary
scope: update 2026-11-14 (C14 GitHub-Releases-only, application id dev.lusalhi.lumynoid, AD-17, AD-25, English UI, Deferred)
target: _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
units: _bmad-output/initiative-lumynoid/tickets/tickets.toml (7 epics)
created: '2026-11-14'
verdict: needs-amendment
---

# Adversarial review: Lumynoid spine, distribution and identity update

## Verdict

The amendment is internally consistent with C14 and the ticket's Decisions. No text in the spine still requires F-Droid or Play. But removing F-Droid also removed two backstops the spine was quietly relying on: F-Droid's build server, which scanned the resolved dependencies, and a store, which arbitrated updates. Nothing replaced them. Now that the only update path is a user installing an APK by hand over the old one, the app's identity has to stay stable from release to release: versionCode order, signing key, manifest identifiers, and persisted card text. The spine leaves each of these to the epic that first touches it.

I found **no Critical holes and five High ones**. Four of them are about epics that run in parallel (epic-tugas-terjadwal and epic-hemat-kuota both come after epic 2) or that write different projections of the same event log. **Fix U1 to U5 before the first APK is published.** After that, versionCode, the signing key, manifest authorities and persisted data are permanent, so the cost of fixing them jumps.

## Method

Same as the first adversary pass. Two teams that never talk read the spine as literally as possible, and each makes the cheapest choice it allows. A finding counts only if both choices obey every AD and the result fails to integrate, fails to install or update, or is unsafe. The fixes are worded as Rule text in Indonesian, ready to paste. I did not edit the spine.

Severity: **High** means the units won't integrate, an update or install fails for real users, or a financial card can be misread. **Medium** means rework or a narrow hole. **Low** means wording or residue.

## Findings at a glance

| # | Sev | Pair | Clauses exploited | Minimal fix |
|---|---|---|---|---|
| U1 | High | epic-tugas-terjadwal × epic-hemat-kuota | Konvensi Versi (`v0.<epic>.<patch>`, versionCode "di source"), AD-19 (CI check on PRs only), AD-25 (sideload only) | Tighten Konvensi Versi + AD-25: release only from `main`, versionCode strictly increasing, checked by CI |
| U2 | High | epic-chat-dan-provider × epic-agent-shell × epic-tugas-terjadwal | Konvensi Package/application id, AD-10 ("deep-link"), AD-25 (diagnostics share sheet, `.debug` coexistence) | New Konvensi row "Identitas manifest" + tighten AD-10 |
| U3 | High | epic-agent-shell × epic-tugas-terjadwal (× epic-skill-pihak-ketiga) | AD-6 `describe(args): UiText`, AD-10 (titles from `strings.xml`, cards from events), AD-4 (projections), Konvensi String UI (English, translate later) | Tighten AD-10: titles resolved at render time from a stable key, never persisted as text or as an `R` id |
| U4 | High | epic-skill-pihak-ketiga × epic-agent-shell | AD-21 (deterministic FINANCIAL summary), AD-10, Konvensi String UI (English) with device locale `id-ID` | New Konvensi row "Format angka dan waktu" |
| U5 | High | epic-chat-dan-provider × epic-agent-shell × epic-rilis-publik | AD-25 (one key, never replaced), Topologi ("kunci di GitHub Actions secrets"), public repo, proot CI build | Tighten AD-25: key custody, escrow, signing job isolation |
| U6 | Medium | epic-hemat-kuota × epic-browsing-web | AD-17 ("dependensi baru dicek di PR"), F-Droid scanner gone | Tighten AD-17: CI gate on the resolved release classpath |
| U7 | Medium | epic-chat-dan-provider × epic-rilis-publik | AD-25 Distribusi, AD-24 permission allowlist, topology (no update host) | Tighten AD-25: update path and release asset contract |
| U8 | Medium | epic-tugas-terjadwal × epic-chat-dan-provider | AD-25 (`.debug` suffix; only the release key registered), Done-when 7-day Xiaomi/Samsung test | Tighten AD-25: which builds testers install |
| U9 | Medium | epic-agent-shell × epic-tugas-terjadwal | Konvensi Serialisasi (no `@SerialName` rule), package names derived from the application id, AD-19 (`Unknown` fallback) | Tighten Konvensi Serialisasi |
| U10 | Low | epic-agent-shell × any epic adding an AAR with `.so` | AD-17 ("binary prebuilt" in Prevents vs. Rule scoped to own native code), AD-14 (16 KB) | One sentence in AD-17 |
| U11 | Low | epic-chat-dan-provider × epic-rilis-publik | AD-25 Diagnostik (local crash export) + unspecified R8 | Archive the mapping file per release |
| U12 | Low | text residue | AD-17 Prevents, AD-25 `includeInBundle`, ticket notes, decisions doc | Wording cleanups (list below) |

---

## U1 — Parallel epics publish APKs that can't update over each other (High)

**Pair:** epic-tugas-terjadwal (epic 3) × epic-hemat-kuota (epic 4). In tickets.toml both depend only on epic 2, so they run in parallel. AD-19 says as much.

**Why each team is within the letter:**

- The Versi convention: "`versionCode`/`versionName` di source, dinaikkan di tag; tiap epic merilis `v0.<epic>.<patch>`" (versionCode and versionName live in source and are bumped at the tag; each epic releases `v0.<epic>.<patch>`). Nothing says which branch is tagged or how versionCode is derived.
- Team 4 derives `versionCode = minor*100 + patch` and ships `v0.4.0` (code 400) from its epic branch. Team 3 then ships the fix `v0.3.2` (code 302).
- Alternatively, both teams bump a counter in source on their own branches and both reach `versionCode = 12`.
- AD-19 says "CI menolak dua PR yang mengklaim nomor versi DB yang sama" (CI rejects two PRs that claim the same DB version). That check only works if both branches merge into `main` before release. An APK tagged on an epic branch never passes through that check.

**Failure (the user's only update path is a manual sideload, per C14):**

- With the first scheme, a user on `v0.4.0` who downloads GitHub's "Latest" release (latest by date, which is `v0.3.2`) gets "App not installed", because Android refuses a versionCode downgrade. No store sits in between to explain the error.
- With the second scheme, the install succeeds because Android allows an equal versionCode. But Room version 5 now means `scheduled_task` in one APK and `router_state` in the other. Room's identity-hash check fails at startup. AD-19 forbids destructive fallback in release builds, so the app crashes on launch until the user clears its data, and history and keys are lost with it.

**Minimal fix:** tighten the Versi convention and add one clause to AD-25:

> **Versi:** APK rilis hanya dibangun dari tag di `main` oleh satu workflow rilis di CI. `versionCode` naik ketat di setiap rilis; CI menolak tag yang `versionCode`-nya tidak lebih besar dari rilis sebelumnya, dan menolak tag yang skema Room-nya tidak berada di garis migrasi `main`. `versionName` mengikuti urutan rilis (`v0.<urutan>.<patch>`), bukan nomor epic; nomor epic dicatat di release notes.

## U2 — Identifiers derived from the application id are left to each epic (High)

**Pair:** epic-chat-dan-provider (AD-25 diagnostics: "diekspor pengguna lewat share sheet", exported by the user via the share sheet, which needs a `FileProvider`) × epic-agent-shell (`approval_needed` notification) × epic-tugas-terjadwal (`task_result` notification). AD-10 says notifications "membuka kartu di app lewat deep-link" (open the card in the app through a deep link).

**Why each team is within the letter:**

- The Package/application id convention gives a string, `dev.lusalhi.lumynoid`, and a debug id, `dev.lusalhi.lumynoid.debug`. It doesn't say how code and the manifest refer to it.
- Team 1 writes `android:authorities="dev.lusalhi.lumynoid.fileprovider"` literally, with `<files-path path="."/>`.
- Team 2 reads "deep-link" literally. It registers an exported `<intent-filter>` for `lumynoid://approval/{taskId}`.
- Team 3 sends an explicit `PendingIntent` with an `EXTRA_TASK_ID` extra and adds its own route parsing in `MainActivity`.
- A library module such as `:scheduler` builds its alarm action as `"dev.lusalhi.lumynoid.scheduler.FIRE"` from its namespace. `:app` checks `context.packageName + ".FIRE"`. That works in release builds but never matches in `.debug` builds.

**Failure:**

- The literal authority makes debug and release builds conflict (`INSTALL_FAILED_CONFLICTING_PROVIDER`). That breaks the exact guarantee AD-25 gives: "build debug menimpa build release di HP uji" (a debug build overwriting the release build on a test phone).
- Both variants claim `lumynoid://`, so users see a chooser.
- An exported custom scheme lets any web page or app open an approval card at a moment it picks. That runs against AD-10's intent that approval happens only inside the app's own flow.
- `path="."` exposes `filesDir`, which holds Room, the secret store and the workspace, behind every URI the app grants.
- `MainActivity` ends up with two routing dialects.

**Minimal fix:** add a convention row and tighten AD-10:

> **Identitas manifest:** literal `dev.lusalhi.lumynoid` hanya muncul di `applicationId` (`:app/build.gradle.kts`). Manifest memakai `${applicationId}` (authority `FileProvider` tunggal di `:app`: `${applicationId}.files`, path hanya `cache/export/`); kode memakai `context.packageName`. Namespace modul `dev.lusalhi.lumynoid.<modul>`, termasuk `:app` = `dev.lusalhi.lumynoid.app`. Alias Keystore tetap (`lumynoid_secret_v1`) dan tidak diturunkan dari nama package.
>
> **AD-10 (tambahan):** "Deep-link" berarti `PendingIntent` eksplisit (`FLAG_IMMUTABLE`) ke satu activity `:app` dengan target navigasi bertipe `{taskId, eventId}`, dibuat oleh satu helper di `:app`. v1 tidak mendaftarkan intent-filter skema kustom atau http yang diekspor.

## U3 — Card text is persisted differently by each projection (High)

**Pair:** epic-agent-shell (activity and approval cards) × epic-tugas-terjadwal (`task_result` notification and the scheduled conversation). epic-skill-pihak-ketiga is a third consumer.

**Why each team is within the letter:**

- AD-6 gives `describe(args): UiText`. AD-10 says titles come from `strings.xml` and that cards are rendered "hanya dari `TaskEvent` bertipe" (only from typed `TaskEvent`s). Neither says whether the title is computed when the event is written or when it is shown.
- `:core` is pure Kotlin under AD-1, so `UiText` cannot hold an Android resource type.
- Team 2 stores `UiText.Res(id = 0x7f12003a, args)` inside the `ToolCallRequested` JSON.
- Team 3 renders the English string at write time and stores `"Run daily summary"` in the event, because its notification has to be built from a receiver that has no tool registry.

**Failure:** every epic ships an APK update (U1), and resource ids are reassigned between builds. After an update, old cards show the wrong sentence or throw `Resources.NotFoundException` while the projection is built. That breaks R9, the action log the user is supposed to be able to open. Team 3's approach freezes English into history, so the "terjemahan bisa menyusul" (translation can come later) promise from the language decision never covers old conversations. There is a further problem. When a skill is uninstalled or a tool is renamed, the current registry can no longer `describe` its old events. AD-10 then forces a choice between showing nothing and parsing text, and the two teams will choose differently.

**Minimal fix:** tighten AD-10:

> Judul kartu dan notifikasi di-resolve saat render, bukan disimpan sebagai teks atau id `R`. `UiText` di `:core` adalah `UiText(key: String, args: Map<String, UiArg>)` dengan `key` stabil (mis. `card.shell.exec`) yang dipetakan ke `strings.xml` oleh satu registry di `:app`. Event menyimpan `key` + args; key yang tidak dikenal dirender sebagai judul generik plus field data mentah, tidak pernah crash.

## U4 — A FINANCIAL amount in an English sentence with Indonesian separators (High)

**Pair:** epic-skill-pihak-ketiga (AD-21: "ringkasan deterministik: aset, jumlah, tujuan, dan jaringan", the deterministic summary of asset, amount, destination and network) × epic-agent-shell (owns the `ApprovalCard` component and the time display on activity cards).

**Why each team is within the letter:**

- The convention fixes the UI language to English, and the app ships only `values/strings.xml`. The device locale, however, is still `id-ID` for the target users.
- Team 6 formats the amount with `NumberFormat.getInstance()`, which gives `1.000` for one thousand on an `id-ID` device.
- Team 2 drops it into the English template `"Send %1$s %2$s to %3$s"`.
- Team 3 formats schedule times with `Locale.US`, giving `3:00 AM`. Team 2 uses the device default, giving `03.00`.

**Failure:** an English sentence primes the reader for English separators, so "Send 1.000 USDT" reads as one USDT and gets approved as if it were small. R8 says the card is the safeguard, and here the card itself misleads. Mixed time formats across screens also weaken the R6 promise that tasks run on time, because users can't tell which time a task is set for.

**Minimal fix:** add a convention row:

> **Format angka dan waktu:** `:core` dan adapter hanya mengirim nilai bertipe (`BigDecimal`, epoch millis + `zoneId`) di args `UiText`; pemformatan hanya di `:app` lewat satu `Formatters`. Jumlah di kartu `FINANCIAL` ditampilkan verbatim tanpa pemisah ribuan (titik desimal, mis. `1000.5 USDT`) plus satuan; waktu memakai format sistem perangkat (`DateFormat.is24HourFormat`).

## U5 — The one never-replaced key has no custody rule (High)

**Pair:** epic-chat-dan-provider (creates and registers the key) × epic-agent-shell (adds the NDK proot build to CI) × epic-rilis-publik (signs `v1.0.0` months later).

**Why each team is within the letter:**

- AD-25 says "satu kunci … tidak pernah diganti" (one key, never replaced). The topology says the key is "disimpan di GitHub Actions secrets" (stored in GitHub Actions secrets).
- Team 1 generates the key in a throwaway environment and puts it only into the repository secret. GitHub secrets are write-only, so that is now the only copy.
- Team 2 adds a `pull_request_target` or same-job workflow so contributor PRs can reuse the proot build cache. The repo is public. That workflow now runs code from forks in a context that can read the repository-level signing secret.
- Separately, if the developer-verification flow asks for an APK signed with the key to prove ownership of the package name (check the current Android Developer Console flow), Team 1 needs a signing path outside the tag workflow. The cheapest one is to export the key to a laptop.

**Failure:** if the repo, the secret or the account is lost, no update can ever be signed. Every user is stranded on their installed version, because updates need the same key and AD-25 forbids replacing it. If the key leaks through a PR workflow, anyone can publish a valid update to `dev.lusalhi.lumynoid`. With manual sideloading, users can't tell a hostile update from a real one. The README fingerprint helps only the few users who check it.

**Minimal fix:** tighten AD-25's signing clause:

> Kunci rilis dibuat sekali secara offline; salinan terenkripsi disimpan di dua lokasi di luar GitHub milik pemilik, dan fingerprint SHA-256 sertifikatnya di-commit di repo dan README. Di CI, kunci hanya ada di GitHub Environment `release` yang terproteksi (hanya tag di `main`, persetujuan manual); workflow `pull_request`/`pull_request_target` tidak pernah punya akses ke environment itu. Penandatanganan di luar workflow rilis (mis. APK bukti kepemilikan untuk verifikasi developer) memakai `workflow_dispatch` di environment yang sama, bukan salinan lokal. Rotasi hanya bila kunci bocor, lewat lineage APK Signature Scheme v3.

## U6 — Transitive Google dependencies get through without F-Droid's scanner (Medium)

**Pair:** epic-hemat-kuota × epic-browsing-web, or any epic that bumps a version catalog entry.

**Why within the letter:** AD-17 says "Dependensi baru dicek terhadap aturan ini di PR yang menambahkannya" (new dependencies are checked against this rule in the PR that adds them). A version bump, or a library that pulls in `com.google.android.gms:play-services-basement` or `com.google.android.play:*` transitively, adds no new line to the PR. Under the old AD-17, F-Droid's build server would have caught it. That backstop is gone, and nothing in CI replaces it.

**Failure:** one epic's release APK quietly carries Play Services stubs or a proprietary artifact from Google Maven. That violates C1 and AD-17, and the v1.x F-Droid path AD-17 says it keeps open closes without anyone noticing.

**Minimal fix:** add to AD-17:

> CI memeriksa classpath runtime rilis yang sudah di-resolve (termasuk transitif) dan gagal bila ada grup `com.google.android.gms`, `com.google.firebase`, `com.google.mlkit`, atau `com.google.android.play`, atau dependensi tanpa lisensi OSI.

## U7 — The update path is undefined, so an epic will improvise one (Medium)

**Pair:** epic-chat-dan-provider (owns the GitHub Releases touch point) × epic-rilis-publik (the "find, install and use" outcome).

**Why within the letter:** AD-25 only says how v1 is distributed. Team 1 adds a startup check against `api.github.com/repos/.../releases/latest` to show an "update available" banner. That is app-initiated egress to a host the topology doesn't list, sending the user's IP and usage timing, but no AD forbids it, since AD-9 covers only tool egress. Team 7 wants a one-tap install and needs `REQUEST_INSTALL_PACKAGES`, which AD-24's allowlist forbids. So it falls back to opening the browser. The result is two different update experiences, and a network call that a later F-Droid build would be required to strip out.

**Minimal fix:** add to AD-25 Distribusi:

> v1 tidak punya pemeriksa atau pemasang update di dalam app; pengguna memperbarui dengan mengunduh APK baru. Setiap rilis memuat tepat satu aset `lumynoid-<versionName>.apk` plus `SHA256SUMS`, sehingga pemantau rilis pihak ketiga bisa dipakai. Semua host yang dihubungi app di luar tool tercantum di topologi.

## U8 — Testers can't install what the epics hand them (Medium)

**Pair:** epic-tugas-terjadwal (7-day test on Xiaomi and Samsung, Done when) × epic-chat-dan-provider (registers only the release key and the base id).

**Why within the letter:** AD-25 sends debug builds to `.debug`, and only `dev.lusalhi.lumynoid` plus the release key are registered for developer verification. Team 3 hands the testers CI debug artifacts. Those are signed with each runner's auto-generated debug keystore and use an unregistered package name. On certified devices where verification applies (Indonesia, per the ticket's Assumption), a sideload from the file manager is blocked. Even where it isn't blocked, the next CI build is signed with a different debug key, so it won't install over the previous one, and the tester loses 7 days of history in the middle of the test.

**Minimal fix:** add to AD-25:

> Pengujian di HP sungguhan memakai APK rilis bertanda tangan (pre-release GitHub dari tag `main`). Build `.debug` hanya dipasang lewat `adb`, dengan debug keystore bersama yang di-commit (bukan rahasia) agar update antar-build CI tidak gagal.

## U9 — Persisted discriminators follow package names (Medium)

**Pair:** epic-agent-shell × epic-tugas-terjadwal. Both add `TaskEvent` subtypes. AD-19 has events "disimpan sebagai JSON berversi" (stored as versioned JSON).

**Why within the letter:** the Serialisasi convention sets `classDiscriminator = "type"` and says nothing about the values. Team 2 adds `@SerialName("tool_result")`. Team 3 leaves its subtypes unannotated, so their stored type becomes the fully qualified name, for example `dev.lusalhi.lumynoid.core.TaskEvent.ScheduleFired`. Package names are tied to the application id by convention. Any later package move, including a namespace cleanup like the `app.lumynoid` rename that just happened, silently turns every old event of that kind into `TaskEvent.Unknown` (AD-19). The data is effectively lost without a crash.

**Minimal fix:** add to the Serialisasi convention:

> Setiap subtipe yang dipersist wajib punya `@SerialName` eksplisit snake_case yang tidak pernah diubah; CI menolak subtipe persisten tanpa `@SerialName`.

## U10 — "Binary prebuilt" is ambiguous for Maven AARs (Low)

AD-17's Prevents names "binary prebuilt". Its Rule restricts only the project's own native code. If one epic adds an AAR that carries `.so` files, for example a bundled SQLite driver for Room 3, whether that is allowed depends on which sentence the reviewer reads. That `.so` might also not be 16 KB aligned, which AD-14 and the Stack table require for proot. **Fix:** add to AD-17: "Native `.so` dari AAR Maven boleh bila berlisensi OSI, arm64-v8a, dan 16 KB-aligned (dicek CI)."

## U11 — Crash exports can't be read without mapping files (Low)

AD-25's crash export is useful only if the stack traces can be read. If a release build turns on R8, which the spine doesn't mention either way, nobody keeps `mapping.txt`, because signing happens only in CI. **Fix:** add to AD-25 Diagnostik: "Bila R8 aktif, `mapping.txt` tiap rilis dilampirkan ke rilis GitHub atau artefak CI permanen."

## U12 — Text that still assumes F-Droid or Play, or is now out of step with the ticket (Low)

Spine:

- **AD-17 Prevents:** "menutup jalur F-Droid di v1.x" (closing the F-Droid path in v1.x). This commits to an F-Droid path for v1.x that the initiative doesn't state. Boundaries only says "di luar v1" (outside v1). Either record it as a deliberate choice that keeps an option open (in the memlog it is only a rationale) or reword it to "menjaga jalur distribusi bebas tetap murah" (keep a free distribution path cheap).
- **AD-25:** `includeInBundle = false` is Play/AAB residue, since v1 produces no AAB. Keep `includeInApk = false` and note the reason: the dependency block is encrypted for Google and adds nothing to a sideloaded APK.
- **AD-17 Binds** dropped `epic-rilis-publik`, but epic-rilis-publik still lists AD-17 in its References. That is harmless, but the two should agree.

Tickets and sources, which the PO should fix since the spine can't:

- epic-chat-dan-provider Notes: "Spine masih menyebut `app.lumynoid`" (the spine still says `app.lumynoid`). This is now false.
- Initiative Notes line 114, "Architecture spine sebagian usang …" (the architecture spine is partly outdated), is resolved by this update.
- epic-rilis-publik Notes: "Aturan inklusi F-Droid … dicek saat architecture memilih dependensi" (F-Droid inclusion rules are checked when the architecture picks dependencies). AD-17 no longer cites F-Droid.
- Initiative C7: "Build untuk Play terikat …; build GitHub/F-Droid tidak" (the Play build is bound by …; the GitHub/F-Droid build is not). This still mentions both stores. Only the GitHub build exists now.
- `lumynoid-decisions.md` "Masih terbuka": "GitHub + F-Droid sudah pasti" (GitHub and F-Droid are certain). This is a spine source and contradicts C14. Add a pointer saying it was superseded by C14.
- The developer account's owner is "milik pengguna" (belongs to the user) in the initiative and epic-chat-dan-provider, but "milik pemilik" (belongs to the owner) in AD-25. In these tickets "pengguna" means the layperson end user, so align on "pemilik proyek" (project owner).

## What held up

- No ruling in the spine depends on F-Droid any more. The deployment topology, Deferred, and AD-24's Prevents are cleanly purged.
- AD-25's single key, plus registering the key and package before the first APK, closes the "update rejected because the key differs" class of failure, as long as U5's custody rule is added.
- `MY_PACKAGE_REPLACED` re-arming (AD-16) and `TaskFailed(INTERRUPTED)` on start (AD-22) already cover an APK update installed while a task is running, which is the normal case under sideloading.
- Room's chained migrations (AD-19) handle users who skip versions, provided U1 keeps one migration line.
