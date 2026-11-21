---
title: 'Buktikan userland Android dan batas isolasinya'
type: chore
ticket: 1
created: '2026-11-20'
status: blocked
blocked_reason: 'PRoot, npm offline, dan cleanup lulus di Samsung API 37. Probe memakai fixture lokal dengan UID aplikasi diagnostik. Batas akses data privat, workspace lain, dan egress yang diminta Epic 2 masih terbuka untuk jalur PRoot.'
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

### Probe file dengan Android isolated service

- [APK diagnostik v0.1](../../../../../build/diagnostics/isolation/lumynoid-isolation-probe.apk) target SDK 37 memakai service dengan `isolatedProcess`. SHA-256 `f2fbe384986b4153d9c9aee2034613db9916334fb6374de8303b33d0ba37f9e3`.
- Pada Redroid arm64 API 34, keempat cek lulus. UID berbeda, satu file dummy yang diberikan lewat descriptor baca-saja terbaca, sementara dua fixture privat yang dipastikan terbaca dari proses utama tidak bisa dibuka dari service.
- Penguji melaporkan keempat cek juga lulus di HP. Model perangkat dan API untuk run ini masih menunggu konfirmasi.
- Bukti ini mencakup service Android dan fixture file. Runtime native, npm, egress, dan pembersihan proses anak masih memerlukan verifikasi. Spike tetap `blocked`.

### Probe pembukaan socket

- [APK diagnostik v0.2](../../../../../build/diagnostics/isolation-v2/lumynoid-isolation-probe-v2.apk) target SDK 37 mempertahankan cek file dan membandingkan pembukaan socket TCP serta UDP IPv4/IPv6 antara proses utama dan service. Proses utama menjadi kontrol dengan izin `INTERNET`. Probe hanya membuat dan menutup socket. SHA-256 `8f72edf9e7b0fc40dd07dff9fa08dd6fd0034d5b56eb4c78abae270fc831b6c2`.
- Redroid arm64 API 34 menampilkan `Files: PASS` dan `Sockets: FAIL` karena kedua proses bisa membuka keempat jenis socket. Hasil ini mengukur pembukaan socket. Kemampuan membuka koneksi, mengirim paket, dan penegakan kebijakan egress belum diukur.
- Penguji melaporkan hasil v0.2 di ASUS `ASUS_AI2401_D` API 36. `Files: PASS`, termasuk UID terpisah, file yang diberikan lewat descriptor terbaca, serta penolakan fixture privat dan workspace lain. `Sockets: PASS` dengan `host OPEN, isolated BLOCKED (EACCES)` untuk TCP dan UDP, masing-masing IPv4 dan IPv6. Hasil berbeda dari Redroid API 34. Cakupan bukti perangkat ini adalah pembukaan socket dari service terisolasi.

### Probe native child

- [APK diagnostik arm64](../../../../../build/diagnostics/native-probe/lumynoid-native-probe.apk) target SDK 37 menjalankan program C tetap dari lokasi native paket aplikasi. SHA-256 `041b5ecd4b0c5c942e80134f295bfa3523031d975fcf089454072abc0d0fd112`. Tanda tangan dan alignment 16 KiB lulus. Segmen eksekusi ELF memiliki izin `R E`.
- Pada Redroid arm64 API 34, `Separate UID`, `Native child start`, dan `Direct child cancellation` lulus. Fixture mencetak UID dan runner mengonfirmasi satu child langsung berhenti setelah dihentikan.
- Penguji melaporkan ketiga cek native child lulus di HP. Model dan API untuk run native belum tercantum di laporan. Bukti ini mencakup satu proses anak langsung.

### Probe pembersihan pohon proses

- [Native Probe v0.2](../../../../../build/diagnostics/native-probe-v2/lumynoid-native-probe-v2.apk) target SDK 37 dan arm64. SHA-256 `bf30e94e7af420c4958e23820b4bb97461facfaf37038bab3b5bfdc6c649813b`. Tanda tangan dan alignment 16 KiB lulus.
- Pada Redroid arm64 API 34, start native lulus dan masing-masing skenario cancel serta timeout menghentikan dua turunan fixture dalam satu grup dan mengonfirmasi keduanya lewat `waitpid`.

### Suite diagnostik gabungan

