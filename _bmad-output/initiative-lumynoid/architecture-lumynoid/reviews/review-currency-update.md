---
review: currency-and-reality-check (update pass)
target: architecture-lumynoid.md, working-tree diff vs 3d97a50 (AD-17, AD-24 Prevents, AD-25, Conventions, Deferred, Deployment)
lens: every decision touched by the amendment was web-researched or reality-checked, not asserted from training data
reviewed: 2026-11-14
verdict: 'Sound decision, but the rationale is stale and one gap is irreversible. Registering dev.lusalhi.lumynoid and the release key before the first APK is correct and matches the current Google process. The dependenciesInfo DSL is valid on AGP 9.5. However: (1) the "phones reject unverified APKs" rationale is not true for GitHub sideloads in Indonesia today, only from the 2027 global rollout; (2) key custody is unspecified, and the only copy lives in write-only GitHub secrets, under a never-rotate rule where a lost key cannot be recovered.'
---

# Currency Review (update pass): distribution, application id, developer verification

## Verdict

**The amendment's decisions hold up. Their stated rationale is partly out of date, and one operational gap could be fatal.**

- **Confirmed:**
  - Register the package name and signing key before the first APK. This matches Google's current process.
  - `dependenciesInfo { includeInApk = false; includeInBundle = false }` is valid AGP 9.5 DSL.
  - The AD-24 permission allowlist is compatible with Play Protect's sideload fraud blocking in Indonesia.
- **Out of date:**
  - AD-25's "Prevents: HP menolak APK dari developer tak terverifikasi" and the initiative assumption ("mungkin sudah berlaku di Indonesia sejak September 2026") are stale. Since 2026-09-30, Indonesia enforces verification only for installs from **seven participating app stores**. Direct sideloads such as GitHub Releases downloads are **not yet** checked. They will be checked in the **2027 global rollout**, and Google has given no exact date for it.
- **Gap:** AD-25 says the key is "tidak pernah diganti", and Deployment says it lives in GitHub Actions secrets. GitHub secrets are write-only, and Google states that a lost key means you cannot register packages. The spine requires no offline backup.

Method: I read the official developer.android.com developer-verification pages (overview, FAQ, Android Developer Console guide, Play Console guide), the Play Console Help article on package registration, the AGP 9.5 DSL reference and release notes, and secondary news coverage for the rollout status. All checks are as of 2026-11-14.

---

## 1. Android developer verification: verified facts

| Claim in spine, memlog or initiative | Verified status (2026-11-14) | Source |
| --- | --- | --- |
| Indonesia is in the Sept 2026 first wave | **Confirmed, but scoped to stores.** "Starting September 30, 2026, these new developer verification protections go live for users in Brazil, Indonesia, Singapore, and Thailand." The system verifies installs **from these stores only**: Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore, and GetApps. | https://developer.android.com/developer-verification/guides |
| An unverified APK is rejected by the phone (AD-25 Prevents) | **Not true today for GitHub sideloads.** FAQ (updated 2026-07-15): "If you distribute your app through other stores, or if users sideload your app directly, these new verification requirements won't apply to your app yet." The global rollout "for all apps on certified Android devices" is scheduled for **2027**, with no published date. Enforcement covers certified devices on Android 7+ and is delivered via Google Play services. | https://developer.android.com/developer-verification/guides/faq |
| What happens to unregistered apps once enforced | They can be installed only through the user's **advanced flow** (developer mode, restart, 24 h wait, biometric confirmation, "Install anyway"), or via ADB. **Updates** to unregistered apps also fail unless advanced flow stays enabled. ADB installs are exempt. | FAQ, "Advanced flow" section |
| What must be registered: package name + signing cert | **Confirmed.** Steps: enter the package name, then add the **SHA-256 certificate fingerprint**. **For a new package name, the public certificate fingerprint alone is enough.** For an **existing** package name (one with known installs), you must also upload an APK signed with the private key that contains a console-issued snippet in `assets/adi-registration.properties`. | https://developer.android.com/developer-verification/guides/android-developer-console ; https://support.google.com/googleplay/android-developer/answer/16761053 |
| Owner's existing Google developer account | **Confirmed path.** "If you have a Play Console account, use it to complete the verification requirements." Play identity verification "meets this requirement", so no new action is needed. Play Console has a new section for registering apps distributed **outside** Play (Android developer verification page, Package names tab, "Register package name"). A **Limited distribution** account (no ID, no fee) is capped at **20 devices** and cannot be used for public release. Limited can be upgraded to full, but not the reverse. | https://developer.android.com/developer-verification/guides ; https://developer.android.com/developer-verification/guides/google-play-console ; FAQ |
| Key can be rotated or added later | **Yes.** "The Android Developer Console lets you add and verify multiple signing keys for a single package." Play Console Help: "After registration, you can add more keys through the same Upload APK process." On the platform side, APK Signature Scheme v3 key rotation works from API 28, so it is available with minSdk 29. AD-25's "tidak pernah diganti" is therefore a policy choice, not a platform constraint. | FAQ ; Play Console Help 16761053 |
| Lost key | "If you lose your signing key you won't be able to register your packages." There is no recovery path. | FAQ |
| Package-name ownership rules | **No domain-ownership check on the package name.** Note that `dev.lusalhi` does not need proof of owning `lusalhi.dev`. Only organizations must verify a website via Search Console. Conflicts are settled by install share: a key with more than 50% of known installs wins; otherwise any key with at least 50 installs is eligible; otherwise **first-come, first-served**. Others must pick a new name or file an appeal-like request. Play: if another developer already uses the name, the console makes you choose another. | Android Developer Console guide ("Handle duplicate package names") ; FAQ ("Can I register without an eligible key?") |
| Tooling to check status | Three ways: the **Android Developer ID Status API** checks eligibility and registration status, the **Console API** registers names and keys from CI, and **Android Studio Panda 4+** shows registration status when you generate a signed APK. | FAQ ; Android Developer Console guide |
| (Extra) Play Protect sideload fraud blocking in Indonesia | **Compatible.** Enhanced Fraud Protection has been live in Indonesia since Feb 2025 (secondary sources). It blocks internet-sideloaded apps that request SMS, notification-listener, accessibility or screen-capture style permissions. AD-24 already forbids Accessibility and MediaProjection and requests no SMS or notification-listener permissions. | https://indonesiasentinel.com/google-launches-enhanced-fraud-protection-in-indonesia-to-combat-online-scams ; https://security.googleblog.com/2024/02/piloting-new-ways-to-protect-Android-users-from%20financial-fraud.html |

