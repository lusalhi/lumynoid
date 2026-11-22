"""Verify extracted arm64 runtime executables and the archive pinned by an APK."""
import argparse
import hashlib
import struct
import zipfile
from pathlib import Path

RUNTIME_LIBRARIES = ("libproot.so", "libproot_loader.so", "libuserland_supervisor.so")
PAGE_SIZE = 16 * 1024


def check_elf(name, binary):
    if binary[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name} is not a little-endian ELF64 file")
    elf_type, architecture = struct.unpack_from("<HH", binary, 16)
    expected_type = 2 if name == "libproot_loader.so" else 3
    if elf_type != expected_type or architecture != 183:
        raise ValueError(f"{name} has an unexpected AArch64 ELF type")
    program_offset = struct.unpack_from("<Q", binary, 32)[0]
    entry_size, entry_count = struct.unpack_from("<HH", binary, 54)
    if entry_size != 56:
        raise ValueError(f"{name} has an unexpected ELF64 program header size")
    load_count = 0
    for index in range(entry_count):
        kind, flags, offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", binary, program_offset + index * entry_size
        )
        if kind != 1:
            continue
        load_count += 1
        if alignment < PAGE_SIZE or (offset - address) % PAGE_SIZE != 0:
            raise ValueError(f"{name} has a LOAD segment that is not 16 KiB aligned")
        if flags & 3 == 3:
            raise ValueError(f"{name} has a LOAD segment that is writable and executable")
    if not load_count:
        raise ValueError(f"{name} has no LOAD segments")
    print(f"{name}: {load_count} aligned LOAD segments")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("rootfs_directory", type=Path)
    parser.add_argument("--test-apk", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as apk:
        for name in RUNTIME_LIBRARIES:
            entry = apk.getinfo(f"lib/arm64-v8a/{name}")
            if entry.compress_type != zipfile.ZIP_DEFLATED:
                raise ValueError(f"{name} must be packaged for extraction")
            check_elf(name, apk.read(entry))
        if any(name.startswith("assets/lumynoid-rootfs-") for name in apk.namelist()):
            raise ValueError("The production APK includes the downloadable rootfs")
        metadata = dict(
            line.split("=", 1)
            for line in apk.read("assets/lumynoid-userland.properties").decode("ascii").splitlines()
        )
    archive = args.rootfs_directory / metadata["asset"]
    with archive.open("rb") as rootfs:
        digest = hashlib.file_digest(rootfs, "sha256").hexdigest()
    if archive.stat().st_size != int(metadata["size"]) or digest != metadata["sha256"]:
        raise ValueError("The staged rootfs does not match the APK's pinned digest and size")
    print(f"{archive.name}: digest and size match APK metadata")
    if args.test_apk:
        with zipfile.ZipFile(args.test_apk) as test_apk:
            fixture = test_apk.getinfo(f"assets/{archive.name}.fixture")
            with test_apk.open(fixture) as test_rootfs:
                fixture_digest = hashlib.file_digest(test_rootfs, "sha256").hexdigest()
            if fixture.file_size != int(metadata["size"]) or fixture_digest != digest:
                raise ValueError("The instrumentation fixture does not match the release archive")
        print(f"{archive.name}: instrumentation fixture matches the staged archive")


if __name__ == "__main__":
    main()
