---
type: epic
title: "Orang awam chat dengan AI memakai key sendiri"
parent: initiative-lumynoid
covers: [R1, R2, R3]
sources: []
companions: []
after: []
refined: false
risk: medium
---

# Orang awam chat dengan AI memakai key sendiri

## Description

Epic pembuka. Fondasi platform (repo publik berlisensi, kerangka Kotlin + Compose, CI, APK bertanda tangan di GitHub Releases) dan pengalaman pertama pengguna: menambah provider dengan API key sendiri, mengetes koneksinya, lalu chat dengan jawaban streaming. Bagian R1 di sini: dari pemasangan sampai jawaban chat pertama hanya lewat layar app; epic 7 memeriksa R1 untuk seluruh app.

## Outcome

Orang awam memasang APK dan langsung chat dengan AI memakai key miliknya, tanpa langkah teknis.

## Done when

- Repo GitHub publik berlisensi; CI membangun APK bertanda tangan dan menerbitkannya ke GitHub Releases.
- Di HP sungguhan, penguji menambah provider dari preset OpenAI-compatible, URL custom, dan Anthropic; tes koneksi menunjukkan berhasil atau gagal beserta alasannya.
- Chat menampilkan jawaban streaming dengan markdown dan blok kode; percakapan muncul di daftar dan bisa dilanjutkan setelah app ditutup.
- Dari pemasangan sampai jawaban pertama, penguji tidak melihat terminal dan tidak keluar dari app.

## Boundaries

- Bukan di sini: tool call dan agent (epic-agent-shell); rotasi key, filter model, dan status kuota (epic-hemat-kuota).
- Touch point milik epic ini: API provider LLM (OpenAI-compatible, Anthropic, OpenRouter); GitHub Releases.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R1, R2, R3; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-chat-dan-provider`

## Notes

- Decision: 2026-11-14 — Fondasi (repo, kerangka Compose, CI, APK bertanda tangan) adalah story pertama epic ini.
- Decision: 2026-11-14 — Pemilihan lisensi open source adalah story hitl pertama, sebelum repo diterbitkan.
