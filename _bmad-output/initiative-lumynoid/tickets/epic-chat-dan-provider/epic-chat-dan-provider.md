---
type: epic
title: "Orang awam chat dengan AI memakai key sendiri"
parent: initiative-lumynoid
covers: [R1, R2, R3]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: true
risk: medium
---

# Orang awam chat dengan AI memakai key sendiri

## Description

Epic pembuka. Fondasi platform (repo publik berlisensi, kerangka Kotlin + Compose, CI, APK bertanda tangan di GitHub Releases) dan pengalaman pertama pengguna: menambah provider dengan API key sendiri, mengetes koneksinya, lalu chat dengan jawaban streaming. Setiap pesan chat sudah berjalan lewat kerangka TaskRunner yang di-host AgentService (FGS) dan tercatat di event log, tanpa tool, dengan router pass-through satu provider (AD-2, AD-4, AD-12). Bagian R1 di sini: dari pemasangan sampai jawaban chat pertama hanya lewat layar app; epic 7 memeriksa R1 untuk seluruh app.

## Outcome

Orang awam memasang APK dan langsung chat dengan AI memakai key miliknya, tanpa langkah teknis.

## Requirements

- R1 (parent R1): Orang awam memasang app, mengisi endpoint/API key AI miliknya, lalu mendapat jawaban chat pertama hanya lewat layar app, tanpa terminal, akun Lumynoid, VPS, atau langkah teknis lain.
- R2 (parent R2): Pengguna menambah provider OpenAI-compatible (preset populer atau URL custom) dan Anthropic dengan API key sendiri, mengetes koneksinya dari app, dan memakai provider mana pun yang lolos tes di chat.
- R3 (parent R3): Chat di dalam app menampilkan jawaban streaming dengan markdown dan blok kode; percakapan tersimpan di daftar dan bisa dilanjutkan.

## Constraints

- C1 (parent C1): Open source dan gratis; tanpa server Lumynoid, inferensi lewat endpoint milik pengguna.
- C2 (parent C2): Tidak ada terminal yang terlihat di UI.
- C5 (parent C5): FGS hanya selama ada tugas; tanpa proses always-on.
- C10 (parent C10): Hanya API key milik pengguna; login memakai langganan Claude dilarang.
- C12 (parent C12): Kotlin + Jetpack Compose, Android saja.
- C13 (parent C13): App baru; kode Operit tidak disalin.
- C14 (parent C14): Distribusi hanya APK unduhan manual dari GitHub Releases.

## Done when

- Repo GitHub publik berlisensi; CI membangun APK bertanda tangan dan menerbitkannya ke GitHub Releases.
- Sebelum APK bertanda tangan pertama terbit (termasuk pre-release `v0.x`), `dev.lusalhi.lumynoid` dan fingerprint kunci rilis berstatus Registered di Play Console milik pemilik proyek; kunci yang dipulihkan dari cadangan offline punya fingerprint yang sama dengan yang tercatat di README (AD-25).
- Di HP sungguhan, penguji menambah provider dari preset OpenAI-compatible, URL custom, dan Anthropic; tes koneksi menunjukkan berhasil atau gagal beserta alasannya.
- Chat menampilkan jawaban streaming dengan markdown dan blok kode; percakapan muncul di daftar dan bisa dilanjutkan setelah app ditutup.
- Dari pemasangan sampai jawaban pertama, penguji tidak melihat terminal dan tidak keluar dari app.
- Pesan chat masuk lewat `TaskRunner.submit` di AgentService: jawaban yang sedang streaming tetap selesai dan tersimpan di percakapan saat app dipindah ke background, dan notifikasi FGS hilang setelah tugas selesai. Penyimpangan yang diterima pemilik (Decision 2026-11-16): di Android 13+ tanpa izin notifikasi, notifikasi tidak terlihat, jadi poin notifikasi dibuktikan lewat jawaban yang selesai dan tersimpan di background; notifikasi yang terlihat menjadi milik epic-tugas-terjadwal.

## Boundaries

