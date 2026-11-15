#!/usr/bin/env python3
"""AD-19 gate: one Room schema version, claimed once.

A pull request "claims" a database version by adding the exported schema file `<db>/<N>.json` under
the schemas directory. The gate fails when the pull request:

* edits or deletes a schema file that already existed at the point it branched (history is immutable),
* adds a schema file the base branch tip has since gained (the version was claimed on base first), or
* adds a schema file that another open pull request also adds (two PRs race for the same version).

Usage in CI:  check_schema_claims.py --base origin/main --repo OWNER/NAME --pr 42
Run `check_schema_claims.py --self-test` to see each rule reject a throw-away repository.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

SCHEMAS_DIR = "data/schemas"


def git(*args, cwd=None, check=True):
    result = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def schema_blobs(rev, schemas_dir, cwd):
    """Maps each schema path at `rev` to its blob id."""
    out = git("ls-tree", "-r", rev, "--", schemas_dir, cwd=cwd)
    blobs = {}
    for line in out.splitlines():
        meta, path = line.split("\t", 1)
        blobs[path] = meta.split()[2]
    return blobs


def check(base, head, schemas_dir, other_prs, cwd=None):
    """other_prs maps a PR label to the schema paths it adds. Returns a list of error strings."""
    fork_point = git("merge-base", base, head, cwd=cwd).strip()
    at_fork, at_base, at_head = (schema_blobs(rev, schemas_dir, cwd) for rev in (fork_point, base, head))
    errors = []
    for path, blob in sorted(at_fork.items()):
        if path not in at_head:
            errors.append(f"{path}: an exported schema was deleted; schema history is immutable")
        elif at_head[path] != blob:
            errors.append(f"{path}: an existing exported schema was edited; add a new version instead")
    added = sorted(set(at_head) - set(at_fork))
    for path in added:
        if path in at_base:
            errors.append(f"{path}: this database version is already claimed on the base branch; renumber your migration")
        for label, paths in sorted(other_prs.items()):
            if path in paths:
                errors.append(f"{path}: this database version is also claimed by {label}; only one of you may take it")
    return errors


def open_pr_claims(repo, own_pr, schemas_dir):
    """Schema paths added by every other open PR, read through the GitHub API (needs `gh` and a token)."""
    def api(endpoint):
        out = subprocess.run(["gh", "api", "--paginate", endpoint], capture_output=True, text=True, check=True).stdout
        # --paginate concatenates JSON arrays as `[...][...]`; decode them one by one.
        decoder, items, index = json.JSONDecoder(), [], 0
        while index < len(out):
            if out[index].isspace():
                index += 1
                continue
            page, index = decoder.raw_decode(out, index)
            items.extend(page)
        return items

    claims = {}
    for pr in api(f"repos/{repo}/pulls?state=open&per_page=100"):
        if pr["number"] == own_pr:
            continue
        files = api(f"repos/{repo}/pulls/{pr['number']}/files?per_page=100")
        added = [f["filename"] for f in files if f["status"] == "added" and f["filename"].startswith(schemas_dir + "/")]
        if added:
            claims[f"PR #{pr['number']}"] = added
    return claims


def self_test():
    schema = f"{SCHEMAS_DIR}/app.Db"
    with tempfile.TemporaryDirectory() as repo:
        def run(*args):
            return git(*args, cwd=repo)

        def write(name, text):
            path = os.path.join(repo, schema, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(text)

        def commit(message):
            run("add", "-A")
            run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message)

        run("init", "-q", "-b", "main")
        write("1.json", "v1")
        commit("v1")

        def branch(name, edit):
            run("checkout", "-q", "-b", name, "main")
            edit()
            commit(name)
            run("checkout", "-q", "main")

        branch("clean", lambda: write("2.json", "v2 clean"))
        branch("edits-old", lambda: write("1.json", "v1 edited"))
        branch("deletes-old", lambda: os.remove(os.path.join(repo, schema, "1.json")))
        branch("races-main", lambda: write("2.json", "v2 from branch"))
        branch("no-schema", lambda: write("../../../notes.txt", "x"))
        run("checkout", "-q", "main")
        write("2.json", "v2 on main")  # base gains version 2 after "races-main" branched
        commit("main gains v2")

        wanted = f"{schema}/2.json"
        run("branch", "base-before-v2", "main~1")  # base as it was when the branches were cut
        # (title, base, head, other open PRs, must fail)
        cases = [
            ("branch claiming a free version", "base-before-v2", "clean", {}, False),
            ("branch with no schema change", "main", "no-schema", {}, False),
            ("edited existing schema", "base-before-v2", "edits-old", {}, True),
            ("deleted existing schema", "base-before-v2", "deletes-old", {}, True),
            ("version claimed on base after branching", "main", "races-main", {}, True),
            ("version also claimed by an open PR", "base-before-v2", "clean", {"PR #7": [wanted]}, True),
            ("other PR claims a different version", "base-before-v2", "clean", {"PR #7": [f"{schema}/9.json"]}, False),
        ]
        failed = 0
        for title, base, head, others, should_fail in cases:
            errors = check(base, head, SCHEMAS_DIR, others, cwd=repo)
            if bool(errors) != should_fail:
                failed += 1
                print(f"SELF-TEST FAIL: {title}: expected {'failure' if should_fail else 'pass'}, got {errors}")
    if failed:
        return 1
    print(f"self-test passed ({len(cases)} cases)")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", help="the base branch tip, e.g. origin/main")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--schemas-dir", default=SCHEMAS_DIR)
    parser.add_argument("--repo", help="OWNER/NAME; with --pr, other open PRs are read through `gh`")
    parser.add_argument("--pr", type=int, default=0, help="this PR's number, excluded from the other-PR scan")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.base:
        parser.error("--base is required")

    others = open_pr_claims(args.repo, args.pr, args.schemas_dir) if args.repo else {}
    errors = check(args.base, args.head, args.schemas_dir, others)
    for message in errors:
        print(f"::error::{message}")
    if errors:
        return 1
    print("schema claims OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
