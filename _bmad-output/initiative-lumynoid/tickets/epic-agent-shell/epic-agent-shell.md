---
type: epic
title: "Agent mengerjakan tugas coding di HP"
parent: initiative-lumynoid
covers: [R4, R8, R9, R10, R11]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: true
risk: high
---

# Agent mengerjakan tugas coding di HP

## Description

Harness agent native Kotlin memasang loop tool call, registry tool, dan gerbang konfirmasi ke TaskRunner + AgentService dari epic-chat-dan-provider. Tool coding menjalankan operasi terstruktur pada folder proyek biasa yang dikelola aplikasi. PRoot menyala saat dibutuhkan untuk instalasi paket lokal atau skrip bawaan. Registry tool juga dipakai oleh epic browsing web dan skill. Kartu berasal dari data tool call, hasil tercatat di log aksi, dan file/HTML bisa dipratinjau. Output tool tidak bisa melewati kartu konfirmasi.

## Outcome

Pengguna menyuruh agent mengerjakan tugas nyata di HP, termasuk coding, dan selalu melihat serta mengendalikan apa yang dijalankan.

## Requirements

- R1 (parent R4): Agent menulis file proyek, memasang paket npm lokal yang disediakan aplikasi, dan menjalankan skrip bawaan aplikasi melalui alat coding terstruktur di Linux userland. Pengguna melihat setiap aksi dan hasilnya di kartu aktivitas.
- R2 (parent R8): Aksi merusak, keuangan, dan pengiriman data keluar tidak berjalan tanpa persetujuan pengguna di kartu buatan app untuk panggilan itu. Teks LLM tidak dapat membuat kartu aktivitas atau kartu konfirmasi.
- R3 (parent R9): Pengguna bisa membuka log aksi yang memuat setiap panggilan tool beserta target dan hasilnya.
- R4 (parent R10): Instruksi dari output tool tidak dapat memicu aksi yang butuh persetujuan tanpa kartu konfirmasi. Bukti untuk konten halaman web menjadi bagian epic Browsing Web.
- R5 (parent R11): Pengguna bisa membuka pratinjau file dan halaman HTML hasil kerja agent di dalam app.
- R6 (parent R8): Setelah pengguna menolak aksi lewat kartu, agent menawarkan alternatif lewat satu balasan teks di chat tanpa menjalankan aksi itu atau tool lain. Penolakan tetap terlihat di log; Stop tetap berarti berhenti.

## Constraints

- C1 (parent C2, C4, C5, C6): Tidak ada terminal di UI atau proses always-on. Satu tugas berjalan lewat TaskRunner dan AgentService yang sudah ada. Menunggu persetujuan tidak memegang slot, userland, atau foreground service bila tidak ada kerja lain (AD-2, AD-3, AD-22).
- C2 (parent C7, C8; AD-14, AD-15, AD-20): Satu APK target SDK 37 memakai PRoot dengan folder proyek biasa. Aplikasi memvalidasi akses file ke folder aktif dan menentukan setiap operasi runtime. Instalasi npm berjalan offline dengan lifecycle scripts dimatikan. Kode yang dieksekusi berasal dari aplikasi dan dependensinya yang sudah ditinjau serta terverifikasi.
- C3 (parent C9, C11; AD-7, AD-8, AD-10, AD-26): Hanya panggilan tool terstruktur melewati gerbang. Event bertipe menjadi sumber kartu dan log. Runner menegakkan batas panggilan LLM dan satu percobaan perbaikan tool call; rotasi kuota tetap di epic Hemat Kuota.

## Done when

