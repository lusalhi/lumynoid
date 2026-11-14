---
type: epic
title: "Agent mencari dan membaca web untuk menjawab"
parent: initiative-lumynoid
covers: [R5, R10]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: false
risk: medium
---

# Agent mencari dan membaca web untuk menjawab

## Description

Tool cari dan baca halaman yang terdaftar di registry tool epic-agent-shell; isi halaman panjang dipotong lewat titik sambung konteks epic-hemat-kuota; jawaban menyertakan sumber yang bisa dibuka. Bagian R10 di sini: instruksi di halaman web diperlakukan sebagai data.

## Outcome

Pengguna mendapat jawaban dari web terkini beserta sumbernya, tanpa agent mengendalikan browser.

## Done when

- Agent menjawab pertanyaan dari hasil pencarian dan isi halaman, dan pengguna bisa membuka setiap sumbernya.
- Halaman uji berisi prompt injection tidak memicu aksi tanpa kartu konfirmasi.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: mengendalikan Chrome (di luar v1).
- Touch point milik epic ini: layanan pencarian web.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R5, R10; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-browsing-web`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-6, AD-7, AD-8, AD-9, AD-13, AD-15; Deferred (backend pencarian default)

## Notes

- Decision: 2026-11-14 — Layanan pencarian dipilih saat architecture; utamakan yang tanpa key tambahan agar R1 tetap tanpa langkah teknis; jika butuh key, jadikan pengaturan opsional.
