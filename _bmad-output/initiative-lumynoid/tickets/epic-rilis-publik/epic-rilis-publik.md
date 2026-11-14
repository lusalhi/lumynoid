---
type: epic
title: "Lumynoid v1 terbit untuk umum"
parent: initiative-lumynoid
covers: [R1]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: false
risk: medium
---

# Lumynoid v1 terbit untuk umum

## Description

Penutup v1: sesi penguji awam di seluruh app, dan rilis v1 sebagai APK di GitHub Releases. Bagian R1 di sini: penguji awam memastikan seluruh app, bukan hanya chat pertama, bisa dipakai tanpa terminal dan tanpa dokumentasi.

## Outcome

Orang awam bisa menemukan, memasang, dan memakai Lumynoid v1 dari GitHub Releases.

## Done when

- Penguji awam yang belum pernah melihat Lumynoid menyelesaikan satu chat, satu tugas coding lewat shell, satu jawaban dari web, dan satu tugas terjadwal tanpa terminal dan tanpa dokumentasi.
- Rilis v1 tersedia sebagai APK bertanda tangan di GitHub Releases.

## Boundaries

- Bukan di sini: fitur baru; temuan sesi penguji yang butuh fitur menjadi tiket di epic pemiliknya.
- Bukan di v1: Google Play dan F-Droid (C14); verifikasi developer sudah di epic-chat-dan-provider.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R1; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-rilis-publik`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-17, AD-24, AD-25; Deferred (PSE Komdigi, retensi riwayat)

## Notes

- Decision: 2026-11-14 — Aturan inklusi F-Droid (tanpa library non-free) dicek saat architecture memilih dependensi, bukan di epic ini.
- Decision: 2026-11-14 — Pengajuan F-Droid dan keputusan Play keluar dari epic ini: v1 hanya di GitHub Releases (C14); verifikasi developer pindah ke epic-chat-dan-provider.
