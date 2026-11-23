---
title: 'APK Projects bertanda tangan lulus uji upgrade dan dua percakapan di HP'
type: chore
ticket: '8'
created: '2026-11-23'
status: done
baseline_revision: 'acf8ba8155be68cdbfecd18579624f27d43aed16'
route: oneshot
route_source: auto
risk: high
review: quick
review_source: pinned
lenses_ran: []
review_loop_iteration: 0
context:
  - '{project-root}/docs/release.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Projects already has local and Redroid evidence, but `v0.3.1` (Epic 2) is still the latest signed APK. In-place migration and the complete Projects flow have not been accepted on the target handset.

**Approach:** Prepare `v0.4.0` (version code 6), ship it with the existing owner-gated tag workflow, then install it over signed `v0.3.1` on a physical arm64 handset. Do not change the package, signing certificate, or feature scope; debug and unsigned builds cannot satisfy handset acceptance.

</frozen-after-approval>

## Implementation Notes

`docs/release.md` and `.github/workflows/release.yml` define the main/tag, signing and owner approval gates. The baseline is the signed Epic 2 APK `v0.3.1` at `d43180e4d2e96bb87c9bac0781b78dbf180d1de9`.

The initial release-preparation commit bumped the version and expanded the handset runbook. The release PR also includes the preceding Projects implementation, including Room migrations 5–7, and the standalone diagnostic variant. The official package and signer are unchanged.

Downloaded the published Story 2.12 baseline and independently verified its `SHA256SUMS` entry, registered certificate, package, version and ABI. Signed `v0.3.1` SHA-256: `815c57331583028f860722e19a5ded6aed4da8d8b53a6f29385e6228ba39ba08`.

## Plan Change Log

## Review Triage Log

- Inline quick review: the product package and signing rules stay unchanged, and the new code exceeds the baseline's code 5. The handset runbook keeps baseline setup separate from the in-project tracer and does not count a completed deletion or Redroid run as physical recovery evidence. No findings deferred.

- Cubic follow-up: corrected release and diagnostic coverage descriptions, and added the userland migration probe suite to both workflow JVM steps. Registry validation now covers names hidden from plain chats without exposing their tool definitions.
- Historical evidence: the 8.4 counts are correct for its recorded implementation revision. Epic 2 teardown was tested on a separate handset diagnostic; saved provider identity was not explicitly recorded and is now part of the Projects upgrade runbook. The earlier approval story remains `built` pending its device checks.
- Local regression: all 47 TaskRunner tests and all four `LegacyWorkspaceProbeTest` cases passed, including the new plain-chat registry validation test.

## Verification

- [x] Build the unsigned candidate with `./gradlew --offline --max-workers=2 :app:packageRelease :userland:buildUserlandRootfs`; use `aapt2`, `zipalign -c -P 16 4`, and `tools/userland/verify-apk.py` to confirm package, version, non-debuggable arm64 ABI, alignment and matching runtime. Run `python3 .github/scripts/check_release.py --self-test`.
- [x] Publish signed `v0.4.1` from the official tag workflow and verify `SHA256SUMS`, the registered certificate, package/version, alignment, and the APK-pinned rootfs.
- [x] Given signed `v0.3.1` on a physical arm64 handset with a file/HTML chat, a package-only chat, and an ordinary chat, when signed `v0.4.1` is installed in place, then both eligible chats become separate projects without copying workspace data; the ordinary chat stays plain, saved provider access remains, old files/package/logs remain usable, and offline text/HTML previews open from their original events (scripts off by default).
- [x] Given a new project, when the first chat writes and installs the bundled package and the second reads, edits and formats the same file, then both share files, the package and instructions across relaunch, but never histories or logs. Another project and a plain chat cannot access those files; plain chat still answers. Preview access from the wrong project or a deleting project is denied.
- [x] Given a task paused for overwrite approval, when project instructions change and the task resumes, then that task uses its original instructions and the next task sees the new ones, without sending another chat's history.
- [x] Given two chats in a project, when one is deleted after confirmation, then only its conversation and log disappear. With pending work, deletion is denied. When project deletion is interrupted and resumed after relaunch, then the marked project rejects new work and preview; resuming removes its chats, logs, instructions, files and packages without harming another project, the provider or shared runtime.

Record the physical device and OS, both APK digests, the GitHub Actions run, and evidence or failure for each handset case. Do not reset app data or treat emulator runs as signed handset evidence.

**Local v0.4.1 evidence:** Gradle packaging and lint-vital passed. `aapt2` reported `dev.lusalhi.lumynoid` `v0.4.1` (code 7), target API 37 and only `arm64-v8a`. ZIP and all three runtime ELFs passed 16 KiB alignment; the staged rootfs matched APK metadata. The debug and unsigned release APKs contained no diagnostic launcher, schema or rootfs fixture. Release checker: 19 self-tests passed.

**Supplementary handset evidence (owner report):** All six [standalone Projects diagnostic](../../../plan-8-projects-one-tap-apk.md#verification) scenarios passed on a physical phone. The diagnostic uses a separate package and fixture data; it is not the signed in-place upgrade.

**Release recovery:** [PR #8](https://github.com/lusalhi/lumynoid/pull/8) merged with green CI and schema checks, but its completed Cubic check reported 16 findings. The [v0.4.0 workflow](https://github.com/lusalhi/lumynoid/actions/runs/45109632829) passed verification and was cancelled before signing at the owner's request to fix valid findings first. The original tag is unchanged and no v0.4.0 APK was published.

**Corrected candidate:** [PR #9](https://github.com/lusalhi/lumynoid/pull/9) merged with passing CI and zero new Cubic findings on the final revision. On isolated arm64 API 34, all three Projects instrumentation cases passed twice with no fixture rows left. After the final follow-up, the two affected instrumentation cases passed again and the diagnostic passed 6/6.

**Published:** Tag `v0.4.1` (code 7) points to `5c59abfa8ffa4f1029e5edffa48a2d29159fee7f`. The [release workflow](https://github.com/lusalhi/lumynoid/actions/runs/45114850629) passed `verify`, `build`, and `publish` after owner approval. All eight entries in `SHA256SUMS` matched; the official non-debuggable arm64 APK matched the registered certificate, passed 16 KiB alignment, and matched the release rootfs metadata. The diagnostic launcher and assets are absent. Signed APK SHA-256: `1cf12aa6fcbf85eb720224e4cde68731d855d43b669f8d570d6f4f61e97e9ae4`.

**Official handset acceptance (owner report, 2026-11-23):** Samsung `SM-S731B`, Android API 37 (model confirmed by the owner on 2026-11-23). The owner confirmed all four acceptance scenario groups on signed `v0.4.1`:

- In-place upgrade from signed `v0.3.1` without uninstalling or clearing data retained chats, files, local packages, logs, old previews, and provider access.
- Two chats in one project shared files and packages without sharing history or logs. Another project and ordinary chat could not read the files.
- An approved pending task kept its original instructions; a fresh task received the updated instructions.
- Deleting a chat retained project files. The project deletion was interrupted, resumed with **Resume deletion**, and left the other project unaffected.

The handset report is complemented by the recorded Room, runtime and preview fixture checks. The current behavior after **Decline** is separate from this acceptance and is tracked as Story 2.13.