- [Lumynoid Spike 2.1 Suite](../../../../../build/diagnostics/spike-suite/lumynoid-spike-2-1-suite.apk) arm64, target SDK 37, SHA-256 `9a28b17f2ea4d27895c01f4a556ded9e19861f725b85cc4ea8c4594d8b8a37e2`. Satu service menjalankan cek fixture file dan socket, start native, pembersihan grup saat cancel dan timeout, serta batas proses dan ruang alamat virtual pada fixture native.
- Di Redroid API 34, cek file, output yang diberikan, pemblokiran fixture skill privat, start native, cancel, timeout, `RLIMIT_NPROC`, dan `RLIMIT_AS` lulus. Hasil socket sama dengan probe v0.2 di emulator ini. Cek sumber daya menunjukkan batas pada fixture, dengan kebutuhan produksi untuk diuji bersama runtime yang dipilih.
- Laporan penguji dari ASUS `ASUS_AI2401_D` API 36 menunjukkan semua cek yang dijalankan `PASS`. UID terpisah, baca dan tulis lewat descriptor yang diberikan berhasil, dan fixture privat app, workspace lain, serta data skill terblokir. Untuk TCP dan UDP pada IPv4 serta IPv6, lintasan kontrol `host OPEN` dan lintasan terisolasi `BLOCKED (EACCES)`. Start native lulus, cancel dan timeout masing-masing mengonfirmasi dua proses turunan selesai, serta `RLIMIT_NPROC` dan `RLIMIT_AS` lulus.
- Hasil layar `Node/npm: PENDING (isolated writable workspace needed)`. Desain direktori workspace terisolasi yang dapat ditulis menjadi pekerjaan berikutnya. Target perangkat API 37 dan verifikasi egress dengan runtime aktual masih terbuka.

### Preflight workspace POSIX

