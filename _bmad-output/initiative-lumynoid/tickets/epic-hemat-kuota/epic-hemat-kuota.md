---
type: epic
title: "Tugas tetap selesai dengan kuota API gratis"
parent: initiative-lumynoid
covers: [R12]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: false
risk: medium
---

# Tugas tetap selesai dengan kuota API gratis

## Description

Rotasi key/provider saat kena limit, filter model yang mendukung tools, dan penghitung kuota. Epic ini juga memiliki penyusunan konteks: pemangkasan konteks dengan titik sambung yang stabil untuk memotong output tool panjang (dipakai epic-browsing-web) dan memuat instruksi skill tanpa terpangkas (dipakai epic-skill-pihak-ketiga).

## Outcome

Pengguna dengan key gratis tetap bisa menyelesaikan beberapa tugas sehari dan tahu sisa kuotanya.

## Done when

- Dengan dua key/provider gratis terkonfigurasi, tugas tetap selesai saat key pertama kena limit.
- Tugas agent tidak memakai model yang tidak mendukung tools.
- Sisa kuota dan status provider terlihat di app.
- Titik sambung penyusunan konteks untuk output tool panjang dan instruksi skill tersedia dan didokumentasikan untuk epic 5 dan 6.
- Saat konteks dipangkas, instruksi proyek tetap tersedia dan riwayat chat lain tidak masuk request.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: menambah dan mengetes provider (epic-chat-dan-provider); penanganan tool call kosong/rusak dan budget panggilan per tugas (epic-agent-shell, AD-26); model lokal untuk routing (di luar v1).
- Touch point milik epic ini: endpoint sisa kuota provider (mis. OpenRouter).

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R12; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-hemat-kuota`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-11, AD-12, AD-13, AD-23, AD-26; Deferred (rotasi, cooldown, sumber kuota)

## Notes

- Decision: 2026-11-14 — Penanganan tool call kosong/rusak pindah ke epic-agent-shell (AD-26: ditegakkan runner); epic ini tetap memegang retry karena limit dan kuota lewat router.
- Decision: 2026-11-22 — Pemangkasan menggunakan sumber instruksi proyek dan batas riwayat per percakapan yang ditetapkan Epic 8, tanpa mendefinisikan ulang kepemilikan konteks.