## 2. AGP 9.5: `dependenciesInfo`

**Valid. No change needed.**

- **API status:** In the AGP **9.5** reference, `ApplicationExtension` exposes `fun dependenciesInfo(action: DependenciesInfo.() -> Unit)` and `val dependenciesInfo: DependenciesInfo` (added 4.2.0, no deprecation note). `DependenciesInfo.includeInApk` and `includeInBundle` are `var Boolean`. The interface lives in the new `com.android.build.api.dsl` package, so the AGP 10 removal of the legacy DSL does not affect it.
- **Release notes:** The AGP 9.5.0/9.5.1 release notes list no change to it. The stack pin of 9.5.1 (2026-11-05) with Gradle ≥ 9.6.0 and JDK 17 matches the release notes.
- **Rationale is now missing (cosmetic).** The original reason was F-Droid's "extra signing block" rejection. The block is an encrypted SDK list readable only by Google Play. With Play and F-Droid both out of v1, the setting is still harmless and keeps the v1.x F-Droid path open, but the spine no longer says why it is there.

Sources:
- https://developer.android.com/reference/tools/gradle-api/9.5/com/android/build/api/dsl/ApplicationExtension
- https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/DependenciesInfo
- https://developer.android.com/build/releases/agp-9-5-0-release-notes

---

## 3. Findings (prioritised)

### F-1 (High): Release key custody is unspecified, and the decision makes loss unrecoverable

- **What's wrong:**
  - AD-25 says one key, never replaced. Deployment (line 432) says the key is stored in GitHub Actions secrets.
  - GitHub secrets cannot be read back once set. If the original keystore file is lost (laptop wipe), the key survives only as a CI secret that cannot be exported.
  - If the key is lost, Google cannot register new keys for the package ("won't be able to register your packages"), and every installed copy can never be updated.
- **Fix:** Add a custody rule to AD-25 (or Deployment):
  - The release keystore and its passwords are kept as an **encrypted offline backup in at least two separate places**, owned by the owner. The GitHub secret is a derived copy, not the master.
  - Restoring from the backup and checking the SHA-256 fingerprint is a hitl check in the foundation story of epic-chat-dan-provider.

### F-2 (Medium): AD-25 "Prevents" and the initiative assumption describe enforcement that does not yet apply to GitHub sideloads

- **What's wrong:**
  - "HP menolak APK dari developer tak terverifikasi" is not currently true for GitHub Releases installs in Indonesia. The 2026-09-30 enforcement covers only the seven participating stores.
  - Sideloads are covered from the **2027 global rollout**, with the date still unannounced.
  - The initiative assumption "mungkin sudah berlaku di Indonesia sejak September 2026 ... belum dicek ulang" can now be closed.
  - The epic-chat-dan-provider Done-when line, "APK dari GitHub Releases terpasang di HP sungguhan tanpa peringatan developer tak terverifikasi", **cannot fail today**, so it is not a real test of registration.
- **Fix:**
  - Reword the Prevents line to: "APK dari GitHub Releases diblokir atau butuh advanced flow (install dan update) saat verifikasi developer berlaku global (2027; di Indonesia sejak 30-09-2026 baru untuk 7 store peserta)".
  - Resolve the initiative assumption with the verified facts.
  - Change the epic check to: "status paket `Registered` di Play Console (halaman Android developer verification) atau via Developer ID Status API / Android Studio Panda 4+ saat build APK bertanda tangan".

