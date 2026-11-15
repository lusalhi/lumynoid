You are the Quick reviewer. This session shares no filesystem with the build session, so everything you need is inline below.

Read the plan below, which holds the acceptance criteria and intent. It has no `context:` files beyond the architecture document, which you do not have; AD-17, AD-19 and AD-25 are summarised in the plan. The repository root has no AGENTS.md or CLAUDE.md. Then read the unified diff below; it is the change under review. (The `LICENSE` file, an unmodified copy of the Apache-2.0 text, is left out of the diff.)

Report unmet acceptance criteria, broken rules, and bugs. Each finding: the location, what goes wrong, and evidence checked against the surrounding code. Output a Markdown list of findings only — no severity, priority, or ranking.

Review by inspection only. Do not modify code, run builds or tests, or invoke quality gates. Do not invoke any skill, and do not spawn subagents of your own — you are the reviewer. Return your findings as text in your final message; do not route them through any findings-reporting tool the host may offer.

=== PLAN (story-repo-publik-berlisensi-dengan-gerbang-ci-plan.md) ===
---
title: 'Public licensed repo with CI gates'
type: chore
ticket: '2'
created: '2026-11-15'
status: in-review
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
- Classpath today: 144 modules, all declaring Apache-2.0 or MIT; the allowlist starts empty.
- Step-03 forbids push and remote operations, so the **live** task (create `main`, throw-away PRs for Firebase and a reused schema number, close them) is NOT done. The schema gate's `gh api` open-PR scan and the `schema-claims` job were exercised only by the local self-test, not on GitHub. The task stays unchecked until after review.
- The final plan task is a hand-over: the owner flips the repo public; the history's author email (`hammam47@gmail.com`) becomes public with it.

## Plan Change Log

## Review Triage Log

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

=== END PLAN ===

