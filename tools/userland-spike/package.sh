#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 && $# != 3 ]]; then
    printf 'Usage: bash %s /path/to/proot /path/to/loader [npm-rootfs.tar.gz]\n' "$0" >&2
    exit 2
fi
here=$(cd -- "$(dirname -- "$0")" && pwd)
repo=$(cd -- "$here/../.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME to the Android SDK directory}}
bt=$sdk/build-tools/37.0.0
platform=$sdk/platforms/android-37.0/android.jar
out=$repo/build/diagnostics/userland
apk=lumynoid-userland-probe.apk
version=()

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work"/{assets,classes,lib/arm64-v8a} "$out"
cp -- "$1" "$work/lib/arm64-v8a/libproot.so"
cp -- "$2" "$work/lib/arm64-v8a/libproot_loader.so"
if [[ $# == 3 ]]; then
    cp -- "$3" "$work/assets/rootfs.tar.gz"
    cp -- "$here/npm-fixture.sh" "$work/assets/npm-fixture.sh"
    mkdir -p "$work/fixture/package"
    printf '%s\n' '{"name":"lumynoid-fixture","version":"1.0.0","main":"index.js"}' \
        > "$work/fixture/package/package.json"
    printf '%s\n' 'module.exports = "lumynoid-offline-ok";' > "$work/fixture/package/index.js"
    tar -C "$work/fixture" -czf "$work/assets/lumynoid-fixture.tgz" package
    apk=lumynoid-proot-npm-probe.apk
    version=(--replace-version --version-code 2 --version-name 0.2)
else
    curl -fSsL --max-time 60 https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.8-aarch64.tar.gz \
        -o "$work/assets/rootfs.tar.gz"
    printf '%s  %s\n' 36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4 "$work/assets/rootfs.tar.gz" | sha256sum --check
fi

javac --release 17 -cp "$platform" -d "$work/classes" "$here/ProbeActivity.java"
"$bt/d8" --lib "$platform" --min-api 29 --output "$work" \
    "$work/classes/dev/lusalhi/lumynoid/userlandprobe/ProbeActivity.class"
"$bt/aapt2" link -I "$platform" --manifest "$here/AndroidManifest.xml" -A "$work/assets" \
    "${version[@]}" -o "$work/unsigned.apk"
(cd "$work" && zip -q -0 unsigned.apk classes.dex lib/arm64-v8a/*.so)
"$bt/zipalign" -P 16 -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$bt/apksigner" sign --ks "$repo/app/debug.keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false \
    --out "$out/$apk" "$work/aligned.apk"
printf '%s\n' "$out/$apk"
