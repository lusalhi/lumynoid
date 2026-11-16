#!/usr/bin/env python3
"""AD-25 release gate: what a `v*` tag and its signed APK must satisfy before anything is published.

`tag` mode (no secrets needed) checks a tag against the repository:
  * the tagged commit is an ancestor of the main branch,
  * the tag name equals `versionName` in app/build.gradle.kts,
  * `versionCode` is higher than the code of every other `v*` tag (read from git, not from Releases),
  * the Room schemas at the tag are on main's migration line: identical to main's, versions 1..N with no gaps,
    and the @Database version equals the highest schema.

`apk` mode checks a built APK from the text output of `apksigner verify --print-certs` and
`aapt2 dump badging`: signing certificate SHA-256 equals the committed fingerprint (a placeholder is rejected),
exactly one signer, package, versionCode and versionName match the source, and the only native ABI is arm64-v8a.

Every failure is reported, not only the first. Failures print as `::error::` annotations and exit 1.

  check_release.py tag --tag v0.1.0 --main origin/main
  check_release.py apk --certs certs.txt --badging badging.txt --gradle app/build.gradle.kts \\
                       --fingerprint config/release-cert-sha256.txt
  check_release.py --self-test
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

GRADLE_FILE = "app/build.gradle.kts"
SCHEMAS_DIR = "data/schemas"
DATABASE_FILE = "LumynoidDatabase.kt"
FINGERPRINT_FILE = "config/release-cert-sha256.txt"
RELEASE_ABIS = {"arm64-v8a"}


def git(*args, cwd=None, check=True):
    result = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def show(rev, path, cwd):
    return git("show", f"{rev}:{path}", cwd=cwd, check=False)


def read_gradle(text):
    """Returns (applicationId, versionCode, versionName); a missing value is None."""
    def find(pattern):
        match = re.search(pattern, text, re.M)
        return match.group(1) if match else None

    code = find(r"^\s*versionCode\s*=\s*(\d+)\s*$")
    return find(r'^\s*applicationId\s*=\s*"([^"]+)"'), int(code) if code else None, find(r'^\s*versionName\s*=\s*"([^"]+)"')


def schema_blobs(rev, cwd):
    """Maps each schema path at `rev` to its blob id."""
    blobs = {}
    for line in git("ls-tree", "-r", rev, "--", SCHEMAS_DIR, cwd=cwd).splitlines():
        meta, path = line.split("\t", 1)
        blobs[path] = meta.split()[2]
    return blobs


def check_schema_line(tag, main, cwd):
    errors = []
    at_tag, at_main = schema_blobs(tag, cwd), schema_blobs(main, cwd)
    for path, blob in sorted(at_tag.items()):
        if path not in at_main:
            errors.append(f"{path}: schema at {tag} is missing on {main}; it is off main's migration line")
        elif at_main[path] != blob:
            errors.append(f"{path}: schema at {tag} differs from {main}; it is off main's migration line")
    versions = {}
    for path in at_tag:
        match = re.fullmatch(rf"{re.escape(SCHEMAS_DIR)}/([^/]+)/(\d+)\.json", path)
        if match:
            versions.setdefault(match.group(1), []).append(int(match.group(2)))
    for database, found in sorted(versions.items()):
        found.sort()
        if found != list(range(1, len(found) + 1)):
            errors.append(f"{SCHEMAS_DIR}/{database}: schema versions {found} are not 1..{len(found)} without gaps")
    db_paths = [p for p in git("ls-tree", "-r", "--name-only", tag, cwd=cwd).splitlines() if p.endswith("/" + DATABASE_FILE)]
    highest = max((v for found in versions.values() for v in found), default=None)
    for db_path in db_paths:
        match = re.search(r"\bversion\s*=\s*(\d+)", show(tag, db_path, cwd))
        if not match:
            errors.append(f"{db_path}: could not read the database version")
        elif highest is None or int(match.group(1)) != highest:
            errors.append(f"{db_path}: database version {match.group(1)} is not the highest exported schema ({highest})")
    if not db_paths:
        errors.append(f"{DATABASE_FILE} not found at {tag}")
    return errors


def check_tag(tag, main, cwd=None):
    errors = []
    if git("rev-parse", "--verify", "-q", f"{tag}^{{commit}}", cwd=cwd, check=False).strip() == "":
        return [f"tag {tag} does not exist"]
    if subprocess.run(["git", "merge-base", "--is-ancestor", f"{tag}^{{commit}}", main], cwd=cwd).returncode != 0:
        errors.append(f"{tag} is not an ancestor of {main}; release only from commits on main")
    _, code, name = read_gradle(show(tag, GRADLE_FILE, cwd))
    if name != tag:
        errors.append(f"tag {tag} does not match versionName {name!r} in {GRADLE_FILE}")
    if code is None:
        errors.append(f"versionCode not found in {GRADLE_FILE} at {tag}")
    else:
        for other in git("tag", "--list", "v*", cwd=cwd).split():
            if other == tag:
                continue
            _, other_code, _ = read_gradle(show(other, GRADLE_FILE, cwd))
            if other_code is not None and other_code >= code:
                errors.append(f"versionCode must rise: {tag} has {code} but {other} has {other_code}")
    errors += check_schema_line(tag, main, cwd)
    return errors


def normalize_fingerprint(text):
    return re.sub(r"[^0-9a-f]", "", text.lower())


def read_committed_fingerprint(text):
    lines = [l.strip() for l in text.splitlines() if l.strip() and not l.lstrip().startswith("#")]
    return lines[0] if lines else ""


def check_apk(certs, badging, gradle, fingerprint_text):
    errors = []
    app_id, code, name = read_gradle(gradle)
    committed = read_committed_fingerprint(fingerprint_text)
    expected = normalize_fingerprint(committed)
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or not re.fullmatch(r"[0-9A-Fa-f:]+", committed):
        errors.append(f"{FINGERPRINT_FILE} is still a placeholder or not a SHA-256 fingerprint: {committed!r}")
        expected = None
    # `apksigner verify --print-certs` prints "V2 Signer: ..." lines (or "Signer #1 ..." with -v), one set per scheme.
    digests = sorted({normalize_fingerprint(d) for d in re.findall(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", certs)})
    if len(digests) != 1:
        errors.append(f"expected exactly one signing certificate, apksigner reported {len(digests)}")
    for digest in digests:
        if expected and digest != expected:
            errors.append(f"signing certificate {digest} is not the registered key {expected}")
    package = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", badging, re.M)
    if not package:
        errors.append("aapt2 badging has no package line")
    else:
        if package.group(1) != app_id:
            errors.append(f"APK package {package.group(1)!r} is not {app_id!r}")
        if package.group(2) != str(code):
            errors.append(f"APK versionCode {package.group(2)!r} is not {code}")
        if package.group(3) != name:
            errors.append(f"APK versionName {package.group(3)!r} is not {name!r}")
    abis = set()
    for line in re.findall(r"^native-code:(.*)$", badging, re.M):
        abis |= set(re.findall(r"'([^']+)'", line))
    if abis != RELEASE_ABIS:
        errors.append(f"APK native ABIs {sorted(abis)} are not exactly {sorted(RELEASE_ABIS)}")
    return errors


def self_test():
    failed = 0
    total = 0

    def expect(title, errors, needle=None):
        nonlocal failed, total
        total += 1
        wanted_failure = needle is not None
        ok = bool(errors) == wanted_failure and (not needle or any(needle in e for e in errors))
        if not ok:
            failed += 1
            print(f"SELF-TEST FAIL: {title}: expected {('failure containing ' + repr(needle)) if wanted_failure else 'pass'}, got {errors}")

    with tempfile.TemporaryDirectory() as repo:
        def run(*args):
            return git(*args, cwd=repo)

        def write(path, text):
            full = os.path.join(repo, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w") as f:
                f.write(text)

        def commit(message):
            run("add", "-A")
            run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", message)

        def gradle(code, name):
            write(GRADLE_FILE, f'applicationId = "dev.x.app"\nversionCode = {code}\nversionName = "{name}"\n')

        def database(version):
            write(f"data/src/main/kotlin/x/{DATABASE_FILE}", f"@Database(\n    version = {version},\n)\n")

        def schema(n, text=None):
            write(f"{SCHEMAS_DIR}/x.Db/{n}.json", text or f"schema {n}")

        run("init", "-q", "-b", "main")
        gradle(1, "v0.1.0")
        database(1)
        schema(1)
        commit("first")
        run("tag", "v0.1.0")
        run("update-ref", "refs/remotes/origin/main", "HEAD")
        expect("first release, no earlier tag", check_tag("v0.1.0", "origin/main", repo))

        gradle(2, "v0.2.0")
        database(2)
        schema(2)
        commit("second")
        run("tag", "v0.2.0")
        run("update-ref", "refs/remotes/origin/main", "HEAD")
        expect("second release, code and schema rise", check_tag("v0.2.0", "origin/main", repo))

        run("tag", "v0.2.1")  # same commit, same code
        expect("second tag on the same commit", check_tag("v0.2.1", "origin/main", repo), "versionCode must rise: v0.2.1 has 2 but v0.2.0 has 2")
        run("tag", "-d", "v0.2.1")

        gradle(3, "v0.1.0")
        commit("name mismatch")
        run("tag", "v0.3.0")
        run("update-ref", "refs/remotes/origin/main", "HEAD")
        expect("tag differs from versionName", check_tag("v0.3.0", "origin/main", repo), "does not match versionName")

        run("checkout", "-q", "-b", "side")
        gradle(4, "v0.4.0")
        commit("off main")
        run("tag", "v0.4.0")
        run("checkout", "-q", "main")
        expect("tag off main", check_tag("v0.4.0", "origin/main", repo), "not an ancestor")

        run("checkout", "-q", "-b", "schemas", "main")
        gradle(5, "v0.5.0")
        database(3)
        schema(4, "skips version 3")
        commit("bad schemas")
        run("tag", "v0.5.0")
        run("update-ref", "refs/remotes/origin/main", "HEAD")
        errors = check_tag("v0.5.0", "origin/main", repo)
        expect("schema gap", errors, "not 1..3 without gaps")
        expect("database version not the highest schema", errors, "database version 3 is not the highest exported schema (4)")
        expect("every failure is reported, not only the first", [] if len(errors) >= 2 else ["only one error"])

        run("checkout", "-q", "-b", "drift", "v0.2.0")
        gradle(6, "v0.6.0")
        commit("v0.6.0")
        run("tag", "v0.6.0")
        schema(2, "schema 2 rewritten after the tag")
        commit("main rewrites schema 2")
        run("update-ref", "refs/remotes/origin/main", "HEAD")
        expect("schema at tag differs from main", check_tag("v0.6.0", "origin/main", repo), "differs from origin/main")

    gradle_text = 'applicationId = "dev.x.app"\nversionCode = 7\nversionName = "v0.7.0"\n'
    good_fp = "AB:CD:" + "12:" * 29 + "34"
    certs = f"Verifies\nV2 Signer: certificate DN: CN=x\nV2 Signer: certificate SHA-256 digest: {good_fp.replace(':', '').lower()}\n"
    badging = "package: name='dev.x.app' versionCode='7' versionName='v0.7.0' platformBuildVersionName='17'\nnative-code: 'arm64-v8a'\n"
    expect("apk happy path", check_apk(certs, badging, gradle_text, "# fingerprint\n" + good_fp + "\n"))
    expect("apk placeholder fingerprint", check_apk(certs, badging, gradle_text, "PLACEHOLDER-replace\n"), "placeholder")
    other = certs.replace("12", "99")
    expect("apk wrong key", check_apk(other, badging, gradle_text, good_fp), "is not the registered key")
    expect("apk two signers", check_apk(certs + certs.replace("1", "9"), badging, gradle_text, good_fp), "exactly one signing certificate")
    expect("apk same key under v2 and v3", check_apk(certs + certs.replace("V2", "V3"), badging, gradle_text, good_fp))
    expect("apk wrong package", check_apk(certs, badging.replace("dev.x.app", "evil.app"), gradle_text, good_fp), "package")
    expect("apk wrong versionCode", check_apk(certs, badging.replace("'7'", "'6'"), gradle_text, good_fp), "versionCode")
    expect("apk wrong versionName", check_apk(certs, badging.replace("v0.7.0", "v0.8.0"), gradle_text, good_fp), "versionName")
    expect("apk extra ABI", check_apk(certs, badging.replace("'arm64-v8a'", "'arm64-v8a' 'x86_64'"), gradle_text, good_fp), "native ABIs")
    expect("apk no native code", check_apk(certs, badging.replace("native-code: 'arm64-v8a'\n", ""), gradle_text, good_fp), "native ABIs")

    if failed:
        return 1
    print(f"self-test passed ({total} cases)")
    return 0


def report(errors, ok_message):
    for message in errors:
        print(f"::error::{message}")
    if errors:
        return 1
    print(ok_message)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true")
    sub = parser.add_subparsers(dest="mode")
    tag = sub.add_parser("tag")
    tag.add_argument("--tag", required=True)
    tag.add_argument("--main", default="origin/main")
    apk = sub.add_parser("apk")
    apk.add_argument("--certs", required=True, help="output of `apksigner verify --print-certs`")
    apk.add_argument("--badging", required=True, help="output of `aapt2 dump badging`")
    apk.add_argument("--gradle", default=GRADLE_FILE)
    apk.add_argument("--fingerprint", default=FINGERPRINT_FILE)
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.mode == "tag":
        return report(check_tag(args.tag, args.main), f"tag {args.tag} OK")
    if args.mode == "apk":
        def read(path):
            with open(path) as f:
                return f.read()

        errors = check_apk(read(args.certs), read(args.badging), read(args.gradle), read(args.fingerprint))
        return report(errors, "apk OK")
    parser.error("choose `tag`, `apk` or --self-test")


if __name__ == "__main__":
    sys.exit(main())
