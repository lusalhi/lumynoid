---
type: epic
title: "Pengguna melanjutkan proyek dari beberapa percakapan"
parent: initiative-lumynoid
covers: [R13]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: true
risk: high
---

# Pengguna melanjutkan proyek dari beberapa percakapan

## Description

Projects membawa ruang kerja dan instruksi proyek ke beberapa percakapan tanpa mencampur riwayat atau log. Epic ini memakai APK bertanda tangan Story 2.12 sebagai baseline upgrade dan menetapkan identitas proyek serta batas penghapusan yang akan dipakai Tugas Terjadwal. Hemat Kuota memakai sumber instruksi proyek dan batas riwayat yang sama.

## Outcome

Pengguna bisa memulai chat baru untuk melanjutkan proyek yang sama, serta membersihkan chat atau proyek tanpa kehilangan data lain.

## Requirements

- R1 (parent R13): Pengguna membuat proyek bernama, mengatur instruksinya, serta membuat dan melanjutkan percakapan dari daftar proyek. Pengguna tetap bisa membuat chat biasa tanpa memilih proyek.
- R2 (parent R13): Chat dalam proyek yang sama memakai file, paket lokal, dan instruksi yang sama. Riwayat dan log aksi tetap dimiliki percakapan. Chat biasa dan proyek lain tidak dapat membaca data proyek itu.
- R3 (parent R13): Setelah konfirmasi, pengguna bisa menghapus satu chat tanpa menghapus file dan paket proyek, atau menghapus seluruh proyek beserta chat, log, instruksi, file, dan paketnya. Proyek lain, konfigurasi provider, dan runtime Linux bersama tetap tersedia. Aplikasi tidak menghapus saat ada tugas terkait yang belum selesai dan dapat melanjutkan penghapusan proyek yang terputus.
- R4 (parent R13): Saat update dari APK Epic 2, chat lama yang memiliki workspace atau instalasi paket menjadi anggota proyek tersendiri; pengguna tetap bisa membuka chat, log, file, dan pratinjau lama serta memakai paket lokalnya tanpa memindahkan data. Chat lama tanpa data workspace tetap menjadi chat biasa.

## Constraints

- C1 (parent C8, C9; AD-7, AD-8, AD-18, AD-27): Keanggotaan proyek tidak memperluas kebijakan tool, konfirmasi aksi, atau akses preview. File hanya masuk konteks ketika dibaca lewat tool dan tidak dapat membuat kartu persetujuan.
- C2 (parent C14; AD-19, AD-25, AD-27): Upgrade melalui APK bertanda tangan tanpa reset atau penyalinan workspace. Gunakan migrasi eksplisit dan pertahankan rujukan artefak lama.

## Done when

- Di proyek bernama, chat pertama membuat file dan chat kedua membaca serta mengubah file itu. Paket lokal dan instruksi proyek tersedia di kedua chat. Riwayat dan log tetap terpisah. Chat biasa tetap berfungsi; chat biasa dan proyek lain tidak bisa mengakses file proyek itu.
- Saat tugas menunggu persetujuan, ubah instruksi proyek dari UI. Tugas yang dilanjutkan tetap memakai instruksi awal; tugas berikutnya memakai instruksi terbaru. Tidak ada riwayat dari chat lain pada kedua request.
- Setelah konfirmasi, hapus chat pertama: riwayat dan log chat itu hilang, tetapi chat kedua masih dapat memakai file dan paket bersama. Hapus proyek: file, paket, instruksi, dan semua chat serta log proyek itu hilang tanpa memengaruhi proyek lain, provider, atau runtime Linux bersama. Penghapusan ditahan selama ada tugas terkait yang belum selesai.
- Jika penghapusan proyek terputus setelah dimulai, buka ulang aplikasi. Proyek itu tidak bisa menerima tugas atau preview baru; ulangi penghapusan sampai selesai dan verifikasi proyek lain tetap bisa dipakai.
- Setelah memasang APK Projects di atas APK Story 2.12 di HP, chat lama dengan workspace berisi file atau dengan instalasi paket saja dapat dibuka dalam proyek tersendiri; file, paket, dan log yang ada tetap tersedia. Pratinjau lama terbuka dari event asal; rujukan dari proyek lain atau proyek yang sedang dihapus ditolak. File tidak disalin; chat tanpa data workspace atau paket tetap biasa.
- APK bertanda tangan tersedia di GitHub Releases dan alur dua chat serta upgrade diuji pada perangkat sungguhan.

## Boundaries

- Tidak mengubah Story 2.12 atau tiket Epic 2 yang sudah dikerjakan. Tidak mencakup pemindahan chat antarproyek atau pemakaian otomatis riwayat chat lain sebagai konteks.
- Projects bisa dirilis sebelum penjadwalan. Epic Tugas Terjadwal memiliki integrasi jadwal dan pembatalannya saat proyek dihapus; Epic Hemat Kuota memiliki pemangkasan konteks; Epic Rilis Publik menguji integrasi v1, bukan mengimplementasikan Projects.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, R13, Done when, dan Draf desain — Projects
- baseline — Story 2.12, APK Epic 2 bertanda tangan dan bukti upgrade di HP
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-4, AD-5, AD-13, AD-16, AD-18, AD-19, AD-25, AD-27

## Notes

- Decision: 2026-11-22 — Epic Projects berdiri sendiri setelah rilis Epic 2; Tugas Terjadwal dan Hemat Kuota memakai kontrak yang dihasilkannya. Tracer yang akan dibahas saat menyusun stories: chat A membuat file, chat B di proyek yang sama membacanya tanpa membawa riwayat chat A, dan proyek lain tidak dapat mengakses file itu.
- Decision: 2026-11-22 — AD-27 menetapkan kepemilikan, isolasi konteks, penghapusan yang bisa dilanjutkan, dan upgrade. Epic 8 tidak menunggu penjadwalan atau pemangkasan; kedua epic itu mengadopsi kontrak saat gilirannya.
- Decision: 2026-11-22 — Story yang menguji upgrade bergantung pada Story 2.12 sebagai baseline rilis, bukan pada penuntasan seluruh spike isolasi Epic 2.