- [Aturan SELinux AOSP untuk isolated service](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app_all.te) mengizinkan I/O pada file app yang sudah dibuka dan diterima lewat IPC, sekaligus melarang service membuka sendiri file app. Descriptor direktori ke `filesDir` bukan kandidat workspace POSIX untuk npm menurut kebijakan ini.
- [Preflight Landlock APK target SDK 37](../../../../../build/diagnostics/workspace-preflight/lumynoid-workspace-preflight.apk) membuat subdirektori dan marker di workspace dummy, memblokir pembacaan fixture tetangga, dan menunjukkan output dari host. Hasil Redroid API 34 `Landlock ABI: 8`, `Workspace mkdir/write: PASS`, `Neighbor fixture blocked: PASS`, `Host sees output: PASS`, `Exit: 0`. Seccomp dan SELinux nonaktif di emulator itu. Sumber [kebijakan seccomp Bionic](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/) untuk aplikasi standar tidak mencantumkan syscall Landlock pada `SYSCALLS.TXT`, `SECCOMP_ALLOWLIST_APP.TXT`, atau `SECCOMP_ALLOWLIST_COMMON.TXT`. Hasil Redroid tidak memenuhi verifikasi platform produksi.
- [AVF/Microdroid](https://android.googlesource.com/platform/packages/modules/Virtualization/+/HEAD/libs/framework-virtualization/README.md) memerlukan izin sistem terbatas untuk mengelola VM. Kandidat untuk diperiksa selanjutnya adalah runtime Linux userspace dengan filesystem guest yang dapat ditulis, disimpan sebagai disk yang diserahkan secara eksplisit ke service terisolasi. Pilot harus memakai paket npm lokal, menjaga larangan akses app/workspace lain, dan mengukur kompatibilitas serta biaya startup di perangkat target.

### Preflight npm di Linux guest

- Host arm64 menjalankan QEMU 8.2.2 dengan TCG, dua vCPU, dan RAM guest 512 MiB. Guest memakai Alpine 3.22 dan kernel `6.12.118-0-virt`, Node `22.23.5`, serta npm `11.6.4`. Guest hanya menerima satu disk VirtIO ext4 96 MiB dan melihat interface `lo`. [Fixture init](../../../../../build/diagnostics/guest-npm/source/init) · [Log QEMU](../../../../../build/diagnostics/guest-npm/qemu-run.log)
- `npm install --offline --ignore-scripts` dari tarball sintetis lulus. Node memuat modul terpasang dan menulis `verified.txt`. Guest melepas disk, keluar dengan kode `0`, lalu dimatikan. Host membuka image dengan `debugfs` setelah shutdown dan mengonfirmasi isi modul serta marker hasil. `e2fsck -fn` lulus.
- npm melaporkan 29 detik untuk instalasi. Host mencatat 66,23 detik untuk boot sampai shutdown dan penggunaan memori puncak QEMU 700.884 KiB. Bukti ini berlaku untuk host. Kelayakan port runtime ke APK, biaya di HP, dan penegakan batas saat runtime aktual dipasang di service Android masih terbuka.

### APK guest dan cleanup disk sementara

- Keputusan penguji, 2026-11-21. Setiap run memakai disk fixture baru. Setelah hasil instalasi diverifikasi, disk run dibuang dan laporan disimpan. Runner host mengonfirmasi cleanup pada [run sukses](../../../../../build/diagnostics/guest-npm/reports/npm.U71wZa.log) dan [run yang sengaja digagalkan](../../../../../build/diagnostics/guest-npm/reports/npm.YQNPhg.log). Image hasil preflight awal tetap tersedia sebagai bukti.
- [APK lokal Guest npm Probe](../../../../../build/diagnostics/guest-android-run/lumynoid-guest-npm-probe.apk) arm64 target SDK 37. SHA-256 `592ba01b589d6df90432c492aeb9cfef4e0ce54a78b940af87ff9cdd8cadd203`. APK membuka QEMU dari direktori native instalasi dengan UID service terisolasi. Seed workspace disalin ke `memfd` baru per run. Guest memverifikasi modul dan marker setelah disk di-mount ulang read-only, kemudian shutdown. Setelah QEMU keluar, descriptor disk dilepas dan aplikasi menyimpan laporan terakhir secara atomik.
- [Redroid API 34](../../../../../build/diagnostics/guest-android-run/redroid-report.txt) menampilkan `Separate UID: PASS`, `Guest npm: PASS`, `Workspace cleanup: PASS`, dan exit guest `0`. npm melaporkan 23 detik. Pada run berikutnya, force-stop menghentikan proses QEMU dan laporan lulus terakhir tetap muncul setelah aplikasi dibuka lagi. Hasil emulator berlaku dengan SELinux/seccomp nonaktif.
- ASUS `ASUS_AI2401_D` API 36 dan Samsung `SM-S731B` API 37 melaporkan hasil yang sama. `Separate UID: PASS`, `Guest npm: FAIL (exit 5)`, `Workspace: not prepared`, dan `Open temporary disk read-only: Permission denied`. Launcher sudah membuat backing disk sebelum pembukaan ulang ditolak. Penanda workspace dicetak setelah langkah tersebut, sehingga laporan menyebut workspace belum disiapkan. QEMU dan npm belum berjalan pada kedua perangkat.
- Distribusi publik prototipe menunggu source yang sesuai dan notice untuk QEMU, kernel Linux, dan komponen bundel lainnya. Versi sumber sudah dipetakan untuk [komponen Ubuntu](../../../../../build/diagnostics/guest-android-run/ubuntu-source-provenance.tsv) dan [paket Alpine](../../../../../build/diagnostics/guest-android-run/alpine-source-provenance.tsv).

### Probe npm melalui PRoot

- Atas arahan penguji, diuji kelayakan runtime PRoot dalam satu APK sebelum membuat perubahan pada Epic 2. APK diagnostik menjalankan fixture yang dikemas dan offline dengan UID aplikasinya sendiri. Folder kerja biasa dibuat di cache dan dipetakan ke `/workspace`. Uji ini mencakup tulis/baca, `npm install --offline --ignore-scripts` dari satu tarball sintetis, dan pemanggilan skrip Node yang mengimpor modul lalu menulis hasilnya.
- [APK PRoot npm Probe](../../../../../build/diagnostics/userland/lumynoid-proot-npm-probe.apk) versi 0.2, arm64, target SDK 37, 31.236.926 B, SHA-256 `216cfd134e9da0c01ad7f763e96f9158ebd9448f9d86faf39637d605403da0aa`. Build memakai PRoot dan loader dari APK baca yang lulus pada ASUS, Alpine 3.22.8, Node 22.23.5 dan npm 11.6.4. Verifikasi tanda tangan dan alignment 16 KiB lulus. [Provenance Alpine](../../../../../build/diagnostics/proot-npm/alpine-source-provenance.tsv)
- [Redroid API 34](../../../../../build/diagnostics/proot-npm/redroid-report.txt) melaporkan `PRoot npm: PASS`, `Workspace read/write: PASS`, `Offline npm install: PASS`, `Node script: PASS`, dan `Workspace cleanup: PASS`. Ekstraksi 602 ms, runtime 2.204 ms, exit 0. Laporan terakhir tetap muncul setelah aplikasi ditutup dan dibuka ulang. Hasil ini berlaku di emulator dengan SELinux/seccomp nonaktif.
- Laporan penguji dari Samsung `SM-S731B` API 37 menunjukkan `PRoot npm: PASS`, `Workspace read/write: PASS`, `Offline npm install: PASS`, `Node script: PASS`, dan `Workspace cleanup: PASS`. Ekstraksi 804 ms, runtime 2.912 ms, exit 0. Alpine 3.22.8, Node 22.23.5, npm 11.6.4. npm memasang satu paket lokal dalam 2 detik. Hasil ini membuktikan kelayakan fixture PRoot dalam satu APK di HP target. Akses antar-workspace, perlindungan data privat, dan egress jalur PRoot di luar cakupan run ini.
