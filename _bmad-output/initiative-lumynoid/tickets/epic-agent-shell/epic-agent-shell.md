---
type: epic
title: "Agent mengerjakan tugas lewat shell tersembunyi dengan aman"
parent: initiative-lumynoid
covers: [R4, R8, R9, R10, R11]
sources: []
companions: []
after: []
refined: false
risk: high
---

# Agent mengerjakan tugas lewat shell tersembunyi dengan aman

## Description

Harness agent native Kotlin dengan loop tool call dan satu titik masuk task runner yang hidup di FGS selama tugas berjalan, terlepas dari layar chat. Tool pertamanya shell di userland proot yang hanya menyala saat dipakai. Registry tool menjadi kontrak untuk epic browsing web dan skill. Kartu aktivitas dan kartu konfirmasi dibuat app dari data tool call, semua tool call tercatat di log aksi, dan hasil berupa file/HTML bisa dipratinjau. Bagian R10 di sini: output tool apa pun tidak bisa melewati kartu konfirmasi; epic-browsing-web menguji konten web.

## Outcome

Pengguna menyuruh agent mengerjakan tugas nyata di HP, termasuk coding, dan selalu melihat serta mengendalikan apa yang dijalankan.

## Done when

- Di HP sungguhan, agent menyelesaikan tugas coding (menulis file, `npm install`, menjalankan skrip); pengguna hanya melihat kartu aktivitas, tanpa terminal.
- Userland tidak berjalan saat tidak ada tool shell aktif; tugas panjang tetap selesai saat layar mati karena berjalan di FGS.
- Aksi merusak, keuangan, dan pengiriman data keluar berhenti di kartu konfirmasi; uji dengan output tool berisi instruksi palsu tidak bisa melewatinya, dan teks LLM tidak bisa tampil sebagai kartu.
- Log aksi menampilkan setiap tool call beserta hasilnya; file dan HTML hasil kerja bisa dipratinjau di app.
- Dirilis di GitHub Releases dan diuji terintegrasi dengan chat epic-chat-dan-provider.

## Boundaries

- Bukan di sini: tool cari dan baca web (epic-browsing-web); skill pihak ketiga (epic-skill-pihak-ketiga); alarm dan penjadwalan (epic-tugas-terjadwal); rotasi, filter model, dan pemangkasan konteks (epic-hemat-kuota).
- Touch point milik epic ini: npm registry; sumber unduhan rootfs userland.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R4, R8, R9, R10, R11; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-agent-shell`

## Notes

- Decision: 2026-11-14 — Epic dibuka dengan spike userland: W^X untuk targetSdk 29+, ukuran dan strategi unduh, `npm install` di HP sungguhan di bawah phantom process killer.
- Decision: 2026-11-14 — Harness, kartu, dan shell satu epic karena kartu baru terbukti dengan tool sungguhan; jika story lebih dari 12, tawarkan pemisahan harness + kartu dari userland.
