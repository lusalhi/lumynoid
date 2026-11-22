#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
# Match the pinned archive's modes regardless of the caller's umask.
umask 002

here=$(cd -- "$(dirname -- "$0")" && pwd)
out=$(realpath -m "${1:?Usage: build-rootfs.sh output-directory}")
metadata=$here/../../userland/src/main/assets/lumynoid-userland.properties
asset=$(sed -n 's/^asset=//p' "$metadata")
mkdir -p "$out"
work=$(mktemp -d)
as_root=()
if (( EUID != 0 )); then as_root=(sudo -n); fi
trap '"${as_root[@]}" rm -rf -- "$work"' EXIT
root=$work/root
mkdir -p "$root/packages" "$work/downloads"
tar --extract --file "$here/alpine-inputs.tar" --directory "$work/downloads"
while read -r digest url; do
    name=${url##*/}
    file=$work/downloads/$name
    # The repository carries the pinned bytes; Alpine's live index can drop old revisions.
    printf '%s  %s\n' "$digest" "$file" | sha256sum --check --status
    case "$name" in
        *.tar.gz) tar --extract --gzip --no-same-owner --no-same-permissions --file "$file" --directory "$root" ;;
        *.apk) cp -- "$file" "$root/packages/$name" ;;
    esac
done < "$here/alpine-inputs.lock"

guest=()
if [[ $(uname -m) != aarch64 ]]; then
    cp -- "$(command -v qemu-aarch64-static)" "$root/qemu-aarch64-static"
    guest=(/qemu-aarch64-static)
fi
packages=()
for apk in "$root"/packages/*.apk; do packages+=("/packages/${apk##*/}"); done
"${as_root[@]}" chroot "$root" "${guest[@]}" /sbin/apk add --no-network --no-scripts "${packages[@]}"
awk -F: '/^P:/{p=$2} /^V:/{print p "\t" $2}' "$root/lib/apk/db/installed" | sort > "$work/installed.tsv"
tail -n +2 "$here/alpine-source-provenance.tsv" | cut -f1,2 | sort > "$work/expected.tsv"
diff -u "$work/expected.tsv" "$work/installed.tsv"
"${as_root[@]}" rm -rf -- "$root/packages" "$root/qemu-aarch64-static"
mkdir -p "$root/workspace"

"${as_root[@]}" tar --sort=name --format=gnu --mtime=@0 --numeric-owner --owner=0 --group=0 \
    --directory "$root" --create --file - . | gzip -n > "$work/$asset"
mv -- "$work/$asset" "$out/$asset"
cp -- "$here/alpine-inputs.lock" "$here/alpine-source-provenance.tsv" "$out/"
sha256sum "$out/$asset"
wc -c "$out/$asset"
