---
title: 'Signed release APK published to GitHub Releases'
type: feature
ticket: '3'
created: '2026-11-16'
status: built
baseline_revision: '42440eb731835cad2d571a1433f7a9b1d3844de3'
route: full
route_source: auto
risk: high
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** No release key, release workflow or release exists, so no one can install a signed Lumynoid APK; the package name and key cannot change after the first signed APK ships, and Android developer verification (AD-25) requires both to be registered in Play Console first.

**Approach:** Add one tag-triggered release workflow that verifies the tag (on `main`, `versionCode` rises, Room schemas on `main`'s migration line), builds an arm64-v8a release APK signed with a key held only in the GitHub Environment `release`, checks the signing fingerprint against the one committed in the repo, and publishes `lumynoid-<versionName>.apk` plus `SHA256SUMS` as a pre-release. A runbook covers the owner's offline steps: key creation, two encrypted backups, Play Console registration, environment secrets, cutting a tag, and the restore drill.

## Boundaries & Constraints

**Decisions (human, 2026-11-16):** Before `v0.1.0`, all of `initiative-lumynoid` (stories 1.4–1.8 and this one) merges to `main` by PR. Keep the full plan, no split.

**Always:** Release only from a `v*` tag whose commit is an ancestor of `origin/main`; tag name equals `versionName` in source. Signing secrets live only in Environment `release` (tag rule `v*`, owner as required reviewer); `ci.yml`, `pull_request` and `pull_request_target` never reference them. The job holding secrets runs only after the verify job passes and has `contents: read`; only the publish job, without secrets, gets `contents: write`. Tag checks live in a versioned script with `--self-test`, runnable locally, and report every failure, not just the first. Local `assembleRelease` without the key still builds (unsigned). The release APK carries only `lib/arm64-v8a`; debug keeps all ABIs. `v0.*` tags publish as pre-release. Keep `allowBackup=false`, `dataExtractionRules`, `dependenciesInfo` off, minSdk 29, targetSdk 37.

**Never:** No key material, password or keystore in the repo, logs or this session; the implementer never creates or handles the release key. No first tag before the owner confirms Play Console shows the package and fingerprint as Registered. No R8/minify change. No auto-update code, no Play/F-Droid publishing, no `workflow_dispatch` signing (AD-25 allows it later). No change to app behaviour.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First release | Tag `v0.1.0` on `main`, `versionCode` 1, no earlier `v*` tag | Pre-release with `lumynoid-v0.1.0.apk` and `SHA256SUMS` | No error expected |
| Code not raised | Earlier tag has `versionCode` ≥ the new one | Verify job fails naming both tags and codes; no signing job runs | `::error::` annotation |
| Tag off `main` | Tag commit not an ancestor of `origin/main` | Verify job fails | `::error::` |
| Name mismatch | Tag `v0.2.0`, source `versionName` `v0.1.0` | Verify job fails | `::error::` |
| Schema off main line | A schema file at the tag differs from `main`'s, versions are not 1..N contiguous, or DB `version` ≠ highest schema | Verify job fails naming the file | `::error::` |
| Wrong key | Signed APK's cert SHA-256 ≠ committed fingerprint, or fingerprint file still a placeholder | Signing job fails before publish | `::error::` |

</frozen-after-approval>

## Code Map

- `app/build.gradle.kts` -- holds `applicationId`, `versionCode = 1`, `versionName = "v0.1.0"`, shared debug `signingConfigs`, `dependenciesInfo { includeInApk = false }`. Add a `release` signing config read from env vars (keystore path, store password, alias, key password), applied only when all are set; add `ndk { abiFilters += "arm64-v8a" }` to the release build type. Today the APK ships `libandroidx.graphics.path.so` for four ABIs.
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml` -- already `allowBackup=false` + rules excluding every domain; leave as is, assert in release checks.
- `data/src/main/kotlin/.../LumynoidDatabase.kt` (`version = 4`), `data/schemas/dev.lusalhi.lumynoid.data.LumynoidDatabase/{1..4}.json` -- input for the schema-line check.
- `.github/scripts/check_schema_claims.py` -- pattern to reuse: `git()` helper, `ls-tree` blob compare, temp-repo `--self-test`, `::error::` output.
- `.github/workflows/ci.yml` -- setup steps to mirror (`checkout@v7`, `setup-java@v6` temurin 17, `setup-gradle@v5`); its gates should also run in the release verify job.
- `README.md` -- has the "fingerprint will be published here" sentence to replace and the Build section.
- State: no tags, no releases, no environments. `origin/main` (`8cfa475`) is 7 commits behind `initiative-lumynoid`. Local `main` is stale; use `origin/main`. SDK at `/opt/android-sdk` (build-tools 37.0.0 has `apksigner`, `aapt2`).

## Tasks & Acceptance

**Execution:**
- [x] `app/build.gradle.kts` -- env-driven release signing and arm64-v8a filter -- AD-25, ABI
- [x] `config/release-cert-sha256.txt` -- placeholder line the workflow rejects until the owner's fingerprint replaces it -- AD-25
- [x] `.github/scripts/check_release.py` -- `tag` mode (ancestor of main, name = `versionName`, `versionCode` > every other `v*` tag's code read via `git show <tag>:app/build.gradle.kts`, schema line), `apk` mode (parses `apksigner verify --print-certs` and `aapt2 dump badging` output: fingerprint, package, versionCode/Name, ABI set), `--self-test` covering each matrix row -- AD-25, AD-19
- [x] `.github/workflows/release.yml` -- jobs `verify` (no secrets: tag check, gates and tests from `ci.yml`), `build` (environment `release`: decode keystore to `$RUNNER_TEMP`, `assembleRelease`, apk check, `SHA256SUMS`, upload artifact, attach `mapping.txt` if it exists), `publish` (`gh release create --prerelease` for `v0.*`, `--verify-tag`)
- [x] `docs/release.md` -- owner runbook: `keytool` PKCS12 command, two encrypted offline backups, printing the fingerprint, Play Console registration, environment + secrets (`gh` commands), cutting a release, restore drill
- [x] `README.md` -- link the runbook; fingerprint slot
- [ ] Live, after review and owner go-ahead: create Environment `release`; owner adds secrets, registers in Play Console, sends the public fingerprint; commit it; merge to `main`; tag `v0.1.0`; prove a non-rising tag is rejected, then delete that tag

**Acceptance Criteria:**
- Given a clean checkout without the key, when `./gradlew :app:assembleRelease` runs, then it builds an unsigned APK whose only native folder is `lib/arm64-v8a`.
- Given `python3 .github/scripts/check_release.py --self-test`, when it runs, then every matrix rule rejects its bad input and the happy path passes.
- Given the owner has confirmed Registered status, when `v0.1.0` is pushed and the run approved, then GitHub Releases shows a pre-release with exactly `lumynoid-v0.1.0.apk` and `SHA256SUMS`, and the checksum matches.
- Given `v0.1.0` exists, when a second tag on the same commit is pushed, then the verify job fails naming the non-rising `versionCode`.

## Implementation Notes

Implemented by a Sonnet subagent from this plan; the main session checked the diff and re-ran verification. All checks pass: `check_release.py --self-test` (19 cases), the full Gradle command (dependency policy 181 modules), the unsigned release APK holds only `lib/arm64-v8a/`, and `ci.yml` has no `secrets.` reference.

- Signing env vars: `LUMYNOID_KEYSTORE_PATH`, `LUMYNOID_KEYSTORE_PASSWORD`, `LUMYNOID_KEY_ALIAS`, `LUMYNOID_KEY_PASSWORD`; the `release` signing config exists only when all four are set. Environment secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
- A throw-away key (scratchpad, deleted) proved Gradle signs when the env vars are set, and showed `apksigner --print-certs` prints `V2 Signer: certificate SHA-256 digest:` lines; the parser matches that and treats the same key under v2 and v3 as one signer.
- `verify` also greps the manifest for `allowBackup="false"` and `dataExtractionRules`; `build` rejects a debuggable APK and deletes the decoded keystore on exit.
- Real-repo check: with a temporary local tag `v0.1.0` at HEAD (deleted afterwards), `check_release.py tag --main HEAD` passed on the real files, and `--main origin/main` failed as expected (not an ancestor; schemas 2–4 missing on `origin/main`) until the branch is merged.
- Not exercised live: the workflow run, environment gating, `gh release create`, and the real-key path. The Live task stays open for the owner steps.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run externally with `omp` on `gpt-6.1-sol` (thinking medium, read-only tools). 3 findings: high 0, medium 0, low 2, false 1, maybe-false 0. No intent_gap or bad_plan; no loopback. One patch applied by the implementer; full verification re-run after, all green.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Tag check not bound to the event commit: the forced tag fetch can move the tag, so `verify` checks one commit while `build` signs `github.sha` | low | patch | Real: a re-cut tag while an earlier run waits in the `release` concurrency queue reaches it. `verify` now fails unless `git rev-parse "$TAG^{commit}"` equals `$GITHUB_SHA`. |
| 2 | Backup gate checks only manifest text, not the exclusions inside `data_extraction_rules.xml` | low | reject | Real, but removing the exclusions needs a reviewed commit to that file, and asserting them needs XML parsing in the workflow; unlikely in everyday use. |
| 3 | `verify` omits `:app:assembleDebug`, so a debug-only compile failure passes | false | reject | `:app` has only `src/main` and `src/test`; no debug-only source set exists, and `build` compiles the same main sources in `assembleRelease` before anything is published. |

## Design Notes

- R8 stays off: turning it on needs keep rules for serialization, Ktor and Room and would change app behaviour. The workflow already attaches `mapping.txt` when a later story enables it.
- The fingerprint check in the signing job is the guard against a wrong or swapped key: a tag can only publish an APK signed by the registered key.
- Previous `versionCode` comes from tags, not Releases, so a deleted release still counts.

## Verification

**Commands:**
- `python3 .github/scripts/check_release.py --self-test` -- expected: exit 0
- `./gradlew :app:checkDependencyPolicy :core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:assembleRelease` -- expected: BUILD SUCCESSFUL
- `unzip -l app/build/outputs/apk/release/*.apk | grep lib/` -- expected: only `lib/arm64-v8a/`
- `grep -rn 'secrets\.' .github/workflows/ci.yml` -- expected: no match

**Manual checks (if no CLI):**
- Owner installs the pre-release APK on a real phone; owner restores the key from a backup and its fingerprint matches README.
