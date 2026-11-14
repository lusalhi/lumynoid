---
type: initiative
title: "Orang awam punya asisten AI pribadi di HP Android-nya sendiri"
sources:
  - _bmad-output/research-lumynoid-android-agent/research-lumynoid-android-agent.md
  - _bmad-output/lumynoid-decisions.md
companions: []
after: []
risk: high
---

# Orang awam punya asisten AI pribadi di HP Android-nya sendiri

## Description

Lumynoid v1 adalah app Android open source dan gratis. Pengguna memasangnya, mengisi endpoint AI miliknya, lalu langsung punya asisten pribadi yang bisa chat, mengerjakan tugas lewat shell tersembunyi (termasuk coding), mencari dan membaca web, menjalankan tugas terjadwal, dan memakai skill pihak ketiga, tanpa langganan bot, tanpa VPS, dan tanpa terminal. Riset menyimpulkan ide ini layak dengan syarat [riset, Ringkasan eksekutif]. Catatan keputusan 2026-11-14 menetapkan bentuk v1 (K1–K12). Di mana keduanya berbeda, catatan keputusan yang berlaku.

## Outcome

Orang awam bisa menyuruh asisten AI di HP-nya sendiri, memakai kuota API miliknya, untuk mengerjakan tugas nyata yang sebelumnya butuh langganan bot atau VPS.

## Requirements

- R1 (Visi, K1, K7): Orang awam memasang app, mengisi endpoint/API key AI miliknya, lalu mendapat jawaban chat pertama hanya lewat layar app, tanpa terminal, akun Lumynoid, VPS, atau langkah teknis lain.
- R2 (K7): Pengguna menambah provider OpenAI-compatible (preset populer atau URL custom) dan Anthropic dengan API key sendiri, mengetes koneksinya dari app, dan memakai provider mana pun yang lolos tes di chat.
- R3 (K2, K9): Chat di dalam app menampilkan jawaban streaming dengan markdown dan blok kode; percakapan tersimpan di daftar dan bisa dilanjutkan.
- R4 (K2, K4): Agent menjalankan perintah shell di Linux userland tersembunyi, termasuk coding (menulis file, `npm install`, menjalankan skrip); pengguna melihat tiap perintah dan hasilnya sebagai kartu aktivitas, dan userland hanya menyala saat tool shell dipakai.
- R5 (K2): Agent mencari di web dan membaca isi halaman untuk menjawab, dan pengguna bisa membuka halaman sumbernya; tanpa mengendalikan Chrome.
- R6 (K2, K6, riset Rek 2): Pengguna membuat tugas terjadwal sekali atau berulang lewat chat atau layar tugas terjadwal; tugas berjalan tepat waktu saat app tertutup dan layar mati, hasilnya muncul sebagai notifikasi dan di percakapan; app memandu pengecualian optimasi baterai dan pengaturan autostart per merek HP.
- R7 (K5): Pengguna memasang skill pihak ketiga berformat Agent Skills (`SKILL.md`) dan agent memakainya. Acuan: Binance Agentic Wallet (CLI npm `baw` + `SKILL.md`; login lewat kode pairing + konfirmasi di Binance App; transaksi lewat `contract-call preview/execute`).
- R8 (K9, K10): Aksi merusak, aksi keuangan, dan pengiriman data keluar tidak berjalan sebelum pengguna menyetujui kartu konfirmasi yang dirender app; teks dari LLM tidak bisa tampil sebagai kartu konfirmasi atau kartu aktivitas.
- R9 (K10): Log aksi mencatat setiap tool call (perintah, URL, file, skill) beserta hasilnya, dan pengguna bisa membukanya dari app.
- R10 (K10): Instruksi di konten web atau output tool tidak bisa memicu aksi yang butuh konfirmasi tanpa kartu konfirmasi R8.
- R11 (K9): Hasil kerja agent berupa halaman web/HTML atau file bisa dipratinjau di dalam app.
- R12 (K9, K11, riset Rek 5): App hemat kuota: saat key/provider kena limit, key/provider berikutnya yang dikonfigurasi dipakai; tugas agent hanya memakai model yang mendukung tools; konteks dipangkas; sisa kuota dan status provider terlihat di app.

## Constraints

