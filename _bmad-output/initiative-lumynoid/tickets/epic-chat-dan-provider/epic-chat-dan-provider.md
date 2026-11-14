---
type: epic
title: "Orang awam chat dengan AI memakai key sendiri"
parent: initiative-lumynoid
covers: [R1, R2, R3]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: false
risk: medium
---

# Orang awam chat dengan AI memakai key sendiri

## Description

Epic pembuka. Fondasi platform (repo publik berlisensi, kerangka Kotlin + Compose, CI, APK bertanda tangan di GitHub Releases) dan pengalaman pertama pengguna: menambah provider dengan API key sendiri, mengetes koneksinya, lalu chat dengan jawaban streaming. Setiap pesan chat sudah berjalan lewat kerangka TaskRunner yang di-host AgentService (FGS) dan tercatat di event log, tanpa tool, dengan router pass-through satu provider (AD-2, AD-4, AD-12). Bagian R1 di sini: dari pemasangan sampai jawaban chat pertama hanya lewat layar app; epic 7 memeriksa R1 untuk seluruh app.

## Outcome

Orang awam memasang APK dan langsung chat dengan AI memakai key miliknya, tanpa langkah teknis.

## Done when

- Repo GitHub publik berlisensi; CI membangun APK bertanda tangan dan menerbitkannya ke GitHub Releases.
- Sebelum APK bertanda tangan pertama terbit (termasuk pre-release `v0.x`), `dev.lusalhi.lumynoid` dan fingerprint kunci rilis berstatus Registered di Play Console milik pemilik proyek; kunci yang dipulihkan dari cadangan offline punya fingerprint yang sama dengan yang tercatat di README (AD-25).
- Di HP sungguhan, penguji menambah provider dari preset OpenAI-compatible, URL custom, dan Anthropic; tes koneksi menunjukkan berhasil atau gagal beserta alasannya.
- Chat menampilkan jawaban streaming dengan markdown dan blok kode; percakapan muncul di daftar dan bisa dilanjutkan setelah app ditutup.
- Dari pemasangan sampai jawaban pertama, penguji tidak melihat terminal dan tidak keluar dari app.
- Pesan chat masuk lewat `TaskRunner.submit` di AgentService: jawaban yang sedang streaming tetap selesai dan tersimpan di percakapan saat app dipindah ke background, dan notifikasi FGS hilang setelah tugas selesai.

## Boundaries

- Bukan di sini: tool, registry tool, gerbang konfirmasi, dan kartu (epic-agent-shell); rotasi key, filter model, dan status kuota (epic-hemat-kuota).
- Touch point milik epic ini: API provider LLM (OpenAI-compatible, Anthropic, OpenRouter); GitHub Releases; verifikasi developer Android.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R1, R2, R3; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-chat-dan-provider`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-1, AD-2, AD-4, AD-5, AD-9 (render markdown), AD-11, AD-12, AD-15, AD-17, AD-19, AD-23, AD-24, AD-25; Deferred (lisensi, preset provider)

## Notes

- Decision: 2026-11-14 — Fondasi (repo, kerangka Compose, CI, APK bertanda tangan) adalah story pertama epic ini.
- Decision: 2026-11-14 — Pemilihan lisensi open source adalah story hitl pertama, sebelum repo diterbitkan.
- Decision: 2026-11-14 — Application id `dev.lusalhi.lumynoid`; pendaftaran verifikasi developer (nama package + kunci tanda tangan) adalah langkah hitl di story fondasi, sebelum APK pertama terbit.
- Decision: 2026-11-14 — Replan (AD-2): epic ini ikut membangun kerangka TaskRunner + AgentService tanpa tool; chat polos sudah lewat jalur itu. Alasan: mencegah jalur chat terpisah yang lalu dibongkar epic-agent-shell.