### F-3 (Medium): Registration preconditions are not stated

- **What's wrong:** Registration of a **new** package needs only the SHA-256 fingerprint. That holds only while no other key has "known installs" for the name, and the rule is first-come, first-served below 50 installs. The spine does not pin down which account, which console, or what counts as "before the first APK".
- **Fix:** Add to AD-25:
  - (a) Check eligibility of `dev.lusalhi.lumynoid` with the Developer ID Status API before the first signed build.
  - (b) Register in the **Play Console** of the owner's existing account, under the Android developer verification page, Package names tab. The account must be a full-distribution account, **not** a Limited distribution (20-device) account.
  - (c) "Sebelum APK pertama" includes every `v0.<epic>.<patch>` and any CI artifact signed with the release key.
  - (d) `dev.lusalhi.lumynoid.debug` is installed only via ADB (exempt). If debug APKs are ever shared, register them as a separate package or never distribute them.

### F-4 (Low): "tidak pernah diganti" is stricter than the platform requires

- **What's wrong:** Google supports multiple keys per package, added later via the APK-upload proof with `adi-registration.properties`. Android supports signing-key rotation (APK Signature Scheme v3, API 28+; minSdk 29 is fine). Never rotating is a defensible v1 policy, but stated as an absolute it blocks a compromise response.
- **Fix:** "Tidak diganti di v1. Bila kunci bocor: rotasi lewat lineage APK Signature Scheme v3 (`apksigner rotate`), dan kunci baru didaftarkan di console sebelum APK pertama yang memakainya."

### F-5 (Low): AD-17 dropped reproducible builds, which affects the "one key" rule if F-Droid returns in v1.x

- **What's wrong:**
  - AD-17's Prevents still cites keeping the F-Droid path open in v1.x, but the rule no longer requires reproducible builds.
  - F-Droid can ship the developer's own signature (one registered key, cross-channel updates) **only** if the build reproduces against the upstream APK. Without that, F-Droid signs with its own key. That key becomes a second key for `dev.lusalhi.lumynoid`, and users could not move between channels.
- **Fix:** Keep "build reproducible dari tag git" as a SHOULD in AD-17, or add a Deferred row: "F-Droid v1.x mensyaratkan build reproducible + tanda tangan upstream agar tetap satu kunci (AD-25)". Also restore a one-line rationale for `dependenciesInfo` (no Play consumer; keeps the F-Droid path).

---

## 4. Other amended items checked

| Item | Result |
| --- | --- |
| Application id `dev.lusalhi.lumynoid`, module packages `dev.lusalhi.lumynoid.<modul>`, debug `.debug` | Valid package-name syntax. No ownership proof is needed for the reverse-domain prefix. Registration is first-come, first-served while installs are below 50, so register early (F-3). |
| Distribution = manual APK from GitHub Releases | Consistent with AD-24. There is no `REQUEST_INSTALL_PACKAGES`, so no in-app installer. Users grant "install unknown apps" to their browser or file manager; that is UX, not architecture. Once global enforcement starts, a registered package keeps installs unchanged ("Nothing changes from today's experience"). |
| Deployment diagram: `REL -->|unduh manual| APP`, F-Droid node removed | Consistent with C14. |
| Deferred: "PSE Komdigi" kept | Not in this lens's scope; not re-researched. |
| UI language English (Conventions) | No currency aspect. |
| Stack table (Kotlin 2.4.21, AGP 9.5.1, Gradle 9.6.0, KSP 2.3.14, etc.) | Matches the prior currency review's verified latest versions. AGP 9.5.1 needs Gradle ≥ 9.6.0 and JDK 17, which is consistent. |

## Sources

- Android developer verification overview: https://developer.android.com/developer-verification/guides
- FAQ: https://developer.android.com/developer-verification/guides/faq
- Android Developer Console guide: https://developer.android.com/developer-verification/guides/android-developer-console
- Play Console guide: https://developer.android.com/developer-verification/guides/google-play-console
- Play Console Help, register package names: https://support.google.com/googleplay/android-developer/answer/16761053
- Developer Console API: https://developer.android.com/developer-verification/guides/developer-console-api
- Rollout coverage: https://androidauthority.com/android-sideloading-developer-verification-first-wave-rollout-3717921 ; https://thehackernews.com/2026/06/google-sets-sept-30-deadline-for.html ; https://9to5google.com/2026/08/18/google-gradually-rolling-out-androids-advanced-sideloading-ahead-of-developer-verification/
- AGP 9.5 DSL: https://developer.android.com/reference/tools/gradle-api/9.5/com/android/build/api/dsl/ApplicationExtension ; https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/DependenciesInfo ; https://developer.android.com/build/releases/agp-9-5-0-release-notes
- Play Protect enhanced fraud protection in Indonesia: https://indonesiasentinel.com/google-launches-enhanced-fraud-protection-in-indonesia-to-combat-online-scams
- F-Droid reproducible builds (signature copying): https://f-droid.org/docs/Reproducible_Builds/