- Di HP API 37, agent menulis file proyek, memasang paket lokal melalui npm, dan menjalankan skrip bawaan. Kartu aktivitas menampilkan hasilnya. Pesan berikutnya bisa membaca file yang sama.
- Uji memasukkan path di luar folder aktif, perintah bebas, dan paket di luar daftar. Ketiganya ditolak. File konfigurasi dan skrip dari proyek tidak mengubah proses yang dijalankan.
- Tugas panjang tetap selesai saat layar mati karena berjalan di FGS. Userland dan seluruh proses anak berhenti saat tugas selesai, gagal, dibatalkan, mencapai timeout, atau menunggu persetujuan.
- Aksi merusak, keuangan, dan pengiriman data keluar berhenti di kartu konfirmasi; uji dengan output tool berisi instruksi palsu tidak bisa melewatinya, dan teks LLM tidak bisa tampil sebagai kartu.
- Ketuk **Decline** saat agent meminta izin menimpa file uji. File tidak berubah, Log menunjukkan **Declined**, dan agent menawarkan alternatif tanpa meminta izin lagi. Ketuk **Stop** pada percobaan terpisah; tugas berhenti tanpa balasan alternatif.
- Log aksi menampilkan setiap tool call beserta hasilnya; file dan HTML hasil kerja bisa dipratinjau di app.
- Dengan `LlmPort` palsu yang mengirim tool call kosong lalu args rusak, runner mengirim satu pesan perbaikan, lalu tugas berakhir `MALFORMED_TOOL_CALL` yang terlihat pengguna dan tercatat di log aksi; tugas yang melewati `MAX_LLM_CALLS` berakhir `BUDGET_EXHAUSTED` (AD-26).
- Dirilis di GitHub Releases dan diuji terintegrasi dengan chat epic-chat-dan-provider.

## Boundaries

- Bukan di sini: kerangka TaskRunner, AgentService, dan event log (epic-chat-dan-provider); tool cari dan baca web (epic-browsing-web); skill pihak ketiga (epic-skill-pihak-ketiga); alarm dan penjadwalan (epic-tugas-terjadwal); rotasi, filter model, dan pemangkasan konteks (epic-hemat-kuota).
- Touch point milik epic ini adalah sumber unduhan rootfs. Aplikasi mengelola unduhan awal dan verifikasi checksum. PRoot menjalankan operasi yang ditentukan aplikasi secara offline.
- Aplikasi memilih paket dan dependensinya berdasarkan identitas dan digest yang dipin. Konfigurasi npm, manifest instalasi, environment, argumen eksekusi, dan jalur pemuatan modul ditentukan aplikasi. File dari agent atau proyek diproses sebagai data.
- Di luar rilis ini ada QEMU, isolasi workspace tingkat OS, shell bebas, eksekusi kode skrip dari proyek, dan instalasi paket npm pilihan agent. Perlu bukti perlindungan data privat, kredensial, workspace lain, dan egress di perangkat sebelum membuka eksekusi kode pihak ketiga.

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
- Decision: 2026-11-21 — Replan setelah pengguna melaporkan Samsung SM-S731B API 37 lulus baca/tulis, npm offline, skrip Node, dan cleanup dengan PRoot pada APK target SDK 37. Rilis awal memakai satu APK dengan folder proyek biasa dan operasi coding yang ditentukan aplikasi. [Bukti Spike 2.1](tickets/spike-buktikan-userland-android-dan-batas-isolasinya-plan.md#probe-npm-melalui-proot)
- Decision: 2026-11-21 — Menggantikan keputusan 2026-11-20 tentang gate isolasi untuk 2.5 dan 2.7. 2.5 memakai hasil kompatibilitas 2.1 dan handoff lifecycle 2.4. Status 2.1 tetap mencatat hambatan shell umum dari scope awal. 2.8 tetap menjadi tracer produk dengan satu workspace stabil per percakapan.
- Decision: 2026-11-21 — Untuk rilis awal, kontrak tool memakai operasi bertipe dengan input yang diperiksa aplikasi. AD-14 tetap menjadi acuan kemasan dan lifecycle. Profil shell bebas dan jaringan di AD-20 berlaku saat cakupan itu dibuka setelah batasnya terbukti. Penyelarasan AD-14/AD-20 dicatat untuk pembaruan arsitektur. Perlindungan kredensial AD-15 tetap berlaku.
- Decision: 2026-11-23 — Dari laporan uji v0.4.1, Decline menutup tugas tanpa memberi tahu model. Pemilik memilih satu balasan teks otomatis tanpa tool sebagai follow-up 2.13 sebelum Epic 3 memakai gerbang yang sama. Penerimaan rilis Projects 8.8 tidak berubah; persetujuan per panggilan dan TTL AD-7 tetap berlaku.
