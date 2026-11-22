---
type: epic
title: "Pengguna melanjutkan proyek dari beberapa percakapan"
parent: initiative-lumynoid
covers: [R13]
sources: []
companions: []
after: []
refined: false
risk: high
---

# Pengguna melanjutkan proyek dari beberapa percakapan

## Description

Projects membawa ruang kerja dan instruksi proyek ke beberapa percakapan tanpa mencampur riwayat atau log. Epic ini memakai APK bertanda tangan Story 2.12 sebagai baseline upgrade dan menetapkan identitas proyek serta batas penghapusan yang akan dipakai Tugas Terjadwal. Hemat Kuota memakai sumber instruksi proyek dan batas riwayat yang sama.

## Outcome

Pengguna bisa memulai chat baru untuk melanjutkan proyek yang sama, serta membersihkan chat atau proyek tanpa kehilangan data lain.

## Done when

- Di proyek bernama, chat pertama membuat file dan chat kedua membaca serta mengubah file itu. Paket lokal dan instruksi proyek tersedia di kedua chat. Riwayat dan log tetap terpisah. Chat biasa tetap berfungsi; chat biasa dan proyek lain tidak bisa mengakses file proyek itu.
- Setelah konfirmasi, hapus chat pertama: riwayat dan log chat itu hilang, tetapi chat kedua masih dapat memakai file dan paket bersama. Hapus proyek: file, paket, instruksi, dan semua chat serta log proyek itu hilang tanpa memengaruhi proyek lain, provider, atau runtime Linux bersama. Penghapusan ditahan selama ada tugas terkait yang belum selesai.
- Setelah memasang APK Projects di atas APK Story 2.12 di HP, chat lama dengan data workspace atau paket dapat dibuka dalam proyek tersendiri dengan file, paket, log, dan pratinjau lama tetap tersedia, tanpa menyalin file atau meminta pengguna memindahkannya. Chat lama tanpa data workspace tetap menjadi chat biasa.
- APK bertanda tangan tersedia di GitHub Releases dan alur dua chat serta upgrade diuji pada perangkat sungguhan.

## Boundaries

- Tidak mengubah Story 2.12 atau tiket Epic 2 yang sudah dikerjakan. Tidak mencakup pemindahan chat antarproyek atau pemakaian otomatis riwayat chat lain sebagai konteks.
- Projects bisa dirilis sebelum penjadwalan. Epic Tugas Terjadwal memiliki integrasi jadwal dan pembatalannya saat proyek dihapus; Epic Hemat Kuota memiliki pemangkasan konteks; Epic Rilis Publik menguji integrasi v1, bukan mengimplementasikan Projects.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, R13, Done when, dan Draf desain — Projects
- baseline — Story 2.12, APK Epic 2 bertanda tangan dan bukti upgrade di HP

## Notes

- Decision: 2026-11-22 — Epic Projects berdiri sendiri setelah rilis Epic 2; Tugas Terjadwal dan Hemat Kuota memakai kontrak yang dihasilkannya. Tracer yang akan dibahas saat menyusun stories: chat A membuat file, chat B di proyek yang sama membacanya tanpa membawa riwayat chat A, dan proyek lain tidak dapat mengakses file itu.
- Open question: Kontrak kepemilikan workspace, konteks, dan penghapusan lintas epic belum diselaraskan di architecture spine; selesaikan saat refinement sebelum menyusun stories.
