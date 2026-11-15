---
title: 'Public licensed repo with CI gates'
type: chore
ticket: '2'
created: '2026-11-15'
status: built
baseline_revision: 'bca406260d72dcdd1bbe770cdf45e4168ecefeca'
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

**Problem:** The repo is private with no licence or README, and CI only builds and runs tests. Nothing yet stops a PR from adding Google/Firebase or non-OSI dependencies (AD-17), two parallel PRs from claiming the same Room version (AD-19), or a persisted subtype from lacking an explicit `@SerialName` (AD-19 serialisation rule). Story 3 publishes releases from this repo, so it must be public and gated first.

**Approach:** Add `LICENSE` (Apache-2.0) and `README.md`, then three CI gates that fail the build: a dependency-policy check on the resolved release runtime classpath, a Room schema-version claim check on PRs, and a `@SerialName` check for persisted types. Finally prove each gate fails on a throw-away PR; the owner then makes the repo public.

## Boundaries & Constraints

**Decisions (human, 2026-11-15):** Licence is Apache-2.0, copyright line `Copyright 2026 lusalhi`. The owner flips the repo to public by hand after reviewing; the implementer never changes visibility. Keep the full plan, no split.

**Always:** Gates run on `push` and `pull_request` with `contents: read` only and no secrets. Each check has a self-test that proves it fails on a bad input. Check logic lives in versioned scripts or Gradle tasks that run locally with one command, not inline YAML. Forbidden groups are exactly `com.google.android.gms`, `com.google.firebase`, `com.google.mlkit`, `com.google.android.play`. Exceptions to the licence check go in a committed allowlist file with a reason per entry. `applicationId`, versions and signing are untouched.

