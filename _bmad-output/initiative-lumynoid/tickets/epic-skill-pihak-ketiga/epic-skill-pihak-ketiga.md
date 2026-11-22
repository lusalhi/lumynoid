---
type: epic
title: "Pengguna memasang skill pihak ketiga"
parent: initiative-lumynoid
covers: [R7]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: false
risk: high
---

# Pengguna memasang skill pihak ketiga

## Description

Pengguna memasang skill berformat Agent Skills (`SKILL.md`). Instruksinya dimuat lewat titik sambung konteks epic-hemat-kuota. Epic ini memakai siklus hidup userland dari epic-agent-shell dan membuktikan perlindungan data serta jaringan sebelum menjalankan CLI skill. Acuannya Binance Agentic Wallet (`baw`), dengan transaksi yang berhenti di preview sampai pengguna mengonfirmasi.

## Outcome

Pengguna menambah kemampuan baru ke asistennya dengan memasang skill pihak ketiga, dan aksi keuangan tetap di bawah kendalinya.

## Done when

- Skill berformat `SKILL.md` dipasang dari app dan dipakai agent.
- `baw` terpasang lewat npm di userland; login lewat kode pairing dan konfirmasi di Binance App berhasil.
- Transaksi berhenti di `contract-call preview`; `execute` hanya jalan setelah kartu konfirmasi disetujui.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: skill yang mengendalikan app lain (di luar v1).
- Touch point milik epic ini: Binance Agentic Wallet (`baw` + `SKILL.md`).
- Sebelum membuka eksekusi CLI pihak ketiga, buktikan batas akses ke data privat, kredensial skill lain, workspace, dan jaringan di HP sesuai AD-15, AD-20, dan AD-21.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R7; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-skill-pihak-ketiga`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-14, AD-15, AD-20, AD-21; Deferred (SkillPolicy baw)

## Notes

- Decision: 2026-11-21 — Epic 2 menyediakan PRoot untuk operasi coding yang ditentukan aplikasi. Pembuktian batas eksekusi CLI pihak ketiga menjadi prasyarat di epic ini sebelum memasang dan menjalankan skill.