=== DIFF (changes since baseline_revision bca406260d72dcdd1bbe770cdf45e4168ecefeca) ===
diff --git a/.github/scripts/check_dependency_policy.py b/.github/scripts/check_dependency_policy.py
new file mode 100644
index 0000000..446bd31
--- /dev/null
+++ b/.github/scripts/check_dependency_policy.py
@@ -0,0 +1,153 @@
+#!/usr/bin/env python3
+"""AD-17 dependency gate.
+
+Reads the report written by `./gradlew :app:releaseClasspathReport` (every module on the resolved release
+runtime classpath, transitive ones included, with the licences its POM declares) and fails when:
+
+* a module belongs to a forbidden Google group (Play Services, Firebase, ML Kit, Play), or
+* a module declares no OSI-approved licence and is not listed in config/license-allowlist.txt.
+
+Run `check_dependency_policy.py --self-test` to prove both rules reject bad input.
+"""
+import argparse
+import json
+import re
+import sys
+
+FORBIDDEN_GROUPS = (
+    "com.google.android.gms",
+    "com.google.firebase",
+    "com.google.mlkit",
+    "com.google.android.play",
+)
+
+# Each pattern is matched against a licence's name and its URL. Only OSI-approved licences belong here.
+OSI_PATTERNS = [
+    r"apache",  # Apache-2.0, in all the spellings POMs use
+    r"\bmit\b|opensource\.org/licenses/mit",
+    r"\bbsd\b|opensource\.org/licenses/bsd",
+    r"\bisc\b",
+    r"eclipse public license|\bepl\b|eclipse\.org/legal/epl",
+    r"mozilla public license|\bmpl\b",
+    r"gnu (lesser )?general public license|\bl?gpl\b",
+    r"common development and distribution license|\bcddl\b",
+    r"\bzlib\b",
+    r"unicode",
+]
+
+
+def forbidden_group(group):
+    return any(group == g or group.startswith(g + ".") for g in FORBIDDEN_GROUPS)
+
+
+def is_osi(licence):
+    haystack = f"{licence.get('name', '')} {licence.get('url', '')}".lower()
+    return any(re.search(p, haystack) for p in OSI_PATTERNS)
+
+
+def load_allowlist(path):
+    entries = {}
+    errors = []
+    with open(path, encoding="utf-8") as f:
+        for number, raw in enumerate(f, 1):
+            line = raw.strip()
+            if not line or line.startswith("#"):
+                continue
+            coordinate, sep, reason = (part.strip() for part in line.partition("|"))
+            if not sep or not reason or coordinate.count(":") != 1:
+                errors.append(f"{path}:{number}: expected '<group>:<artifact> | <reason>'")
+                continue
+            entries[coordinate] = reason
+    return entries, errors
+
+
+def check(modules, allowlist):
+    """Returns (errors, unused allowlist entries)."""
+    errors = []
+    used = set()
+    for module in modules:
+        group, name, version = module["group"], module["name"], module["version"]
+        coordinate = f"{group}:{name}"
+        label = f"{coordinate}:{version}"
+        if forbidden_group(group):
+            errors.append(f"{label}: group '{group}' is forbidden (AD-17: no Google services)")
+            continue
+        licences = module.get("licenses", [])
+        if any(is_osi(licence) for licence in licences):
+            continue
+        if coordinate in allowlist:
+            used.add(coordinate)
+            continue
+        declared = ", ".join(l.get("name") or l.get("url") or "?" for l in licences) or "none declared"
+        errors.append(f"{label}: no OSI licence ({declared}); fix the dependency or add it to config/license-allowlist.txt")
+    return errors, sorted(set(allowlist) - used)
+
+
+def self_test():
+    ok = {"group": "org.jetbrains.kotlin", "name": "kotlin-stdlib", "version": "1",
+          "licenses": [{"name": "The Apache Software License, Version 2.0", "url": ""}]}
+
+    def failures(module, allowlist=None):
+        return check([ok, module], allowlist or {})[0]
+
+    cases = [
+        ("clean classpath passes", [], check([ok], {})[0]),
+        ("firebase rejected", ["com.google.firebase:firebase-common"],
+         failures({"group": "com.google.firebase", "name": "firebase-common", "version": "1", "licenses": ok["licenses"]})),
+        ("subgroup of gms rejected", ["com.google.android.gms.ads:x"],
+         failures({"group": "com.google.android.gms.ads", "name": "x", "version": "1", "licenses": ok["licenses"]})),
+        ("mlkit rejected", ["com.google.mlkit:x"],
+         failures({"group": "com.google.mlkit", "name": "x", "version": "1", "licenses": ok["licenses"]})),
+        ("play rejected", ["com.google.android.play:core"],
+         failures({"group": "com.google.android.play", "name": "core", "version": "1", "licenses": ok["licenses"]})),
+        ("similar group allowed", [],
+         failures({"group": "com.google.android.material", "name": "material", "version": "1", "licenses": ok["licenses"]})),
+        ("no licence rejected", ["acme:thing"], failures({"group": "acme", "name": "thing", "version": "1", "licenses": []})),
+        ("proprietary rejected", ["acme:thing"],
+         failures({"group": "acme", "name": "thing", "version": "1",
+                   "licenses": [{"name": "Proprietary EULA", "url": "https://acme.example/eula"}]})),
+        ("allowlisted accepted", [],
+         failures({"group": "acme", "name": "thing", "version": "1", "licenses": []}, {"acme:thing": "MIT per its repo"})),
+    ]
+    bad = 0
+    for title, expected_coordinates, errors in cases:
+        got = sorted(e.split(":")[0] + ":" + e.split(":")[1] for e in errors)
+        if got != sorted(expected_coordinates):
+            bad += 1
+            print(f"SELF-TEST FAIL: {title}: expected {expected_coordinates}, got {errors}")
+    if bad:
+        return 1
+    print(f"self-test passed ({len(cases)} cases)")
+    return 0
+
+
+def main():
+    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
+    parser.add_argument("--report")
+    parser.add_argument("--allowlist")
+    parser.add_argument("--self-test", action="store_true")
+    args = parser.parse_args()
+    if args.self_test:
+        return self_test()
+    if not args.report or not args.allowlist:
+        parser.error("--report and --allowlist are required")
+
+    with open(args.report, encoding="utf-8") as f:
+        modules = json.load(f)["modules"]
+    if not modules:
+        print("::error::the dependency report is empty; the check would pass vacuously")
+        return 1
+    allowlist, allowlist_errors = load_allowlist(args.allowlist)
+    errors, unused = check(modules, allowlist)
+    for entry in unused:
+        print(f"::warning::allowlist entry '{entry}' is not needed any more; remove it")
+    for message in allowlist_errors + errors:
+        print(f"::error::{message}")
+    if errors or allowlist_errors:
+        return 1
+    print(f"dependency policy OK: {len(modules)} modules checked")
+    return 0
+
+
+if __name__ == "__main__":
+    sys.exit(main())
diff --git a/.github/scripts/check_schema_claims.py b/.github/scripts/check_schema_claims.py
new file mode 100644
index 0000000..4f01494
--- /dev/null
+++ b/.github/scripts/check_schema_claims.py
@@ -0,0 +1,170 @@
+#!/usr/bin/env python3
+"""AD-19 gate: one Room schema version, claimed once.
+
+A pull request "claims" a database version by adding the exported schema file `<db>/<N>.json` under
+the schemas directory. The gate fails when the pull request:
+
+* edits or deletes a schema file that already existed at the point it branched (history is immutable),
+* adds a schema file the base branch tip has since gained (the version was claimed on base first), or
+* adds a schema file that another open pull request also adds (two PRs race for the same version).
+
+Usage in CI:  check_schema_claims.py --base origin/main --repo OWNER/NAME --pr 42
+Run `check_schema_claims.py --self-test` to see each rule reject a throw-away repository.
+"""
+import argparse
+import json
+import os
+import subprocess
+import sys
+import tempfile
+
+SCHEMAS_DIR = "data/schemas"
+
+
+def git(*args, cwd=None, check=True):
+    result = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
+    if check and result.returncode != 0:
+        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
+    return result.stdout
+
+
+def schema_blobs(rev, schemas_dir, cwd):
+    """Maps each schema path at `rev` to its blob id."""
+    out = git("ls-tree", "-r", rev, "--", schemas_dir, cwd=cwd)
+    blobs = {}
+    for line in out.splitlines():
+        meta, path = line.split("\t", 1)
+        blobs[path] = meta.split()[2]
+    return blobs
+
+
+def check(base, head, schemas_dir, other_prs, cwd=None):
+    """other_prs maps a PR label to the schema paths it adds. Returns a list of error strings."""
+    fork_point = git("merge-base", base, head, cwd=cwd).strip()
+    at_fork, at_base, at_head = (schema_blobs(rev, schemas_dir, cwd) for rev in (fork_point, base, head))
+    errors = []
+    for path, blob in sorted(at_fork.items()):
+        if path not in at_head:
+            errors.append(f"{path}: an exported schema was deleted; schema history is immutable")
+        elif at_head[path] != blob:
+            errors.append(f"{path}: an existing exported schema was edited; add a new version instead")
+    added = sorted(set(at_head) - set(at_fork))
+    for path in added:
+        if path in at_base:
+            errors.append(f"{path}: this database version is already claimed on the base branch; renumber your migration")
+        for label, paths in sorted(other_prs.items()):
+            if path in paths:
+                errors.append(f"{path}: this database version is also claimed by {label}; only one of you may take it")
+    return errors
+
+
+def open_pr_claims(repo, own_pr, schemas_dir):
+    """Schema paths added by every other open PR, read through the GitHub API (needs `gh` and a token)."""
+    def api(endpoint):
+        out = subprocess.run(["gh", "api", "--paginate", endpoint], capture_output=True, text=True, check=True).stdout
+        # --paginate concatenates JSON arrays as `[...][...]`; decode them one by one.
+        decoder, items, index = json.JSONDecoder(), [], 0
+        while index < len(out):
+            if out[index].isspace():
+                index += 1
+                continue
+            page, index = decoder.raw_decode(out, index)
+            items.extend(page)
+        return items
+
+    claims = {}
+    for pr in api(f"repos/{repo}/pulls?state=open&per_page=100"):
+        if pr["number"] == own_pr:
+            continue
+        files = api(f"repos/{repo}/pulls/{pr['number']}/files?per_page=100")
+        added = [f["filename"] for f in files if f["status"] == "added" and f["filename"].startswith(schemas_dir + "/")]
+        if added:
+            claims[f"PR #{pr['number']}"] = added
+    return claims
+
+
+def self_test():
+    schema = f"{SCHEMAS_DIR}/app.Db"
+    with tempfile.TemporaryDirectory() as repo:
+        def run(*args):
+            return git(*args, cwd=repo)
+
+        def write(name, text):
+            path = os.path.join(repo, schema, name)
+            os.makedirs(os.path.dirname(path), exist_ok=True)
+            with open(path, "w") as f:
+                f.write(text)
+
+        def commit(message):
+            run("add", "-A")
+            run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message)
+
+        run("init", "-q", "-b", "main")
+        write("1.json", "v1")
+        commit("v1")
+
+        def branch(name, edit):
+            run("checkout", "-q", "-b", name, "main")
+            edit()
+            commit(name)
+            run("checkout", "-q", "main")
+
+        branch("clean", lambda: write("2.json", "v2 clean"))
+        branch("edits-old", lambda: write("1.json", "v1 edited"))
+        branch("deletes-old", lambda: os.remove(os.path.join(repo, schema, "1.json")))
+        branch("races-main", lambda: write("2.json", "v2 from branch"))
+        branch("no-schema", lambda: write("../../../notes.txt", "x"))
+        run("checkout", "-q", "main")
+        write("2.json", "v2 on main")  # base gains version 2 after "races-main" branched
+        commit("main gains v2")
+
+        wanted = f"{schema}/2.json"
+        run("branch", "base-before-v2", "main~1")  # base as it was when the branches were cut
+        # (title, base, head, other open PRs, must fail)
+        cases = [
+            ("branch claiming a free version", "base-before-v2", "clean", {}, False),
+            ("branch with no schema change", "main", "no-schema", {}, False),
+            ("edited existing schema", "base-before-v2", "edits-old", {}, True),
+            ("deleted existing schema", "base-before-v2", "deletes-old", {}, True),
+            ("version claimed on base after branching", "main", "races-main", {}, True),
+            ("version also claimed by an open PR", "base-before-v2", "clean", {"PR #7": [wanted]}, True),
+            ("other PR claims a different version", "base-before-v2", "clean", {"PR #7": [f"{schema}/9.json"]}, False),
+        ]
+        failed = 0
+        for title, base, head, others, should_fail in cases:
+            errors = check(base, head, SCHEMAS_DIR, others, cwd=repo)
+            if bool(errors) != should_fail:
+                failed += 1
+                print(f"SELF-TEST FAIL: {title}: expected {'failure' if should_fail else 'pass'}, got {errors}")
+    if failed:
+        return 1
+    print(f"self-test passed ({len(cases)} cases)")
+    return 0
+
+
+def main():
+    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
+    parser.add_argument("--base", help="the base branch tip, e.g. origin/main")
+    parser.add_argument("--head", default="HEAD")
+    parser.add_argument("--schemas-dir", default=SCHEMAS_DIR)
+    parser.add_argument("--repo", help="OWNER/NAME; with --pr, other open PRs are read through `gh`")
+    parser.add_argument("--pr", type=int, default=0, help="this PR's number, excluded from the other-PR scan")
+    parser.add_argument("--self-test", action="store_true")
+    args = parser.parse_args()
+    if args.self_test:
+        return self_test()
+    if not args.base:
+        parser.error("--base is required")
+
+    others = open_pr_claims(args.repo, args.pr, args.schemas_dir) if args.repo else {}
+    errors = check(args.base, args.head, args.schemas_dir, others)
+    for message in errors:
+        print(f"::error::{message}")
+    if errors:
+        return 1
+    print("schema claims OK")
+    return 0
+
+
+if __name__ == "__main__":
+    sys.exit(main())
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index eebf9ae..b228f40 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -6,6 +6,7 @@ on:
 
 permissions:
   contents: read
