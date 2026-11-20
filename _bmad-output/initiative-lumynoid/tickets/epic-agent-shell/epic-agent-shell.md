---
type: epic
title: "Agent mengerjakan tugas lewat shell tersembunyi dengan aman"
parent: initiative-lumynoid
covers: [R4, R8, R9, R10, R11]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: true
risk: high
---

# Agent mengerjakan tugas lewat shell tersembunyi dengan aman

## Description

Harness agent native Kotlin: loop tool call, registry tool, dan gerbang konfirmasi dipasang ke kerangka TaskRunner + AgentService dari epic-chat-dan-provider, sehingga tugas berjalan di FGS terlepas dari layar chat. Tool pertamanya shell di userland proot yang hanya menyala saat dipakai. Registry tool menjadi kontrak untuk epic browsing web dan skill. Kartu aktivitas dan kartu konfirmasi dibuat app dari data tool call, semua tool call tercatat di log aksi, dan hasil berupa file/HTML bisa dipratinjau. Bagian R10 di sini: output tool apa pun tidak bisa melewati kartu konfirmasi; epic-browsing-web menguji konten web.

## Outcome

Pengguna menyuruh agent mengerjakan tugas nyata di HP, termasuk coding, dan selalu melihat serta mengendalikan apa yang dijalankan.

## Requirements

- R1 (parent R4): Agent menjalankan perintah coding di Linux userland tersembunyi: menulis file, memasang paket npm, dan menjalankan skrip. Pengguna melihat perintah dan hasil lewat kartu aktivitas; userland hanya aktif saat dipakai untuk tugas shell.
- R2 (parent R8): Aksi merusak, keuangan, dan pengiriman data keluar tidak berjalan tanpa persetujuan pengguna di kartu buatan app untuk panggilan itu. Teks LLM tidak dapat membuat kartu aktivitas atau kartu konfirmasi.
- R3 (parent R9): Pengguna bisa membuka log aksi yang memuat setiap panggilan tool beserta target dan hasilnya.
- R4 (parent R10): Instruksi dari output tool tidak dapat memicu aksi yang butuh persetujuan tanpa kartu konfirmasi. Bukti untuk konten halaman web menjadi bagian epic Browsing Web.
- R5 (parent R11): Pengguna bisa membuka pratinjau file dan halaman HTML hasil kerja agent di dalam app.

## Constraints

- C1 (parent C2, C4, C5, C6): Tidak ada terminal di UI atau proses always-on. Satu tugas berjalan lewat TaskRunner dan AgentService yang sudah ada. Menunggu persetujuan tidak memegang slot, userland, atau foreground service bila tidak ada kerja lain (AD-2, AD-3, AD-22).
- C2 (parent C7, C8; AD-14, AD-15, AD-20): Target tetap SDK 37. Workspace dan rootfs bukan akses ke data privat app, secret, atau workspace lain. Rute egress dan batas akses dibuktikan di perangkat sebelum shell diaktifkan; persetujuan egress bukan pengganti isolasi filesystem.
- C3 (parent C9, C11; AD-7, AD-8, AD-10, AD-26): Hanya panggilan tool terstruktur melewati gerbang. Event bertipe menjadi sumber kartu dan log. Runner menegakkan batas panggilan LLM dan satu percobaan perbaikan tool call; rotasi kuota tetap di epic Hemat Kuota.

## Done when

- Di HP sungguhan, agent menyelesaikan tugas coding (menulis file, `npm install`, menjalankan skrip); pengguna hanya melihat kartu aktivitas, tanpa terminal.
- Userland tidak berjalan saat tidak ada tool shell aktif; tugas panjang tetap selesai saat layar mati karena berjalan di FGS.
- Aksi merusak, keuangan, dan pengiriman data keluar berhenti di kartu konfirmasi; uji dengan output tool berisi instruksi palsu tidak bisa melewatinya, dan teks LLM tidak bisa tampil sebagai kartu.
- Log aksi menampilkan setiap tool call beserta hasilnya; file dan HTML hasil kerja bisa dipratinjau di app.
- Dengan `LlmPort` palsu yang mengirim tool call kosong lalu args rusak, runner mengirim satu pesan perbaikan, lalu tugas berakhir `MALFORMED_TOOL_CALL` yang terlihat pengguna dan tercatat di log aksi; tugas yang melewati `MAX_LLM_CALLS` berakhir `BUDGET_EXHAUSTED` (AD-26).
- Dirilis di GitHub Releases dan diuji terintegrasi dengan chat epic-chat-dan-provider.

## Boundaries

- Bukan di sini: kerangka TaskRunner, AgentService, dan event log (epic-chat-dan-provider); tool cari dan baca web (epic-browsing-web); skill pihak ketiga (epic-skill-pihak-ketiga); alarm dan penjadwalan (epic-tugas-terjadwal); rotasi, filter model, dan pemangkasan konteks (epic-hemat-kuota).
- Touch point milik epic ini: npm registry; sumber unduhan rootfs userland.
- Uji coba shell hanya memakai fixture milik proyek. Rilis shell menunggu bukti batas workspace, kredensial, jaringan, dan pembersihan proses anak; bila tidak terbukti, revisi desain sebelum membuka shell, bukan melonggarkan gerbang.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R4, R8, R9, R10, R11; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-agent-shell`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-2, AD-3, AD-4, AD-6, AD-7, AD-8, AD-10, AD-13 (assembler naif), AD-14, AD-18, AD-20, AD-22, AD-26; Deferred (spike userland)

## Notes

- Decision: 2026-11-14 — Epic dibuka dengan spike userland: W^X untuk targetSdk 29+, ukuran dan strategi unduh, `npm install` di HP sungguhan di bawah phantom process killer.
- Decision: 2026-11-14 — Harness, kartu, dan shell satu epic karena kartu baru terbukti dengan tool sungguhan; jika story lebih dari 12, tawarkan pemisahan harness + kartu dari userland.
- Decision: 2026-11-14 — Replan (AD-2): kerangka TaskRunner + AgentService dibangun epic-chat-dan-provider; epic ini menambah loop tool call, registry, dan gerbang ke kerangka yang sama, tanpa membuat jalur eksekusi kedua.
- Decision: 2026-11-14 — Penanganan tool call kosong/rusak dan budget panggilan LLM per tugas (AD-26) pindah ke epic ini dari epic-hemat-kuota, karena keduanya ditegakkan runner.
- Decision: 2026-11-20 — Spike 2.1 mendahului tracer produk karena kelayakan dan isolasi shell harus dibuktikan dahulu. Spike menelusuri APK diagnostik ke satu perintah baca aman; tracer produk 2.8 menelusuri chat → tool call provider → runner dan gerbang → userland → event → kartu dan jawaban.
- Decision: 2026-11-20 — 2.2 dapat dikerjakan saat uji perangkat 2.1 menunggu karena loop dan registry diuji dengan tool in-process; tidak ada eksekusi kode model di fixture tersebut. 2.5 dan 2.7 menunggu hasil spike.
- Open question: Distro, ukuran unduhan, commit proot/loader, dan batas filesystem, jaringan, serta proses mana yang lolos pada HP arm64 API 37 dengan APK target SDK 37? Jawab di spike 2.1 dengan hasil ukur dan keputusan lanjut atau ubah desain (AD-14, AD-17, AD-20).
