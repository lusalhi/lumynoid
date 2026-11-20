#!/usr/bin/env bash
set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME to the Android SDK directory}}
ndk=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}
bin=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC=${CC:-$bin/aarch64-linux-android29-clang}
export AR=${AR:-$bin/llvm-ar}
export LDFLAGS=-Wl,-z,max-page-size=16384

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cd "$work"
curl -fSsL --max-time 60 https://codeload.github.com/termux/proot/zip/refs/tags/v5.1.107.102 -o proot.zip
curl -fSsL --max-time 60 https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz -o talloc.tgz
sha256sum --check <<'DIGESTS'
30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852  proot.zip
912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007  talloc.tgz
DIGESTS
unzip -q proot.zip
tar -xzf talloc.tgz
mkdir deps
cp "$here/talloc-cross-answers.txt" cross-answers.txt
(
    cd talloc-2.5.0
    ./configure --prefix="$work/deps" --disable-rpath --disable-python --cross-compile \
        --cross-answers="$work/cross-answers.txt"
    make -j"${JOBS:-4}"
    cd bin/default
    "$AR" rcs "$work/deps/libtalloc.a" talloc.c.5.o lib/replace/{replace,closefrom}.c.2.o
)

cd proot-5.1.107.102/src
cpp="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$work/talloc-2.5.0 -DARG_MAX=131072"
flags=(HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/nonexistent "LD=$CC $LDFLAGS"
    "LDFLAGS=-L$work/deps -ltalloc -Wl,-z,noexecstack $LDFLAGS")
# Upstream's ashmem compatibility extension uses strcmp/memset without including string.h.
make "${flags[@]}" "CPPFLAGS=$cpp -include string.h" extension/ashmem_memfd/ashmem_memfd.o
make -j"${JOBS:-4}" "${flags[@]}" "CPPFLAGS=$cpp"
bash "$here/package.sh" "$PWD/proot" "$PWD/loader/loader"
