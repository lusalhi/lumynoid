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

Dukungan format Agent Skills (`SKILL.md`): pengguna memasang skill, instruksinya dimuat lewat titik sambung konteks epic-hemat-kuota, dan CLI-nya berjalan di userland epic-agent-shell. Acuan: Binance Agentic Wallet (`baw`), dengan transaksi yang berhenti di preview sampai pengguna mengonfirmasi.

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

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R7; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-skill-pihak-ketiga`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-14, AD-15, AD-20, AD-21; Deferred (SkillPolicy baw)
