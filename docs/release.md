# Releasing Lumynoid (owner runbook)

This is for the project owner. Releases are signed APKs published to GitHub Releases by `.github/workflows/release.yml`
when a `v*` tag is pushed (AD-25). The signing key never enters the repository, CI logs or any chat or agent session.

The package name `dev.lusalhi.lumynoid` and the signing key cannot change after the first signed APK ships.

## 1. Create the release key (once, offline)

Do this on your own machine, not in CI and not with an assistant.

```
keytool -genkeypair -v -storetype PKCS12 -keystore lumynoid-release.p12 \
  -alias lumynoid -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=Lumynoid, O=lusalhi"
```

Choose a strong password (PKCS12 uses one password for the store and the key). Write down the alias (`lumynoid`).

## 2. Back it up twice, outside GitHub

Encrypt the keystore and store the password with it in two separate places you own (for example a password manager
vault and an encrypted offline drive):

```
gpg --symmetric --cipher-algo AES256 lumynoid-release.p12     # writes lumynoid-release.p12.gpg
```

Losing the key means nobody can ever update an installed Lumynoid; a leaked key means anyone can forge updates.

## 3. Print the fingerprint

```
keytool -list -v -keystore lumynoid-release.p12 -alias lumynoid | grep 'SHA256:'
```

Commit the colon-separated hex (not the password) as the only non-comment line of `config/release-cert-sha256.txt`,
and put the same value in the README fingerprint slot. The release workflow rejects the placeholder and any APK
whose signing certificate differs.

## 4. Register in Play Console (before the first tag)

Android developer verification needs the package and key registered. In Play Console with your full developer account:
check that `dev.lusalhi.lumynoid` is available, register the package, and add the SHA-256 fingerprint from step 3.
Do not push a tag until the console shows the package and fingerprint as **Registered**. Pre-release `v0.x` APKs count
as the first signed APK.

## 5. Set up the GitHub Environment `release`

In the repository: Settings, Environments, New environment `release`.

- Deployment branches and tags: add a tag rule `v*`.
- Required reviewers: yourself.

Then add the four secrets to that environment only (not repository secrets):

```
base64 -w0 lumynoid-release.p12 | gh secret set RELEASE_KEYSTORE_BASE64 --env release
gh secret set RELEASE_KEYSTORE_PASSWORD --env release
gh secret set RELEASE_KEY_ALIAS --env release        # lumynoid
gh secret set RELEASE_KEY_PASSWORD --env release     # same as the store password for PKCS12
```

`gh secret set` prompts for the value, so nothing lands in shell history.

## 6. Cut a release

1. Raise `versionCode` and set `versionName` in `app/build.gradle.kts` (`versionName` is `v0.<n>.<patch>`, the tag name).
2. Merge to `main` by pull request.
3. Tag the merge commit and push the tag:
   ```
   git fetch origin && git tag v0.1.0 origin/main && git push origin v0.1.0
   ```
4. The `verify` job checks the tag (on `main`, name equals `versionName`, `versionCode` higher than every other `v*` tag,
   Room schemas on main's line) and runs the gates and tests. Approve the `release` environment when the `build` job waits.
5. The workflow signs, checks the certificate against the committed fingerprint, and publishes a pre-release for `v0.*`
   with `lumynoid-<versionName>.apk`, the pinned Alpine rootfs, native source archives, provenance, and `SHA256SUMS`.
   The workflow checks that the rootfs matches the digest and size embedded in the APK.

Run the tag checks locally before tagging: `python3 .github/scripts/check_release.py tag --tag v0.1.0 --main origin/main`
(after creating the tag), and `python3 .github/scripts/check_release.py --self-test`.

A rejected tag can be deleted (`git push origin :refs/tags/vX`, `git tag -d vX`) and re-cut after fixing the cause.

## 7. Restore drill

Prove the backup works before you need it: decrypt a backup on a clean machine, run the `keytool -list` command from
step 3, and confirm the SHA-256 equals the README fingerprint. Repeat for the second backup.

## Projects handset acceptance (Story 8.8)

Use a physical arm64 handset running Epic 2's signed `v0.3.1`. Under separate disposable chats, create
`upgrade-note.txt` and `upgrade-preview.html` with a visible script marker, install `@lumynoid/json-tools`
without writing any files, and send an ordinary chat message without using tools. Record the chat names
and original log entries. Install the signed Projects APK over `v0.3.1` without uninstalling or clearing
data. The first two chats must become distinct projects; the ordinary chat stays plain. In the
package-only chat, write a fresh JSON file and format it with the existing package without
reinstalling. With the handset offline, reopen both old files from their original log events;
the HTML script marker must appear only after selecting **Enable JavaScript**.

1. Create a disposable project. In chat A, write `tracer.json`, install `@lumynoid/json-tools`, and
   send a unique chat-only marker. In chat B, read, edit and format `tracer.json` without reinstalling.
   Relaunch and confirm that both chats see the same files, package and project instructions but not
   each other's history or log. Try the same file from a second project and a plain chat: neither may
   see its contents or open its preview, and plain chat must still answer.
2. Set a distinctive project instruction, then ask chat B to overwrite `tracer.json`. While the
   approval card is pending, change the instruction from the project screen. Verify that deletion
   is unavailable while work is pending. Return to the task and approve: the resumed answer should
   follow the original instruction, and a new turn should follow the updated one. A controlled
   provider capture, if used, must omit other chats' messages from both requests.
3. Delete chat A after confirmation. Verify its history and log are gone, and chat B can still use
   the file and package. Confirm deletion of the disposable project and force-stop Lumynoid during
   cleanup without clearing its data. On relaunch, look for **Resume deletion**; the marked project
   must not accept new work or previews. Resume and verify it is gone while the second project,
   provider and shared runtime still work. If cleanup finishes before the force-stop or deletion
   never starts, that attempt does not prove recovery; use another disposable fixture to retry.

Record the device, OS, signed APK digests and outcome of each case. Record which cases could not be
observed separately; a debug or Redroid pass does not replace signed handset acceptance.

## If the key leaks

v1 does not rotate keys. If it leaks, rotate through an APK Signature Scheme v3 lineage and register the new key in
Play Console before the first APK that uses it (AD-25).
