#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

here=$(cd -- "$(dirname -- "$0")" && pwd)
mkdir -p -- "${1:?Usage: build-native.sh output-directory}"
out=$(cd -- "$1" && pwd -P)
sdk=${2:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}
ndk_version=28.2.13676358
ndk=${sdk:?Pass the Android SDK directory}/ndk/$ndk_version
case "$(uname -s)" in
    Linux) host=linux-x86_64; make=make; checksum=(sha256sum) ;;
    Darwin) host=darwin-x86_64; make=gmake; checksum=(shasum -a 256) ;;
    *) printf 'Unsupported native build host: %s\n' "$(uname -s)" >&2; exit 1 ;;
esac
bin=$ndk/toolchains/llvm/prebuilt/$host/bin
sysroot=$bin/../sysroot
proot_revision=52d65a4dc140ca554d241a1438ddd4f6b00c9813
proot_url=https://codeload.github.com/termux/proot/zip/$proot_revision
talloc_url=https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz
proot_sha=ff605effefe6db8e90789e74375f12c635d4a4883420f08445309cfe5e8d7fa7
talloc_sha=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cd "$work"
if [[ $host == darwin-x86_64 ]]; then
    # The arm64 loader's make rules invoke readelf by name.
    ln -s "$bin/llvm-readelf" "$work/readelf"
    export PATH="$work:$PATH"
fi
if [[ $host == linux-x86_64 && $(uname -m) == aarch64 ]]; then
    # The Linux NDK distribution contains x86_64 host tools. Use the arm64 host tools
    # with the pinned NDK's Android sysroot and compiler runtime on a native arm64 host.
    resource=$(realpath "$bin"/../lib/clang/*)
    host_clang=$(command -v clang)
    host_lld=$(command -v ld.lld || command -v "ld.lld-$(clang -dumpversion | cut -d. -f1)")
    printf '#!/usr/bin/env bash\nexec "%s" --target=aarch64-linux-android29 --sysroot="%s" -resource-dir="%s" --ld-path="%s" -rtlib=compiler-rt "$@"\n' \
        "$host_clang" "$sysroot" "$resource" "$host_lld" > "$work/android-clang"
    chmod +x "$work/android-clang"
    export CC=$work/android-clang AR=$(command -v llvm-ar)
else
    export CC=$bin/aarch64-linux-android29-clang AR=$bin/llvm-ar
fi
export LDFLAGS=-Wl,-z,max-page-size=16384
curl -fsSL --retry 3 --max-time 120 "$proot_url" -o proot.zip
curl -fsSL --retry 3 --max-time 120 "$talloc_url" -o talloc.tar.gz
printf '%s  %s\n' "$proot_sha" proot.zip "$talloc_sha" talloc.tar.gz | "${checksum[@]}" --check
unzip -q proot.zip
tar -xzf talloc.tar.gz
mkdir deps
cp "$here/../userland-spike/talloc-cross-answers.txt" cross-answers.txt
(
    cd talloc-2.5.0
    ./configure --prefix="$work/deps" --disable-rpath --disable-python --cross-compile \
        --cross-answers="$work/cross-answers.txt"
    "$make" -j"${JOBS:-4}"
    cd bin/default
    "$AR" rcs "$work/deps/libtalloc.a" talloc.c.5.o lib/replace/{replace,closefrom}.c.2.o
)

proot=$work/proot-$proot_revision
patch -d "$proot" -p1 < "$here/proot-exitkill.patch"
cd "$proot/src"
cpp="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$work/talloc-2.5.0 -DARG_MAX=131072"
flags=(HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/nonexistent "LD=$CC $LDFLAGS"
    "LDFLAGS=-L$work/deps -ltalloc -Wl,-z,noexecstack $LDFLAGS")
# Upstream's ashmem compatibility extension omits the header for strcmp/memset.
"$make" "${flags[@]}" "CPPFLAGS=$cpp -include string.h" extension/ashmem_memfd/ashmem_memfd.o
"$make" -j"${JOBS:-4}" "${flags[@]}" "CPPFLAGS=$cpp"

jni=$out/jni/arm64-v8a
sources=$out/sources
mkdir -p "$jni" "$sources"
install -m 0755 proot "$jni/libproot.so"
install -m 0755 loader/loader "$jni/libproot_loader.so"
"$CC" -std=c11 -D_GNU_SOURCE -Wall -Wextra -Werror -O2 "$LDFLAGS" \
    "$here/supervisor.c" -o "$jni/libuserland_supervisor.so"
install -m 0644 "$work/proot.zip" "$sources/proot-$proot_revision.zip"
install -m 0644 "$work/talloc.tar.gz" "$sources/talloc-2.5.0.tar.gz"
install -m 0644 "$here/proot-exitkill.patch" "$sources/"
{
    printf 'Android NDK %s\n' "$ndk_version"
    "$CC" --version
    printf '\nSHA-256\tSource URL\n%s\t%s\n%s\t%s\n' "$proot_sha" "$proot_url" "$talloc_sha" "$talloc_url"
    printf '\nPRoot and its loader are GPL-licensed. talloc is LGPL-licensed.\n'
    printf 'The source archives include the applicable copyright and license notices.\n'
    printf 'The PRoot patch and the tools/userland build scripts accompany the release source.\n'
} > "$sources/native-provenance.txt"
