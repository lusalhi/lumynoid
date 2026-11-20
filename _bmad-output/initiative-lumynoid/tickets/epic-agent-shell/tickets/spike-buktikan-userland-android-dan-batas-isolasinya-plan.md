---
title: 'Buktikan userland Android dan batas isolasinya'
type: chore
ticket: 1
created: '2026-11-20'
status: blocked
blocked_reason: 'Probe baca lulus di HP penguji; isolasi filesystem/egress, npm, dan lifecycle pada desain pengganti PRoot satu UID belum terbukti.'
baseline_revision: 55d7b1b48bdd66dcaa765097aa95989bce205af0
route: oneshot
route_source: auto
risk: high
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Belum ada bukti bahwa APK target SDK 37 bisa menjalankan userland Linux arm64 dan membatasi aksesnya. PRoot menerjemahkan path, bukan menyediakan batas keamanan setara kernel; satu read yang berhasil tidak membuktikan isolasi.

**Approach:** Ukur dua kandidat rootfs, periksa sumber dan batas keamanan kandidat, serta siapkan diagnostik terpisah dengan input fixture tetap. Bedakan bukti build di host dari bukti runtime di perangkat; pertahankan no-go untuk rilis sampai seluruh batas Epic 2 terbukti. Tidak ada shell dari model, kredensial, koneksi jaringan dari APK diagnostik, atau perubahan APK Lumynoid.

</frozen-after-approval>

## Implementation Notes

- Sumber: [PRoot upstream](https://proot-me.github.io/#description) menjelaskan bahwa resource jaringan host tetap tersedia; [PRoot-Distro](https://github.com/termux/proot-distro#proot-limitations) menegaskan bahwa PRoot tidak menyediakan namespace atau isolasi kernel. [Android Application Sandbox](https://source.android.com/docs/security/app-sandbox) mengisolasi UID aplikasi, bukan workspace berbeda di dalam UID yang sama. **No-go:** PRoot dalam UID app dengan bind mount dan proxy saja bukan batas untuk skrip tak tepercaya. Lulus cek baca tetap tidak membuka 2.5 atau 2.7.
- [Aturan Android untuk target API 29+](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission) mengharuskan binary app berasal dari APK, bukan dieksekusi langsung dari app home yang bisa ditulis. Probe mengeksekusi PRoot dan loader yang dipaketkan di `nativeLibraryDir`; kompatibilitas Android belum dibuktikan oleh pemeriksaan ELF.
- Source native: [Termux PRoot v5.1.107.102](https://github.com/termux/proot/tree/52d65a4dc140ca554d241a1438ddd4f6b00c9813) dan [talloc 2.5.0](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz), keduanya diverifikasi SHA-256. Arm64-only; SysV shmem helper dan loader 32-bit tidak dipaketkan. PRoot/loader memakai GNU GPL (lihat `COPYING` dan header source); library talloc LGPL-3.0-or-later. Jangan terbitkan binary tanpa source yang persis sesuai, script build, dan notice komponen.
- Pengukuran host pada rootfs kosong tanpa Node/npm; kolom isi menjumlahkan file biasa dari arsip, bukan konsumsi storage aktual di HP:

  | Arm64 rootfs | Unduhan terkompresi | Isi file biasa |
  | --- | ---: | ---: |
  | Alpine minirootfs 3.22.8 | 3,971,704 B | 8,532,050 B |
  | Debian bookworm-slim | 28,144,358 B | 97,241,709 B |

  Debian manifest `sha256:ec8534cda2cb30f29f44f23e135297af7a1496f2990fd8e4e84aa36b081a757a`, layer `sha256:2d66c782e4847b8495b17a4b86be1159aa13d140fe4fd17cb1d536953d5114c6`. Alpine dipilih hanya untuk probe berdasarkan ukuran base image; belum ada pilihan distro produksi.
- Kandidat untuk replan: tempatkan runtime di boundary OS yang tidak mendapat kredensial atau izin jaringan app, dengan antarmuka terbatas ke workspace dan egress. [Android isolated service](https://developer.android.com/guide/topics/manifest/service-element#isolated) layak diperiksa, tetapi dukungan untuk proses native, Node/npm, dan teardown belum terbukti. Jangan ganti invariant isolasi dengan heuristik nama command atau dialog approval.
- APK diagnostik adalah eksperimen kompatibilitas, bukan permukaan produksi. Kode aplikasi, workflow rilis, izin Lumynoid, dan arsitektur tidak diubah oleh eksperimen.

## Plan Change Log

## Review Triage Log

## Verification

- Host: [build.sh](../../../../../tools/userland-spike/build.sh) membangun APK dari source yang dipin. Di host Linux arm64, gunakan `CC` berupa Clang arm64 dengan target `aarch64-linux-android29`, sysroot dan compiler-rt NDK r28c, linker LLD arm64, serta `AR=llvm-ar`. Build berhasil dengan Clang dan LLD 18; compiler NDK x86-64 melalui emulasi tidak dipakai untuk build akhir.
- APK lokal: [`lumynoid-userland-probe.apk`](../../../../../build/diagnostics/userland/lumynoid-userland-probe.apk), 4,350,642 B, SHA-256 `2c5135fa99d28bd7653f89e3d1ce8b21d40f74e8dfad5f409bf587bd6d2edaba`. `aapt` melihat package terpisah tanpa permission manifest, minSdk 29, targetSdk 37, dan `arm64-v8a`. `apksigner verify` dan `zipalign -c -P 16 4` lulus. Kedua ELF native di APK adalah AArch64 dengan segmen LOAD 16 KiB-aligned; tidak ada segmen yang writable sekaligus executable. SHA-256 aset Alpine cocok dengan sumber.
- Perangkat (laporan penguji): ASUS `ASUS_AI2401_D`, Android API 36, APK diagnostik target SDK 37. Ekstraksi 202 ms; baca 103 ms, `exit 0`, hasil `3.22.8`. Jalur APK → PRoot/loader → baca fixture lulus di perangkat ini. Runtime pada perangkat API 37 belum diuji.
- Belum teruji: npm, batas proses/memori, pembersihan proses anak saat cancel, dan penegakan isolasi filesystem/egress pada desain baru. 2.2 tidak menunggu bukti itu; 2.5 tetap diblokir.