**Never:** Do not make the repo public. No release workflow, tags, signing keys, secrets or GitHub Environments (story 3). No AD-24 manifest-permission check (not in this ticket's scope). No history rewriting. Test PRs are never merged and are closed with their branches deleted. No change to production source other than what the gates need.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Clean tree | Current `main` state | All three gates pass | No error expected |
| Forbidden group | PR adds a `com.google.firebase` dependency (also when transitive) | Dependency gate fails naming the coordinate | CI error annotation |
| Non-OSI or missing licence | A resolved artifact has no OSI licence and no allowlist entry | Dependency gate fails naming it | CI error annotation |
| Version already claimed | PR adds `N.json` that exists on base, or another open PR adds the same file | Schema gate fails naming the version and the other claimant | CI error annotation |
| Edited old schema | PR modifies an existing exported schema file | Schema gate fails | CI error annotation |
| Unnamed subtype | A persisted `@Serializable` subtype lacks `@SerialName`, or its name is not snake_case | Serialisation gate fails naming the class | Test failure |

</frozen-after-approval>

## Code Map

- `.github/workflows/ci.yml` -- existing job (AD-1 check, tests, debug APK); add the three gates, a `pull_request`-only schema step with `fetch-depth: 0`, and `pull-requests: read` for it.
- `build.gradle.kts`, `gradle/libs.versions.toml` -- root build; home for a dependency-policy task that resolves `:app` `releaseRuntimeClasspath` (maven POM licences via artifact resolution query, parent POMs followed).
- `app/build.gradle.kts` -- applies Android; `releaseRuntimeClasspath` exists even with no `release` signing; do not change.
- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskEvent.kt` -- persisted roots `Origin`, `TaskEvent`, enum `FailureReason`; all already have snake_case `@SerialName`. `core/src/test/.../TaskRunnerTest.kt` -- pattern for JVM tests (`kotlin("test")`, JUnit).
- `data/schemas/dev.lusalhi.lumynoid.data.LumynoidDatabase/1.json` -- the only exported schema; `data/.../LumynoidDatabase.kt` declares `version = 1`.
- `.gitignore` -- keep `local.properties` ignored; `app/debug.keystore` is committed on purpose (shared debug key, AD-25).
- No `README*`, `LICENSE*`, `main` branch or `.github/scripts` exist. Remote has only `initiative-lumynoid`; the repo is private.

## Tasks & Acceptance

**Execution:**
- [x] `LICENSE`, `README.md` -- licence text per decision; README states what Lumynoid is, that the APK comes only from GitHub Releases, licence, how to build and run the gates; release-key fingerprint placeholder deferred to story 3 -- C14, AD-25
- [x] `build.gradle.kts`, `config/license-allowlist.txt` -- task `checkDependencyPolicy`: resolve `:app:releaseRuntimeClasspath`, fail on forbidden groups and on non-OSI or unknown licences unless allowlisted -- AD-17
- [x] `.github/scripts/check_schema_claims.py` + self-test -- compare schema files on the PR against base and against other open PRs (`gh api`) -- AD-19
- [x] `core/src/test/.../SerialNamePolicyTest.kt` -- reflect over each persisted root's sealed subclasses and enum entries; require explicit snake_case serial names; include a negative fixture proving a missing name is detected -- AD-19
- [x] `.github/workflows/ci.yml` -- wire the three gates into the job
- [ ] Verify live: on a private repo, push a base branch `main`, then open throw-away PRs (Firebase dependency; reused schema number); both must fail, then close them and delete their branches
- [ ] Hand over: report that the repo is ready to flip public, with the history's author email noted for the owner

**Acceptance Criteria:**
- Given a clean checkout, when `./gradlew checkDependencyPolicy :core:test` runs, then it succeeds.
- Given a test PR adding `com.google.firebase:firebase-common`, when CI runs, then the dependency gate fails and names it.
- Given a test PR adding schema `1.json` content changes or a number already on base, when CI runs, then the schema gate fails.
- Given the private repo after push, when GitHub detects the licence, then `licenseInfo` reports Apache-2.0; the owner's later flip to public is the ticket's final step.

## Implementation Notes

Implemented directly in the main session (no subagent was requested). Local verification passed: `checkDependencyPolicy`, `:core:test`, `:llm:test`, `:data:testDebugUnitTest`, `:app:assembleDebug`, both Python self-tests, and the `:core` purity grep.

- Deviation from the plan: the dependency rules live in `.github/scripts/check_dependency_policy.py` (with `--self-test`), fed by a Gradle report task in `gradle/dependency-policy.gradle.kts` (applied from `:app`); `checkDependencyPolicy` is an `Exec` over that script. This makes the rules testable without a Gradle fixture. The root `build.gradle.kts` is unchanged.
- Negative check done locally: adding `com.google.firebase:firebase-common:21.0.0` to `:app` made `checkDependencyPolicy` fail on 5 `gms`/`firebase` modules (restored afterwards). It also exposed a bug: the report task had no inputs, so Gradle skipped it as up to date after a dependency change; both tasks now always run.
- Mutation check done locally: removing one `@SerialName` from `TaskEvent` made `SerialNamePolicyTest` fail (restored afterwards).
- Review patches: the dependency gate now also fails on unresolved release dependencies and on local `files(...)` jars/AARs (see Review Triage Log).
- Classpath today: 144 modules, all declaring Apache-2.0 or MIT; the allowlist starts empty.
- Step-03 forbids push and remote operations, so the **live** task (create `main`, throw-away PRs for Firebase and a reused schema number, close them) is NOT done. The schema gate's `gh api` open-PR scan and the `schema-claims` job were exercised only by the local self-test, not on GitHub. The task stays unchecked until after review.
- The final plan task is a hand-over: the owner flips the repo public; the history's author email (`hammam47@gmail.com`) becomes public with it.

## Plan Change Log

## Review Triage Log

Pass 1, quick lens, run externally with `omp` on its default model (findings in `story-repo-publik-berlisensi-dengan-gerbang-ci-review.md` prompt; output not stored beside the plan). 6 findings: high 0, medium 1, low 4, false 0, maybe-false 0; 1 more is a known pending task. No intent_gap or bad_plan; no loopback. Patches applied by the main session; full verification re-run after, all green.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 3 | `files(...)` jars/AARs bypass the licence gate | medium | patch | Real: only `ModuleComponentIdentifier` was reported, so `releaseImplementation(files(...))` added a prebuilt binary with no check, against AD-17. The report now adds every non-module, non-project classpath file as `(local file):<name>` with no licence, so it fails unless allowlisted. Proven locally with a throw-away jar (fails), clean tree passes. |
| 2 | Unresolved release dependencies dropped silently | low | patch | Real: `filterIsInstance<ResolvedDependencyResult>()` hid unresolved ones. The traversal now throws on `UnresolvedDependencyResult`. |
| 1 | `Apache-2.0 WITH Commons-Clause` matches `apache` | low | reject | Real in theory, but no such POM is on the classpath and the fix needs extra guard branches; allowlist review at PR time covers new dependencies. |
| 4 | Enum entry check accepts an unannotated lowercase entry | low | reject | Real, but Kotlin enum entries are uppercase by convention, so an unannotated entry fails the snake_case check; detecting annotations needs reflection on the enum class. Not worth the complexity. |
| 5 | Prefix match forbids subgroups of the four groups | low | reject | Stricter than the plan's "exactly" wording, but it only rejects `com.google.android.gms.*` style groups that do not exist on Maven, and loosening it would weaken AD-17. |
| 6 | Live GitHub acceptance evidence missing | low | defer | Not a code defect: step 3 forbids push, so the throw-away PRs and the `licenseInfo` check are done after review. Recorded in `deferred-work.md`; the plan task stays unchecked. |

## Design Notes

- The serialisation gate uses serial descriptors, not text matching: an unnamed subtype's default `serialName` is its fully qualified class name, so "contains a dot" detects the missing annotation exactly. Residual risk: a new persisted root must be added to the test's root list; the test file says so.
- The schema gate cannot see PRs opened after its own run; the later PR's run catches the collision, which is acceptable because both cannot merge green.

## Verification

**Commands:**
- `./gradlew checkDependencyPolicy :core:test :llm:test :data:testDebugUnitTest` -- expected: BUILD SUCCESSFUL
- `python3 .github/scripts/check_schema_claims.py --self-test` -- expected: exit 0
- `gh repo view lusalhi/lumynoid --json licenseInfo` -- expected: Apache-2.0 (visibility stays PRIVATE until the owner flips it)

**Manual checks (if no CLI):**
- The two throw-away PRs show a failing CI check with the policy message.
