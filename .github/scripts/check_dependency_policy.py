#!/usr/bin/env python3
"""AD-17 dependency gate.

Reads the report written by `./gradlew :app:releaseClasspathReport` (every module on the resolved release
runtime classpath, transitive ones included, with the licences its POM declares) and fails when:

* a module belongs to a forbidden Google group (Play Services, Firebase, ML Kit, Play), or
* a module declares no OSI-approved licence and is not listed in config/license-allowlist.txt.

Run `check_dependency_policy.py --self-test` to prove both rules reject bad input.
"""
import argparse
import json
import re
import sys

FORBIDDEN_GROUPS = (
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.mlkit",
    "com.google.android.play",
)

# Each pattern is matched against a licence's name and its URL. Only OSI-approved licences belong here.
OSI_PATTERNS = [
    r"apache",  # Apache-2.0, in all the spellings POMs use
    r"\bmit\b|opensource\.org/licenses/mit",
    r"\bbsd\b|opensource\.org/licenses/bsd",
    r"\bisc\b",
    r"eclipse public license|\bepl\b|eclipse\.org/legal/epl",
    r"mozilla public license|\bmpl\b",
    r"gnu (lesser )?general public license|\bl?gpl\b",
    r"common development and distribution license|\bcddl\b",
    r"\bzlib\b",
    r"unicode",
]


def forbidden_group(group):
    return any(group == g or group.startswith(g + ".") for g in FORBIDDEN_GROUPS)


def is_osi(licence):
    haystack = f"{licence.get('name', '')} {licence.get('url', '')}".lower()
    return any(re.search(p, haystack) for p in OSI_PATTERNS)


def load_allowlist(path):
    entries = {}
    errors = []
    with open(path, encoding="utf-8") as f:
        for number, raw in enumerate(f, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            coordinate, sep, reason = (part.strip() for part in line.partition("|"))
            if not sep or not reason or coordinate.count(":") != 1:
                errors.append(f"{path}:{number}: expected '<group>:<artifact> | <reason>'")
                continue
            entries[coordinate] = reason
    return entries, errors


def check(modules, allowlist):
    """Returns (errors, unused allowlist entries)."""
    errors = []
    used = set()
    for module in modules:
        group, name, version = module["group"], module["name"], module["version"]
        coordinate = f"{group}:{name}"
        label = f"{coordinate}:{version}"
        if forbidden_group(group):
            errors.append(f"{label}: group '{group}' is forbidden (AD-17: no Google services)")
            continue
        licences = module.get("licenses", [])
        if any(is_osi(licence) for licence in licences):
            continue
        if coordinate in allowlist:
            used.add(coordinate)
            continue
        declared = ", ".join(l.get("name") or l.get("url") or "?" for l in licences) or "none declared"
        errors.append(f"{label}: no OSI licence ({declared}); fix the dependency or add it to config/license-allowlist.txt")
    return errors, sorted(set(allowlist) - used)


def self_test():
    ok = {"group": "org.jetbrains.kotlin", "name": "kotlin-stdlib", "version": "1",
          "licenses": [{"name": "The Apache Software License, Version 2.0", "url": ""}]}

    def failures(module, allowlist=None):
        return check([ok, module], allowlist or {})[0]

    cases = [
        ("clean classpath passes", [], check([ok], {})[0]),
        ("firebase rejected", ["com.google.firebase:firebase-common"],
         failures({"group": "com.google.firebase", "name": "firebase-common", "version": "1", "licenses": ok["licenses"]})),
        ("subgroup of gms rejected", ["com.google.android.gms.ads:x"],
         failures({"group": "com.google.android.gms.ads", "name": "x", "version": "1", "licenses": ok["licenses"]})),
        ("mlkit rejected", ["com.google.mlkit:x"],
         failures({"group": "com.google.mlkit", "name": "x", "version": "1", "licenses": ok["licenses"]})),
        ("play rejected", ["com.google.android.play:core"],
         failures({"group": "com.google.android.play", "name": "core", "version": "1", "licenses": ok["licenses"]})),
        ("similar group allowed", [],
         failures({"group": "com.google.android.material", "name": "material", "version": "1", "licenses": ok["licenses"]})),
        ("no licence rejected", ["acme:thing"], failures({"group": "acme", "name": "thing", "version": "1", "licenses": []})),
        ("proprietary rejected", ["acme:thing"],
         failures({"group": "acme", "name": "thing", "version": "1",
                   "licenses": [{"name": "Proprietary EULA", "url": "https://acme.example/eula"}]})),
        ("allowlisted accepted", [],
         failures({"group": "acme", "name": "thing", "version": "1", "licenses": []}, {"acme:thing": "MIT per its repo"})),
    ]
    bad = 0
    for title, expected_coordinates, errors in cases:
        got = sorted(e.split(":")[0] + ":" + e.split(":")[1] for e in errors)
        if got != sorted(expected_coordinates):
            bad += 1
            print(f"SELF-TEST FAIL: {title}: expected {expected_coordinates}, got {errors}")
    if bad:
        return 1
    print(f"self-test passed ({len(cases)} cases)")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report")
    parser.add_argument("--allowlist")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.report or not args.allowlist:
        parser.error("--report and --allowlist are required")

    with open(args.report, encoding="utf-8") as f:
        modules = json.load(f)["modules"]
    if not modules:
        print("::error::the dependency report is empty; the check would pass vacuously")
        return 1
    allowlist, allowlist_errors = load_allowlist(args.allowlist)
    errors, unused = check(modules, allowlist)
    for entry in unused:
        print(f"::warning::allowlist entry '{entry}' is not needed any more; remove it")
    for message in allowlist_errors + errors:
        print(f"::error::{message}")
    if errors or allowlist_errors:
        return 1
    print(f"dependency policy OK: {len(modules)} modules checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
