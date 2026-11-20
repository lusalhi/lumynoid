---
title: 'Epic release tested on a real phone'
type: chore
ticket: '12'
created: '2026-11-20'
status: built
baseline_revision: 'c2379a97695553bc207d8d59968e2b2b183168e6'
route: oneshot
route_source: auto
risk: medium
review: quick
review_source: pinned
lenses_ran: [quick]
review_loop_iteration: 0
context:
  - '{project-root}/docs/release.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Stories 1.4–1.11 were never released; `v0.1.0` is only the tracer. The epic's Done-when checks must pass on a real phone with an APK from GitHub Releases.

**Approach:** Raise the version, merge to `main` by PR, tag, let the story-3 workflow publish the pre-release, then the owner installs it and runs each Done-when check.

**Decisions (human, 2026-11-20):** Version `v0.2.0`, `versionCode` 2. Story 1.10's debug-build phone check stays outside this story; 1.10 is marked done and the check is an owner debt. The hidden FGS notification stays accepted (epic Decision 2026-11-16).

</frozen-after-approval>

## Implementation Notes

Oneshot: the only code change is two lines in `app/build.gradle.kts`; everything else is owner-gated release steps from `docs/release.md` section 6. Claude pushes, opens the PR, merges and tags only with the owner's go-ahead at each step (pushes are held, `batch-pushes`).

Built 2026-11-20: version bump done and verified (self-test 19 cases, full Gradle command BUILD SUCCESSFUL, release APK holds only `lib/arm64-v8a`). The docs, live and phone tasks stay open and owner-gated; status `built` means the code part is ready, not that the story is done. Nothing pushed.

Phone result, 2026-11-20: the owner reported a crash with the `v0.2.0` pre-release. Cubic's PR #6 review had flagged the manifest registering `androidx.core.content.FileProvider` directly; AndroidX says that "is not reliable and will causes crashes on some devices", so story 1.10's rejection of that finding was wrong. Fix: `LumynoidFileProvider` subclass registered in the manifest, `CrashReportTest` asserts it, and the version goes to `v0.2.1` / `versionCode` 3. The crash cause on the owner's phone is not yet confirmed to be the provider; a crash report or logcat would settle it.

## Review Triage Log

Pass 1, quick lens (subagent, same model level). 4 findings: high 0, medium 0, low 0, false 4, maybe-false 0. No loopback.

| # | Finding | Verdict | Route | Evidence / action |
|---|---------|---------|-------|-------------------|
| 1 | Diff completes only task 1 of 4 (no tag, release, phone check, docs commit) | false | reject | The other tasks are owner-gated and sequenced after merge; the plan says so. Not a defect in the change. |
| 2 | Version bump correct | false | reject | Confirmation, no problem reported. |
| 3 | Staged plan hunk lacks the review frontmatter | false | reject | The diff came from `git add -N` before the frontmatter edit; the commit stages the file as it is on disk. |
| 4 | No AGENTS.md or CLAUDE.md exists | false | reject | Confirmed absent; no rules to break. |

## Tasks

- [x] `app/build.gradle.kts` -- `versionCode = 2`, `versionName = "v0.2.0"`
- [ ] Docs commit -- mark 1.10, 1.11 and this story done in the tree and plans after the live proof
- [ ] Live (owner go-ahead each step): push branch, PR to `main`, merge, `git tag v0.2.0 origin/main`, push tag, owner approves environment `release`, verify the pre-release holds `lumynoid-v0.2.0.apk` + `SHA256SUMS` and the checksum matches
- [ ] Owner on phone, APK from GitHub Releases, per epic Done-when: add provider (OpenAI-compatible preset, custom URL, Anthropic) with connection test showing result and reason; streaming markdown + code block chat; conversation in the list and continues after the app is closed; install to first answer without terminal or leaving the app; answer finishes and is saved with the app in background

## Verification

**Commands:**
- `python3 .github/scripts/check_release.py --self-test` -- expected: exit 0
- `./gradlew :app:checkDependencyPolicy :core:test :llm:test :data:testDebugUnitTest :app:assembleDebug :app:assembleRelease` -- expected: BUILD SUCCESSFUL
- `python3 .github/scripts/check_release.py tag --tag v0.2.0 --main origin/main` (after local tag, after merge) -- expected: exit 0

**Manual checks:**
- Owner runs every Done-when check on the phone and reports pass/fail per check.
