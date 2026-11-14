---
type: epic
title: "Tugas tetap selesai dengan kuota API gratis"
parent: initiative-lumynoid
covers: [R12]
sources: []
companions: []
after: []
refined: false
risk: medium
---

# Tugas tetap selesai dengan kuota API gratis

## Description

Rotasi key/provider saat kena limit, filter model yang mendukung tools, penanganan tool call kosong atau rusak dari model gratis, dan penghitung kuota. Epic ini juga memiliki penyusunan konteks: pemangkasan konteks dengan titik sambung yang stabil untuk memotong output tool panjang (dipakai epic-browsing-web) dan memuat instruksi skill tanpa terpangkas (dipakai epic-skill-pihak-ketiga).

## Outcome

Pengguna dengan key gratis tetap bisa menyelesaikan beberapa tugas sehari dan tahu sisa kuotanya.

## Done when

- Dengan dua key/provider gratis terkonfigurasi, tugas tetap selesai saat key pertama kena limit.
- Tugas agent tidak memakai model yang tidak mendukung tools.
- Tool call kosong atau rusak dari model gratis tidak menghentikan tugas diam-diam; pengguna melihat apa yang terjadi.
- Sisa kuota dan status provider terlihat di app.
- Titik sambung penyusunan konteks untuk output tool panjang dan instruksi skill tersedia dan didokumentasikan untuk epic 5 dan 6.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: menambah dan mengetes provider (epic-chat-dan-provider); model lokal untuk routing (di luar v1).
- Touch point milik epic ini: endpoint sisa kuota provider (mis. OpenRouter).

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R12; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-hemat-kuota`