- Bukan di sini: tool, registry tool, gerbang konfirmasi, dan kartu (epic-agent-shell); rotasi key, filter model, dan status kuota (epic-hemat-kuota).
- Touch point milik epic ini: API provider LLM (OpenAI-compatible, Anthropic, OpenRouter); GitHub Releases; verifikasi developer Android.

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R1, R2, R3; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-chat-dan-provider`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-1, AD-2, AD-3, AD-4, AD-5, AD-9 (render markdown), AD-11, AD-12, AD-15, AD-17, AD-19, AD-22, AD-23, AD-24, AD-25; Consistency Conventions; Deferred (lisensi, preset provider)

## Notes

- Decision: 2026-11-14 — Fondasi (repo, kerangka Compose, CI, APK bertanda tangan) adalah story pertama epic ini.
- Decision: 2026-11-14 — Pemilihan lisensi open source adalah story hitl pertama, sebelum repo diterbitkan.
- Decision: 2026-11-14 — Application id `dev.lusalhi.lumynoid`; pendaftaran verifikasi developer (nama package + kunci tanda tangan) adalah langkah hitl di story fondasi, sebelum APK pertama terbit.
- Decision: 2026-11-14 — Replan (AD-2): epic ini ikut membangun kerangka TaskRunner + AgentService tanpa tool; chat polos sudah lewat jalur itu. Alasan: mencegah jalur chat terpisah yang lalu dibongkar epic-agent-shell.
- Decision: 2026-11-14 — Preset provider: OpenRouter, Groq, Google Gemini (endpoint OpenAI-compatible), dan OpenAI, ditambah URL custom dan Anthropic; keandalan model gratis untuk tool calling tetap milik epic-hemat-kuota.
- Decision: 2026-11-14 — Satu provider dan model aktif untuk seluruh app; model dipilih dari daftar model provider, atau diketik bila provider tidak memberi daftar; perubahan berlaku mulai pesan berikutnya. Pilihan per percakapan di luar v1.
- Decision: 2026-11-14 — Aksi percakapan: baru, lanjutkan, ubah nama, hapus, dan hentikan jawaban yang sedang streaming; judul diambil dari pesan pertama tanpa panggilan LLM tambahan.
- Decision: 2026-11-14 — Epic ini tidak meminta izin notifikasi (milik epic-tugas-terjadwal, architecture Deferred); di Android 13+ tanpa izin itu, notifikasi FGS dicek di daftar app aktif sistem.
- Decision: 2026-11-14 — Layar provider, chat, dan daftar percakapan dibangun tanpa desain `bmad-ux`; builder menentukan tata letaknya sendiri dengan komponen Material 3 bawaan.
- Decision: 2026-11-15 — Akun developer Google penuh milik pemilik proyek sudah ada dan aplikasi produksi sudah live di Play Store (dikonfirmasi pemilik); ini menjawab open question story 3. Yang tetap menjadi langkah hitl di story 3: mendaftarkan `dev.lusalhi.lumynoid` dan fingerprint SHA-256 kunci rilis di Play Console sebelum APK bertanda tangan pertama terbit.
- Decision: 2026-11-15 — Story 2 selesai: LICENSE Apache-2.0, README, dan gerbang CI (AD-17, AD-19, `@SerialName`) terbukti gagal pada PR uji di GitHub; repo `lusalhi/lumynoid` dijadikan publik.
- Source conflict: R3 — architecture AD-13 menaruh versi naif ContextAssembler di epic-agent-shell, padahal percakapan yang dilanjutkan di epic ini butuh riwayatnya di setiap request. Usulan: story 7 membangun versi naif (riwayat percakapan saja), epic-agent-shell menambah giliran tool; AD-13 diperbarui lewat `bmad-architecture`.
- Decision: 2026-11-14 — Source conflict R3/AD-13 diselesaikan: story 7 membangun ContextAssembler versi naif (riwayat percakapan saja), epic-agent-shell menambah giliran tool; AD-13 dan baris lisensi di Deferred menunggu pembaruan lewat `bmad-architecture`.
- Decision: 2026-11-14 — Menggantikan tiga Decision di atas tentang "story pertama" dan "story hitl pertama": story 1 adalah tracer bullet; lisensi dipilih di story 2 sebelum repo dibuat publik; kunci rilis, verifikasi developer, dan APK bertanda tangan pertama ada di story 3.
- Decision: 2026-11-14 — Tracer bullet: story 1, jawaban chat pertama (satu giliran) mengalir lewat semua lapisan (UI → AgentService → TaskRunner → adapter OpenAI-compatible → event log Room) di build debug.
- Decision: 2026-11-14 — Urutan: setelah tracer, lisensi dan repo publik (story 2) lalu rilis bertanda tangan dan verifikasi developer (story 3), karena itu bagian yang paling tidak pasti dan nama package tidak bisa diubah setelah terbit; lane provider (4 → 5 dan 4 → 7, karena keduanya mengubah skema Room) dan lane layar chat (6 → 8) bisa paralel; tabrakan kecil di manifest antara story 3, 8, dan 10 diterima dan diselesaikan saat merge; ditutup refactor sweep dan rilis epic di HP sungguhan.
- Decision: 2026-11-16 — Di HP pemilik (pre-release `v0.1.0`), jawaban tetap selesai dan tersimpan saat layar mati atau app ditutup, jadi FGS dan wake lock terbukti jalan. Notifikasi FGS tidak terlihat di Android 13+ karena izin notifikasi tidak dideklarasikan, dan Lumynoid tidak muncul di daftar app aktif sistem. Pemilik menerima ini: untuk epic ini, poin "notifikasi FGS hilang setelah tugas selesai" dibuktikan lewat jawaban yang selesai di background. Izin notifikasi dan notifikasi `task_running` yang terlihat diserahkan ke epic-tugas-terjadwal.