+  pull-requests: read
 
 concurrency:
   group: ci-${{ github.ref }}
@@ -33,7 +34,13 @@ jobs:
             exit 1
           fi
 
-      - name: JVM tests
+      - name: Dependency policy self-test
+        run: python3 .github/scripts/check_dependency_policy.py --self-test
+
+      - name: No Google services, only OSI licences on the release classpath (AD-17)
+        run: ./gradlew :app:checkDependencyPolicy
+
+      - name: JVM tests (include the persisted-name check)
         run: ./gradlew :core:test :llm:test :data:testDebugUnitTest
 
       - name: Build debug APK
@@ -53,3 +60,27 @@ jobs:
             core/build/reports/tests
             llm/build/reports/tests
             data/build/reports/tests
+
+  schema-claims:
+    # One Room schema version, claimed once (AD-19). Needs the PR's own history, so it only runs on pull requests.
+    if: github.event_name == 'pull_request'
+    runs-on: ubuntu-latest
+    timeout-minutes: 10
+    steps:
+      - uses: actions/checkout@v4
+        with:
+          ref: ${{ github.event.pull_request.head.sha }}
+          fetch-depth: 0
+
+      - name: Schema claim self-test
+        run: python3 .github/scripts/check_schema_claims.py --self-test
+
+      - name: Reject reused or edited schema versions
+        env:
+          GH_TOKEN: ${{ github.token }}
+          BASE_REF: ${{ github.base_ref }}
+          REPO: ${{ github.repository }}
+          PR_NUMBER: ${{ github.event.pull_request.number }}
+        run: |
+          git fetch --no-tags origin "$BASE_REF:refs/remotes/origin/$BASE_REF"
+          python3 .github/scripts/check_schema_claims.py --base "origin/$BASE_REF" --repo "$REPO" --pr "$PR_NUMBER"
diff --git a/README.md b/README.md
new file mode 100644
index 0000000..593ac91
--- /dev/null
+++ b/README.md
@@ -0,0 +1,35 @@
+# Lumynoid
+
+Lumynoid is an Android app for chatting with an AI model using your own API key. There is no Lumynoid account and no Lumynoid server: the app talks directly to the endpoint you choose (an OpenAI-compatible provider or Anthropic).
+
+> Status: early development. The first releases are pre-releases (`v0.x`).
+
+## Install
+
+Lumynoid is distributed only as a signed APK downloaded manually from this repository's [GitHub Releases](../../releases). There is no Play Store or F-Droid listing and the app does not update itself: to update, download the newer APK.
+
+Each release carries one `lumynoid-<version>.apk` and a `SHA256SUMS` file. The release signing-key fingerprint will be published here when the first signed release ships.
+
+## Build
+
+Requirements: JDK 17 and the Android SDK (platform 37).
+
+```
+./gradlew :app:assembleDebug          # debug APK, package dev.lusalhi.lumynoid.debug
+./gradlew :core:test :llm:test :data:testDebugUnitTest
+```
+
+## Checks that gate every change
+
+CI runs on every push and pull request, and these gates fail the build:
+
+| Gate | What it enforces | Run it locally |
+| --- | --- | --- |
+| `:core` stays pure | `:core` has no Android, Ktor or Room dependency (AD-1) | see `.github/workflows/ci.yml` |
+| Dependency policy | The release runtime classpath, transitive dependencies included, has no `com.google.android.gms`, `com.google.firebase`, `com.google.mlkit` or `com.google.android.play`, and every dependency declares an OSI-approved licence. Exceptions go in `config/license-allowlist.txt` with a reason (AD-17) | `./gradlew :app:checkDependencyPolicy` |
+| Schema versions | A pull request may not edit an exported Room schema, reuse a version already on the base branch, or claim a version another open pull request claims (AD-19) | `python3 .github/scripts/check_schema_claims.py --base origin/main` |
+| Persisted names | Every persisted subtype and enum entry has an explicit snake_case `@SerialName` (AD-19) | `./gradlew :core:test` |
+
+## License
+
+Copyright 2026 lusalhi. Licensed under the [Apache License, Version 2.0](LICENSE).
diff --git a/_bmad-output/initiative-lumynoid/tickets/epic-chat-dan-provider/tickets/story-repo-publik-berlisensi-dengan-gerbang-ci-plan.md b/_bmad-output/initiative-lumynoid/tickets/epic-chat-dan-provider/tickets/story-repo-publik-berlisensi-dengan-gerbang-ci-plan.md
new file mode 100644
index 0000000..36aa734
--- /dev/null
+++ b/_bmad-output/initiative-lumynoid/tickets/epic-chat-dan-provider/tickets/story-repo-publik-berlisensi-dengan-gerbang-ci-plan.md
@@ -0,0 +1,103 @@
+---
+title: 'Public licensed repo with CI gates'
+type: chore
+ticket: '2'
+created: '2026-11-15'
+status: in-progress
+baseline_revision: 'bca406260d72dcdd1bbe770cdf45e4168ecefeca'
+route: full
+route_source: auto
+risk: medium
+review: ''
+review_source: ''
+lenses_ran: []
+review_loop_iteration: 0
+context:
+  - '{project-root}/_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md'
+---
+
+<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">
+
+## Intent
+
+**Problem:** The repo is private with no licence or README, and CI only builds and runs tests. Nothing yet stops a PR from adding Google/Firebase or non-OSI dependencies (AD-17), two parallel PRs from claiming the same Room version (AD-19), or a persisted subtype from lacking an explicit `@SerialName` (AD-19 serialisation rule). Story 3 publishes releases from this repo, so it must be public and gated first.
+
+**Approach:** Add `LICENSE` (Apache-2.0) and `README.md`, then three CI gates that fail the build: a dependency-policy check on the resolved release runtime classpath, a Room schema-version claim check on PRs, and a `@SerialName` check for persisted types. Finally prove each gate fails on a throw-away PR; the owner then makes the repo public.
+
+## Boundaries & Constraints
+
+**Decisions (human, 2026-11-15):** Licence is Apache-2.0, copyright line `Copyright 2026 lusalhi`. The owner flips the repo to public by hand after reviewing; the implementer never changes visibility. Keep the full plan, no split.
+
+**Always:** Gates run on `push` and `pull_request` with `contents: read` only and no secrets. Each check has a self-test that proves it fails on a bad input. Check logic lives in versioned scripts or Gradle tasks that run locally with one command, not inline YAML. Forbidden groups are exactly `com.google.android.gms`, `com.google.firebase`, `com.google.mlkit`, `com.google.android.play`. Exceptions to the licence check go in a committed allowlist file with a reason per entry. `applicationId`, versions and signing are untouched.
+
+**Never:** Do not make the repo public. No release workflow, tags, signing keys, secrets or GitHub Environments (story 3). No AD-24 manifest-permission check (not in this ticket's scope). No history rewriting. Test PRs are never merged and are closed with their branches deleted. No change to production source other than what the gates need.
+
+## I/O & Edge-Case Matrix
+
+| Scenario | Input / State | Expected Output / Behavior | Error Handling |
+|----------|--------------|---------------------------|----------------|
+| Clean tree | Current `main` state | All three gates pass | No error expected |
+| Forbidden group | PR adds a `com.google.firebase` dependency (also when transitive) | Dependency gate fails naming the coordinate | CI error annotation |
+| Non-OSI or missing licence | A resolved artifact has no OSI licence and no allowlist entry | Dependency gate fails naming it | CI error annotation |
+| Version already claimed | PR adds `N.json` that exists on base, or another open PR adds the same file | Schema gate fails naming the version and the other claimant | CI error annotation |
+| Edited old schema | PR modifies an existing exported schema file | Schema gate fails | CI error annotation |
+| Unnamed subtype | A persisted `@Serializable` subtype lacks `@SerialName`, or its name is not snake_case | Serialisation gate fails naming the class | Test failure |
+
+</frozen-after-approval>
+
+## Code Map
+
+- `.github/workflows/ci.yml` -- existing job (AD-1 check, tests, debug APK); add the three gates, a `pull_request`-only schema step with `fetch-depth: 0`, and `pull-requests: read` for it.
+- `build.gradle.kts`, `gradle/libs.versions.toml` -- root build; home for a dependency-policy task that resolves `:app` `releaseRuntimeClasspath` (maven POM licences via artifact resolution query, parent POMs followed).
+- `app/build.gradle.kts` -- applies Android; `releaseRuntimeClasspath` exists even with no `release` signing; do not change.
+- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskEvent.kt` -- persisted roots `Origin`, `TaskEvent`, enum `FailureReason`; all already have snake_case `@SerialName`. `core/src/test/.../TaskRunnerTest.kt` -- pattern for JVM tests (`kotlin("test")`, JUnit).
+- `data/schemas/dev.lusalhi.lumynoid.data.LumynoidDatabase/1.json` -- the only exported schema; `data/.../LumynoidDatabase.kt` declares `version = 1`.
+- `.gitignore` -- keep `local.properties` ignored; `app/debug.keystore` is committed on purpose (shared debug key, AD-25).
+- No `README*`, `LICENSE*`, `main` branch or `.github/scripts` exist. Remote has only `initiative-lumynoid`; the repo is private.
+
+## Tasks & Acceptance
+
+**Execution:**
+- [x] `LICENSE`, `README.md` -- licence text per decision; README states what Lumynoid is, that the APK comes only from GitHub Releases, licence, how to build and run the gates; release-key fingerprint placeholder deferred to story 3 -- C14, AD-25
+- [x] `build.gradle.kts`, `config/license-allowlist.txt` -- task `checkDependencyPolicy`: resolve `:app:releaseRuntimeClasspath`, fail on forbidden groups and on non-OSI or unknown licences unless allowlisted -- AD-17
+- [x] `.github/scripts/check_schema_claims.py` + self-test -- compare schema files on the PR against base and against other open PRs (`gh api`) -- AD-19
+- [x] `core/src/test/.../SerialNamePolicyTest.kt` -- reflect over each persisted root's sealed subclasses and enum entries; require explicit snake_case serial names; include a negative fixture proving a missing name is detected -- AD-19
+- [x] `.github/workflows/ci.yml` -- wire the three gates into the job
+- [ ] Verify live: on a private repo, push a base branch `main`, then open throw-away PRs (Firebase dependency; reused schema number); both must fail, then close them and delete their branches
+- [ ] Hand over: report that the repo is ready to flip public, with the history's author email noted for the owner
+
+**Acceptance Criteria:**
+- Given a clean checkout, when `./gradlew checkDependencyPolicy :core:test` runs, then it succeeds.
+- Given a test PR adding `com.google.firebase:firebase-common`, when CI runs, then the dependency gate fails and names it.
+- Given a test PR adding schema `1.json` content changes or a number already on base, when CI runs, then the schema gate fails.
+- Given the private repo after push, when GitHub detects the licence, then `licenseInfo` reports Apache-2.0; the owner's later flip to public is the ticket's final step.
+
+## Implementation Notes
+
+Implemented directly in the main session (no subagent was requested). Local verification passed: `checkDependencyPolicy`, `:core:test`, `:llm:test`, `:data:testDebugUnitTest`, `:app:assembleDebug`, both Python self-tests, and the `:core` purity grep.
+
+- Deviation from the plan: the dependency rules live in `.github/scripts/check_dependency_policy.py` (with `--self-test`), fed by a Gradle report task in `gradle/dependency-policy.gradle.kts` (applied from `:app`); `checkDependencyPolicy` is an `Exec` over that script. This makes the rules testable without a Gradle fixture. The root `build.gradle.kts` is unchanged.
+- Negative check done locally: adding `com.google.firebase:firebase-common:21.0.0` to `:app` made `checkDependencyPolicy` fail on 5 `gms`/`firebase` modules (restored afterwards). It also exposed a bug: the report task had no inputs, so Gradle skipped it as up to date after a dependency change; both tasks now always run.
+- Mutation check done locally: removing one `@SerialName` from `TaskEvent` made `SerialNamePolicyTest` fail (restored afterwards).
+- Classpath today: 144 modules, all declaring Apache-2.0 or MIT; the allowlist starts empty.
+- Step-03 forbids push and remote operations, so the **live** task (create `main`, throw-away PRs for Firebase and a reused schema number, close them) is NOT done. The schema gate's `gh api` open-PR scan and the `schema-claims` job were exercised only by the local self-test, not on GitHub. The task stays unchecked until after review.
+- The final plan task is a hand-over: the owner flips the repo public; the history's author email (`hammam47@gmail.com`) becomes public with it.
+
+## Plan Change Log
+
+## Review Triage Log
+
+## Design Notes
+
+- The serialisation gate uses serial descriptors, not text matching: an unnamed subtype's default `serialName` is its fully qualified class name, so "contains a dot" detects the missing annotation exactly. Residual risk: a new persisted root must be added to the test's root list; the test file says so.
+- The schema gate cannot see PRs opened after its own run; the later PR's run catches the collision, which is acceptable because both cannot merge green.
+
+## Verification
+
+**Commands:**
+- `./gradlew checkDependencyPolicy :core:test :llm:test :data:testDebugUnitTest` -- expected: BUILD SUCCESSFUL
+- `python3 .github/scripts/check_schema_claims.py --self-test` -- expected: exit 0
+- `gh repo view lusalhi/lumynoid --json licenseInfo` -- expected: Apache-2.0 (visibility stays PRIVATE until the owner flips it)
+
+**Manual checks (if no CLI):**
+- The two throw-away PRs show a failing CI check with the policy message.
diff --git a/app/build.gradle.kts b/app/build.gradle.kts
index a651deb..7338291 100644
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -68,3 +68,6 @@ dependencies {
     implementation(libs.compose.foundation)
     implementation(libs.compose.material3)
 }
+
+// AD-17: the release classpath gate.
+apply(from = rootProject.file("gradle/dependency-policy.gradle.kts"))
diff --git a/config/license-allowlist.txt b/config/license-allowlist.txt
new file mode 100644
index 0000000..ee19e14
--- /dev/null
+++ b/config/license-allowlist.txt
@@ -0,0 +1,3 @@
+# Exceptions to the AD-17 licence check (.github/scripts/check_dependency_policy.py).
+# One entry per line: <group>:<artifact> | <reason>. Blank lines and lines starting with # are ignored.
+# Every entry needs a reason that names the licence the artifact is really under.
diff --git a/core/src/test/kotlin/dev/lusalhi/lumynoid/core/SerialNamePolicyTest.kt b/core/src/test/kotlin/dev/lusalhi/lumynoid/core/SerialNamePolicyTest.kt
new file mode 100644
index 0000000..505a89e
--- /dev/null
+++ b/core/src/test/kotlin/dev/lusalhi/lumynoid/core/SerialNamePolicyTest.kt
@@ -0,0 +1,74 @@
+package dev.lusalhi.lumynoid.core
+
+import kotlinx.serialization.ExperimentalSerializationApi
+import kotlinx.serialization.Serializable
+import kotlinx.serialization.SerialName
+import kotlinx.serialization.descriptors.PolymorphicKind
+import kotlinx.serialization.descriptors.SerialDescriptor
+import kotlinx.serialization.descriptors.SerialKind
+import kotlinx.serialization.descriptors.elementDescriptors
+import kotlinx.serialization.descriptors.elementNames
+import kotlinx.serialization.serializer
+import kotlin.test.Test
+import kotlin.test.assertEquals
+import kotlin.test.assertTrue
+
+/**
+ * AD-19 / Consistency Conventions: every persisted subtype and enum entry carries an explicit, never-changing
+ * snake_case `@SerialName`. Without one the stored name is the class's fully qualified name, so a rename or a
+ * package move silently orphans old rows (they would decode to `Unknown`).
+ *
+ * A new persisted sealed root or enum must be added to [persistedRoots]; the test cannot discover it.
+ */
+@OptIn(ExperimentalSerializationApi::class)
+class SerialNamePolicyTest {
+    private val snakeCase = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
+
+    private val persistedRoots: List<SerialDescriptor> = listOf(
+        serializer<Origin>().descriptor,
+        serializer<TaskEvent>().descriptor,
+        serializer<FailureReason>().descriptor,
+    )
+
+    /** Names that reach storage under [root]: sealed subtype names and enum entry names. Returns the offenders. */
+    private fun violations(root: SerialDescriptor, seen: MutableSet<String> = mutableSetOf()): List<String> {
+        if (!seen.add(root.serialName)) return emptyList()
+        val own = when {
+            root.kind == SerialKind.ENUM ->
+                root.elementNames.filterNot(snakeCase::matches).map { "enum ${root.serialName} entry '$it'" }
+            root.kind == PolymorphicKind.SEALED -> subtypes(root).filterNot { snakeCase.matches(it.serialName) }
+                .map { "sealed subtype '${it.serialName}' of ${root.serialName}" }
+            else -> emptyList()
+        }
+        val children = (if (root.kind == PolymorphicKind.SEALED) subtypes(root) else root.elementDescriptors.toList())
+        return own + children.flatMap { violations(it, seen) }
+    }
+
+    private fun subtypes(sealed: SerialDescriptor): List<SerialDescriptor> =
+        sealed.getElementDescriptor(1).elementDescriptors.toList()
+
+    @Test
+    fun everyPersistedTypeHasAnExplicitSnakeCaseSerialName() {
+        persistedRoots.forEach { root ->
+            assertEquals(emptyList(), violations(root), "persisted names under ${root.serialName} need an explicit @SerialName")
+        }
+    }
+
+    @Test
+    fun theCheckRejectsAnUnnamedSubtypeAndAMisCasedEnumEntry() {
+        val found = violations(serializer<UnnamedFixture>().descriptor) + violations(serializer<MisCasedFixture>().descriptor)
+        assertTrue(found.any { "Unnamed" in it }, "unnamed subtype not detected: $found")
+        assertTrue(found.any { "MisCased" in it || "Camel" in it }, "mis-cased enum entry not detected: $found")
+    }
+
+    @Serializable
+    private sealed interface UnnamedFixture {
+        @Serializable
+        data object Unnamed : UnnamedFixture
+    }
+
+    @Serializable
+    private enum class MisCasedFixture {
+        @SerialName("CamelCase") ENTRY,
+    }
+}
diff --git a/gradle/dependency-policy.gradle.kts b/gradle/dependency-policy.gradle.kts
new file mode 100644
index 0000000..7eb2e07
--- /dev/null
+++ b/gradle/dependency-policy.gradle.kts
@@ -0,0 +1,90 @@
+// Dependency policy support (AD-17). Applied from :app.
+// `releaseClasspathReport` writes every module on the resolved release runtime classpath, transitive ones
+// included, with the licences its POM (or the nearest parent POM) declares. `checkDependencyPolicy` feeds
+// that report to .github/scripts/check_dependency_policy.py, which owns the rules and has its own self-test.
+
+import org.gradle.api.artifacts.component.ModuleComponentIdentifier
+import org.gradle.api.artifacts.result.ResolvedDependencyResult
+import org.w3c.dom.Element
+import javax.xml.parsers.DocumentBuilderFactory
+
+val reportFile = layout.buildDirectory.file("reports/dependency-policy/release-runtime-classpath.json")
+
+data class PomInfo(val licenses: List<Pair<String, String>>, val parent: Triple<String, String, String>?)
+
+fun Element.child(tag: String): Element? =
+    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == tag }
+
+fun Element.childText(tag: String): String = child(tag)?.textContent?.trim().orEmpty()
+
+fun readPom(group: String, name: String, version: String): PomInfo? {
+    val detached = configurations.detachedConfiguration(dependencies.create("$group:$name:$version@pom"))
+    detached.isTransitive = false
+    val file = detached.incoming.artifactView { isLenient = true }.files.files.firstOrNull() ?: return null
+    val factory = DocumentBuilderFactory.newInstance()
+    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
+    val project = factory.newDocumentBuilder().parse(file).documentElement
+    val licenses = project.child("licenses")?.let { node ->
+        (0 until node.childNodes.length).map { node.childNodes.item(it) }.filterIsInstance<Element>()
+            .filter { it.tagName == "license" }.map { it.childText("name") to it.childText("url") }
+    }.orEmpty()
+    val parent = project.child("parent")?.let {
+        Triple(it.childText("groupId"), it.childText("artifactId"), it.childText("version"))
+    }
+    return PomInfo(licenses, parent)
+}
+
+/** Licences declared by the module's POM, falling back to the nearest parent POM that declares any. */
+fun licensesOf(group: String, name: String, version: String): List<Pair<String, String>> {
+    var current: Triple<String, String, String>? = Triple(group, name, version)
+    var depth = 0
+    while (current != null && depth < 6) {
+        val pom = readPom(current.first, current.second, current.third) ?: return emptyList()
+        if (pom.licenses.isNotEmpty()) return pom.licenses
+        current = pom.parent
+        depth++
+    }
+    return emptyList()
+}
+
+val releaseClasspathReport = tasks.register("releaseClasspathReport") {
+    group = "verification"
+    description = "Lists the release runtime classpath with each module's declared licences."
+    outputs.file(reportFile)
+    // The classpath is the input and Gradle cannot see it here; a stale report would let a bad dependency through.
+    outputs.upToDateWhen { false }
+    notCompatibleWithConfigurationCache("resolves POM files through detached configurations at execution time")
+    doLast {
+        val root = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.root
+        val seen = linkedSetOf<ModuleComponentIdentifier>()
+        val stack = ArrayDeque(listOf(root))
+        val visited = mutableSetOf<Any>()
+        while (stack.isNotEmpty()) {
+            val component = stack.removeLast()
+            if (!visited.add(component.id)) continue
+            (component.id as? ModuleComponentIdentifier)?.let(seen::add)
+            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { stack.add(it.selected) }
+        }
+        val modules = seen.sortedBy { "${it.group}:${it.module}" }.map { id ->
+            mapOf(
+                "group" to id.group,
+                "name" to id.module,
+                "version" to id.version,
+                "licenses" to licensesOf(id.group, id.module, id.version).map { (n, u) -> mapOf("name" to n, "url" to u) },
+            )
+        }
+        val out = reportFile.get().asFile
+        out.parentFile.mkdirs()
+        out.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("modules" to modules))))
+    }
+}
+
+tasks.register<Exec>("checkDependencyPolicy") {
+    group = "verification"
+    description = "Fails on forbidden Google groups and on dependencies without an OSI licence (AD-17)."
+    dependsOn(releaseClasspathReport)
+    val script = rootProject.file(".github/scripts/check_dependency_policy.py")
+    val allowlist = rootProject.file("config/license-allowlist.txt")
+    outputs.upToDateWhen { false }
+    commandLine("python3", script.absolutePath, "--report", reportFile.get().asFile.absolutePath, "--allowlist", allowlist.absolutePath)
+}

=== END DIFF ===