- C1 (Visi): Open source dan gratis; tanpa server Lumynoid, inferensi lewat endpoint milik pengguna.
- C2 (K1): Tidak ada terminal yang terlihat di UI.
- C3 (K3; riset D2 [18][19][20]): Tidak mengendalikan app lain dan tidak memakai Accessibility API.
- C4 (K4; riset D3 [13][15]): Harness agent native Kotlin buatan sendiri; tidak bergantung pada CLI OpenCode/Claude Code atau harness yang butuh proses terus hidup.
- C5 (K4, K6; riset D1 [1][2][7][8]): Berbasis kejadian, tanpa proses always-on: exact alarm + notifikasi + FGS hanya selama ada tugas. FGS `dataSync` dibatasi 6 jam per 24 jam (Android 15+), dan job dari FGS ikut kuota normal (Android 16).
- C6 (K4; riset D1 [3][10]): Userland proot hanya hidup saat tool shell dipakai. Phantom process killer (maks 32 child process lintas app, Android 12+) dan batas memori berbasis RAM (Android 17) membatasi shell, node, dan git.
- C7 (riset D3 [11][4]): Eksekusi binary patuh W^X untuk targetSdk 29+ (tidak mengeksekusi file dari direktori yang bisa ditulis app). Build untuk Play terikat larangan unduh kode native; build GitHub/F-Droid tidak.
- C8 (K10): Shell disandbox di folder app; konten web diperlakukan sebagai data.
- C9 (K9): Kartu konfirmasi dan kartu aktivitas tool dibuat oleh app dari data tool call, bukan dari teks LLM.
- C10 (K7; [Anthropic legal](https://code.claude.com/docs/en/legal-and-compliance); riset [37]): MVP hanya API key milik pengguna. Login memakai langganan Claude dilarang untuk app pihak ketiga. Tidak menjanjikan model gratis OpenCode Zen (hanya bisa dipakai lewat OpenCode).
- C11 (K11; riset D4 [34][35][36][44]): Dirancang untuk kuota gratis: OpenRouter tanpa kredit 50 request/hari, sedangkan satu tugas butuh sekitar 10–20 panggilan LLM. Tool calling model gratis tidak andal (tool call kosong, "no endpoints support tool use") dan harus ditangani.
- C12 (K8): Kotlin + Jetpack Compose, Android saja.
- C13 (K12): App baru, bukan fork Operit; Operit (LGPL-3.0) hanya referensi teknis.
- C14 (keputusan, Masih terbuka; riset D2 [29][31][32]): Distribusi pasti lewat GitHub Releases dan F-Droid (anti-fitur NonFreeNet); Play belum diputuskan.

## Done when

- Penguji awam yang belum pernah melihat Lumynoid memasang APK dari GitHub Releases di HP sungguhan, mengisi API key-nya sendiri, lalu menyelesaikan satu chat, satu tugas coding lewat shell, satu jawaban dari pencarian web, dan satu tugas terjadwal, tanpa melihat terminal dan tanpa membaca dokumentasi.
- Tugas terjadwal harian berjalan tepat waktu dengan app tertutup dan layar mati, setiap hari selama 7 hari, di satu HP Xiaomi dan satu HP Samsung yang disiapkan lewat panduan di app.
- Pada skenario uji, setiap aksi merusak, keuangan, atau pengiriman data keluar menunggu kartu konfirmasi dan tercatat di log aksi; halaman web uji berisi prompt injection tidak bisa memicu aksi tanpa konfirmasi.
- Skill pihak ketiga berformat `SKILL.md` (acuan: Binance Agentic Wallet) terpasang dan dipakai agent sampai langkah transaksi `preview`, dan `execute` hanya jalan setelah konfirmasi.
- Dengan dua key/provider gratis yang dikonfigurasi, tugas tetap selesai saat key pertama kena limit, dan app menampilkan sisa kuotanya.
- Kode sumber terbit di GitHub, rilis v1 tersedia di GitHub Releases, dan app diajukan ke F-Droid.

## Boundaries

- Di luar v1: mengendalikan app lain, Accessibility, Shizuku, MediaProjection (K3).
- Di luar v1: mengendalikan Chrome; browsing hanya cari dan baca web (K2).
- Di luar v1, menyusul v1.x: Telegram dan kanal chat lain di luar app (K6); GenUI/A2UI dengan fallback teks (K9); Sign in with ChatGPT lewat Responses API, setelah kelayakannya untuk app Android dipastikan (K7).
- Tidak pernah: login memakai langganan Claude (K7).
- Di luar v1: LLM yang "berpikir terus" 24 jam; model lokal sebagai otak agent (riset Ringkasan eksekutif 2, D4).
- Di luar: iOS (K8); fork Operit (K12).
- Touch point: API provider LLM (OpenAI-compatible, Anthropic, OpenRouter) — dipakai lewat API key pengguna; owner: epic-chat-dan-provider.
- Touch point: endpoint sisa kuota provider (mis. OpenRouter) — dibaca untuk penghitung kuota; owner: epic-hemat-kuota.
- Touch point: npm registry dan sumber unduhan rootfs userland — dipakai shell dan coding; owner: epic-agent-shell.
- Touch point: layanan pencarian web — dipakai oleh tool cari; owner: epic-browsing-web.
- Touch point: Binance Agentic Wallet (`baw` + `SKILL.md`) — skill acuan untuk R7; owner: epic-skill-pihak-ketiga.
- Touch point: GitHub Releases — kanal rilis setiap epic; owner: epic-chat-dan-provider.
- Touch point: F-Droid — pengajuan rilis v1; owner: epic-rilis-publik.
- Tracer path: pengguna mengisi API key → mengirim chat → harness memanggil provider → agent menjalankan satu perintah di userland → kartu aktivitas dan, bila perlu, kartu konfirmasi tampil → jawaban mengalir ke chat.

## References

- research — `_bmad-output/research-lumynoid-android-agent/research-lumynoid-android-agent.md`, Ringkasan eksekutif; D1 (latar belakang 24 jam); D2 (kontrol HP, browser, distribusi); D3 (eksekusi kode); D4 (akses LLM gratis); D5 (peta proyek); Rekomendasi; Pertanyaan terbuka; Lampiran sumber [1]–[61]
- decisions — `_bmad-output/lumynoid-decisions.md`, Visi; Keputusan K1–K12; Masih terbuka; Urutan berikutnya

## Notes

- Assumption: "Selalu bisa dihubungi" di v1 berarti chat kapan saja saat app dibuka, dan tugas terjadwal berjalan saat app tertutup; menerima pesan masuk saat app tertutup baru datang bersama Telegram di v1.x (K6, riset Ringkasan eksekutif 2).
- Assumption: v1 dan MVP di catatan keputusan adalah rilis yang sama.
- Assumption: Onboarding pengecualian baterai dan panduan autostart per merek (riset Rek 2) masuk v1, karena tugas terjadwal (K2) bergantung padanya.
- Assumption: Model lokal untuk routing intent (riset Rek 5) tidak masuk v1; catatan keputusan tidak menyebutnya.
- Assumption: Uji daya tahan tugas terjadwal 7 hari berturut-turut; riset hanya menyatakan daya tahan di Xiaomi/Samsung harus diuji sendiri, angka 7 hari usulan PO.
- Open question: Distribusi Play: apakah FGS untuk tugas agent dan shell (binary dibundel + `npm install` saat runtime) lolos kebijakan Play [4][5][11]? Menunggu: distribusi dan architecture.
- Open question: Verifikasi developer 2027: akun penuh ($25 + identitas) atau advanced flow; jalur open-source registry dan APK bertanda tangan F-Droid [28][29][30]. Harus diputuskan sebelum rollout global 2027.
- Open question: Layanan pencarian web untuk tool cari (gratis atau murah) belum diriset. Menunggu: R5.
- Open question: Ukuran userland, strategi unduhnya, dan batas `npm install` di HP sungguhan (phantom process killer, memori Android 17) [3][10]. Menunggu: R4, R7.
- Open question: Library agent Kotlin/JVM (Koog, LangChain4j, atau buatan sendiri) belum dievaluasi. Menunggu: architecture.
- Open question: Model gratis mana yang andal untuk tool calling? Belum ada benchmark; perlu uji sendiri. Angka free tier Groq/Gemini hanya terlihat di console [38][61]. Menunggu: R12 dan preset provider di R2.
- Open question: Apakah app BYOK gratis wajib terdaftar sebagai PSE Komdigi?
- Open question: Lisensi open source Lumynoid sendiri belum dipilih.
- Open question: Aturan inklusi F-Droid (tanpa library non-free) belum dicek terhadap pilihan dependensi.
- Open question: Penyimpanan API key dan riwayat chat: apakah semuanya tetap di HP, terenkripsi, dan bisa dibackup? Sumber tidak menyebutnya.
- Open question: Bahasa UI v1 (Indonesia, Inggris, atau keduanya) dan versi Android minimum belum ditetapkan.
- Open question: Kebijakan `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dan angka konsumsi baterai belum ditemukan. Menunggu: R6.
- Decision: 2026-11-14 — Urutan: slice dulu, lalu `bmad-architecture` di level initiative, lalu `bmad-ux` saat epic chat dan agent di-refine; alasannya risiko terbesar ada di sisi teknis dan komponen chat sudah ditetapkan K9.
- Decision: 2026-11-14 — Slicing: epic dipotong per kemampuan karena Lumynoid satu app Android (satu batas deploy); tujuh epic dalam urutan build di `tickets/tickets.toml`, setiap epic merilis ke GitHub Releases.
- Decision: 2026-11-14 — Fondasi platform adalah story pertama epic-chat-dan-provider, bukan epic tersendiri.
- Decision: 2026-11-14 — Keputusan bersama (kontrak harness dan registry tool, adapter provider, siklus hidup userland, model kartu, penyimpanan key) ditetapkan di architecture spine `bmad-architecture`, lalu dirujuk epic pemakainya.
- Decision: 2026-11-14 — Tabrakan kode dicegah lewat kontrak: epic 3 hanya memanggil task runner epic 2; epic 4 memiliki penyusunan konteks dengan titik sambung untuk epic 5 dan 6, yang karenanya menunggu epic 4.
