---
type: initiative
title: "Orang awam punya asisten AI pribadi di HP Android-nya sendiri"
sources:
  - _bmad-output/research-lumynoid-android-agent/research-lumynoid-android-agent.md
  - _bmad-output/lumynoid-decisions.md
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
risk: high
---

# Orang awam punya asisten AI pribadi di HP Android-nya sendiri

## Description

Lumynoid v1 adalah app Android open source dan gratis. Pengguna memasangnya, mengisi endpoint AI miliknya, lalu punya asisten pribadi yang bisa chat, mengerjakan tugas coding, mencari dan membaca web, menjalankan tugas terjadwal, dan memakai skill pihak ketiga, tanpa langganan bot, VPS, atau terminal. Projects memisahkan ruang kerja proyek dari percakapan (K13). Riset menyimpulkan ide ini layak dengan syarat [riset, Ringkasan eksekutif]. Catatan 2026-11-14 menetapkan bentuk awal v1 (K1–K12). Replan 2026-11-21 menetapkan cakupan tool coding.

## Outcome

Orang awam bisa menyuruh asisten AI di HP-nya sendiri, memakai kuota API miliknya, untuk mengerjakan tugas nyata yang sebelumnya butuh langganan bot atau VPS.

## Requirements

- R1 (Visi, K1, K7): Orang awam memasang app, mengisi endpoint/API key AI miliknya, lalu mendapat jawaban chat pertama hanya lewat layar app, tanpa terminal, akun Lumynoid, VPS, atau langkah teknis lain.
- R2 (K7): Pengguna menambah provider OpenAI-compatible (preset populer atau URL custom) dan Anthropic dengan API key sendiri, mengetes koneksinya dari app, dan memakai provider mana pun yang lolos tes di chat.
- R3 (K2, K9): Chat di dalam app menampilkan jawaban streaming dengan markdown dan blok kode; percakapan tersimpan di daftar dan bisa dilanjutkan.
- R4 (K2, K4; keputusan replan 2026-11-21): Agent menyelesaikan tugas coding dengan tool terstruktur untuk menulis file, memasang paket npm lokal yang disediakan aplikasi, dan menjalankan skrip bawaan. Pengguna melihat aksi dan hasil di kartu aktivitas. Runtime Linux aktif hanya saat dibutuhkan.
- R5 (K2): Agent mencari di web dan membaca isi halaman untuk menjawab, dan pengguna bisa membuka halaman sumbernya; tanpa mengendalikan Chrome.
- R6 (K2, K6, riset Rek 2): Pengguna membuat tugas terjadwal sekali atau berulang lewat chat atau layar tugas terjadwal; tugas berjalan tepat waktu saat app tertutup dan layar mati, hasilnya muncul sebagai notifikasi dan di percakapan; app memandu pengecualian optimasi baterai dan pengaturan autostart per merek HP.
- R7 (K5): Pengguna memasang skill pihak ketiga berformat Agent Skills (`SKILL.md`) dan agent memakainya. Acuan: Binance Agentic Wallet (CLI npm `baw` + `SKILL.md`; login lewat kode pairing + konfirmasi di Binance App; transaksi lewat `contract-call preview/execute`).
- R8 (K9, K10): Aksi merusak, aksi keuangan, dan pengiriman data keluar tidak berjalan sebelum pengguna menyetujui kartu konfirmasi yang dirender app; teks dari LLM tidak bisa tampil sebagai kartu konfirmasi atau kartu aktivitas.
- R9 (K10): Log aksi mencatat setiap tool call (perintah, URL, file, skill) beserta hasilnya, dan pengguna bisa membukanya dari app.
- R10 (K10): Instruksi di konten web atau output tool tidak bisa memicu aksi yang butuh konfirmasi tanpa kartu konfirmasi R8.
- R11 (K9): Hasil kerja agent berupa halaman web/HTML atau file bisa dipratinjau di dalam app.
- R12 (K9, K11, riset Rek 5): App hemat kuota: saat key/provider kena limit, key/provider berikutnya yang dikonfigurasi dipakai; tugas agent hanya memakai model yang mendukung tools; konteks dipangkas; sisa kuota dan status provider terlihat di app.
- R13 (K13): Pengguna membuat proyek bernama dan membuka beberapa percakapan yang berbagi file, paket lokal, dan instruksi proyek, dengan riwayat AI dan log terpisah. Chat biasa tetap bisa dibuat tanpa proyek. Setelah konfirmasi, menghapus chat tidak menghapus data proyek; menghapus proyek menghapus file, paket, instruksi, dan semua chat serta log dalam proyek itu saja. Upgrade dari Epic 2 mempertahankan file, paket, chat, log, dan akses pratinjau yang sudah ada.

## Constraints

- C1 (Visi): Open source dan gratis; tanpa server Lumynoid, inferensi lewat endpoint milik pengguna.
- C2 (K1): Tidak ada terminal yang terlihat di UI.
- C3 (K3; riset D2 [18][19][20]): Tidak mengendalikan app lain dan tidak memakai Accessibility API.
- C4 (K4; riset D3 [13][15]): Harness agent native Kotlin buatan sendiri; tidak bergantung pada CLI OpenCode/Claude Code atau harness yang butuh proses terus hidup.
- C5 (K4, K6; riset D1 [1][2][7][8]): Berbasis kejadian, tanpa proses always-on: exact alarm + notifikasi + FGS hanya selama ada tugas. FGS `dataSync` dibatasi 6 jam per 24 jam (Android 15+), dan job dari FGS ikut kuota normal (Android 16).
- C6 (K4; riset D1 [3][10]): PRoot hanya hidup selama operasi yang memerlukannya. Batas proses dan memori Android berlaku.
- C7 (riset D3 [11][4]): Eksekusi binary patuh W^X untuk targetSdk 29+ (tidak mengeksekusi file dari direktori yang bisa ditulis app).
- C8 (K10; keputusan replan 2026-11-21): Aplikasi membatasi operasi coding ke aksi bawaan serta file ke folder proyek yang dipilih. Shell bebas menunggu bukti sandbox dan kebijakan egress. Konten web diperlakukan sebagai data.
- C9 (K9): Kartu konfirmasi dan kartu aktivitas tool dibuat oleh app dari data tool call, bukan dari teks LLM.
- C10 (K7; [Anthropic legal](https://code.claude.com/docs/en/legal-and-compliance); riset [37]): MVP hanya API key milik pengguna. Login memakai langganan Claude dilarang untuk app pihak ketiga. Tidak menjanjikan model gratis OpenCode Zen (hanya bisa dipakai lewat OpenCode).
- C11 (K11; riset D4 [34][35][36][44]): Dirancang untuk kuota gratis: OpenRouter tanpa kredit 50 request/hari, sedangkan satu tugas butuh sekitar 10–20 panggilan LLM. Tool calling model gratis tidak andal (tool call kosong, "no endpoints support tool use") dan harus ditangani.
- C12 (K8): Kotlin + Jetpack Compose, Android saja.
- C13 (K12): App baru, bukan fork Operit; Operit (LGPL-3.0) hanya referensi teknis.
- C14 (keputusan 2026-11-14; riset D2 [29][31][32]): v1 hanya didistribusikan sebagai APK unduhan manual dari GitHub Releases; Google Play dan F-Droid di luar v1.

## Done when

- Penguji awam yang belum pernah melihat Lumynoid memasang APK dari GitHub Releases di HP sungguhan, mengisi API key-nya sendiri, lalu menyelesaikan satu chat biasa, satu tugas lewat tool coding di proyek, satu jawaban dari pencarian web, dan satu tugas terjadwal, tanpa melihat terminal dan tanpa membaca dokumentasi.
- Tugas terjadwal harian berjalan tepat waktu dengan app tertutup dan layar mati, setiap hari selama 7 hari, di satu HP Xiaomi dan satu HP Samsung yang disiapkan lewat panduan di app.
- Pada skenario uji, setiap aksi merusak, keuangan, atau pengiriman data keluar menunggu kartu konfirmasi dan tercatat di log aksi; halaman web uji berisi prompt injection tidak bisa memicu aksi tanpa konfirmasi.
- Skill pihak ketiga berformat `SKILL.md` (acuan: Binance Agentic Wallet) terpasang dan dipakai agent sampai langkah transaksi `preview`, dan `execute` hanya jalan setelah konfirmasi.
- Dengan dua key/provider gratis yang dikonfigurasi, tugas tetap selesai saat key pertama kena limit, dan app menampilkan sisa kuotanya.
- Setelah upgrade dari APK Epic 2, pengguna membuka chat, log, dan pratinjau lama. Di satu proyek, chat kedua dapat membaca dan mengubah file chat pertama tanpa membawa riwayatnya; proyek lain dan chat biasa tidak dapat mengakses file itu. Hapus chat pertama tidak menghapus file; hapus proyek menghapus datanya tanpa memengaruhi proyek lain.
- Kode sumber terbit di GitHub, dan rilis v1 tersedia sebagai APK bertanda tangan di GitHub Releases dari developer Android terverifikasi.

## Boundaries

- Di luar v1: mengendalikan app lain, Accessibility, Shizuku, MediaProjection (K3).
- Di luar v1: mengendalikan Chrome; browsing hanya cari dan baca web (K2).
- Di luar v1, menyusul v1.x: Telegram dan kanal chat lain di luar app (K6); GenUI/A2UI dengan fallback teks (K9); Sign in with ChatGPT lewat Responses API, setelah kelayakannya untuk app Android dipastikan (K7).
- Tidak pernah: login memakai langganan Claude (K7).
- Di luar v1: LLM yang "berpikir terus" 24 jam; model lokal sebagai otak agent (riset Ringkasan eksekutif 2, D4).
- Di luar: iOS (K8); fork Operit (K12).
- Di luar v1: distribusi lewat Google Play dan F-Droid (C14).
- Di luar Projects v1: pemindahan percakapan antarproyek dan pemakaian otomatis riwayat percakapan lain sebagai konteks.
- Touch point: API provider LLM (OpenAI-compatible, Anthropic, OpenRouter) — dipakai lewat API key pengguna; owner: epic-chat-dan-provider.
- Touch point: endpoint sisa kuota provider (mis. OpenRouter) — dibaca untuk penghitung kuota; owner: epic-hemat-kuota.
- Touch point unduhan rootfs dikelola epic-agent-shell. Paket npm untuk tool coding disediakan aplikasi. Akses registry npm untuk skill pihak ketiga dibuktikan di epic-skill-pihak-ketiga.
- Touch point: layanan pencarian web — dipakai oleh tool cari; owner: epic-browsing-web.
- Touch point: Binance Agentic Wallet (`baw` + `SKILL.md`) — skill acuan untuk R7; owner: epic-skill-pihak-ketiga.
- Touch point: GitHub Releases — kanal rilis setiap epic; owner: epic-chat-dan-provider.
- Touch point: verifikasi developer Android (akun developer Google milik pemilik proyek) — pendaftaran nama package dan kunci tanda tangan; owner: epic-chat-dan-provider.
- Tracer path: pengguna mengisi API key → mengirim chat → harness memanggil provider → agent memakai tool coding → kartu aktivitas dan, bila perlu, kartu konfirmasi tampil → jawaban mengalir ke chat.

## Draf desain — Projects

- **Navigasi:** **Projects → New Project** membuat proyek bernama. Halaman Project menampilkan daftar percakapan, **New Conversation**, dan pengaturan instruksi proyek. **New Chat** tetap tersedia di luar Projects.
- **Konfirmasi penghapusan:** Dialog chat menjelaskan bahwa file proyek tetap ada. Dialog proyek menyebut nama proyek dan data yang akan dihapus. Penghapusan tidak tersedia selama ada pekerjaan terkait yang antre, berjalan, atau menunggu persetujuan.
- **Upgrade:** Chat lama yang memiliki file proyek atau instalasi paket tampil dalam proyek tersendiri; chat lain tetap menjadi chat biasa. File tidak disalin. Pengguna tidak perlu memindahkan data manual.
- **Integrasi jadwal:** Saat penjadwalan dirilis, hasil tugas proyek tetap berada di proyek itu. Hapus proyek juga membatalkan jadwalnya; percakapan yang masih menjadi tujuan hasil tidak dihapus sebelum jadwal tersebut dihapus.
- **Pemisahan rilis:** Projects tidak menunggu `SchedulerPort`. Epic Tugas Terjadwal menambahkan pembatalan jadwal ke alur hapus proyek ketika fitur jadwal dirilis. Epic Hemat Kuota mempertahankan instruksi proyek dan batas riwayat per chat saat memperkenalkan pemangkasan konteks.

## References

- research — `_bmad-output/research-lumynoid-android-agent/research-lumynoid-android-agent.md`, Ringkasan eksekutif; D1 (latar belakang 24 jam); D2 (kontrol HP, browser, distribusi); D3 (eksekusi kode); D4 (akses LLM gratis); D5 (peta proyek); Rekomendasi; Pertanyaan terbuka; Lampiran sumber [1]–[61]
- decisions — `_bmad-output/lumynoid-decisions.md`, Visi; Keputusan K1–K13; Masih terbuka; Urutan berikutnya
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-1–AD-26; Consistency Conventions; Stack; Capability → Architecture Map; Deferred
- Projects — [Epic 8 Projects](tickets/epic-projects/epic-projects.md); kontrak arsitektur Projects masih perlu diselaraskan lewat `bmad-architecture` sebelum menyusun stories.

## Notes

- Assumption: "Selalu bisa dihubungi" di v1 berarti chat kapan saja saat app dibuka, dan tugas terjadwal berjalan saat app tertutup; menerima pesan masuk saat app tertutup baru datang bersama Telegram di v1.x (K6, riset Ringkasan eksekutif 2).
- Assumption: v1 dan MVP di catatan keputusan adalah rilis yang sama.
- Assumption: Onboarding pengecualian baterai dan panduan autostart per merek (riset Rek 2) masuk v1, karena tugas terjadwal (K2) bergantung padanya.
- Assumption: Model lokal untuk routing intent (riset Rek 5) tidak masuk v1; catatan keputusan tidak menyebutnya.
- Assumption: Uji daya tahan tugas terjadwal 7 hari berturut-turut; riset hanya menyatakan daya tahan di Xiaomi/Samsung harus diuji sendiri, angka 7 hari usulan PO.
- Assumption: minSdk 29, targetSdk 37, ABI rilis arm64-v8a (architecture, Stack); dikonfirmasi di story 1.3 (rilis bertanda tangan) sebelum rilis pertama.
- Open question: Ukuran rootfs rilis, sumber unduhan, dan siklus hidup PRoot produksi di HP target. Menunggu Story 2.5. Spike 2.1 sudah membuktikan instalasi paket npm lokal di Samsung API 37.
- Open question: Model gratis mana yang andal untuk tool calling? Belum ada benchmark; perlu uji sendiri. Angka free tier Groq/Gemini hanya terlihat di console [38][61]. Menunggu: R12 dan preset provider di R2.
- Open question: Apakah app BYOK gratis wajib terdaftar sebagai PSE Komdigi?
- Open question: Lisensi open source Lumynoid belum dipilih; harus kompatibel dengan proot GPL-2.0 yang dibundel (architecture, Deferred). Menunggu: story 1.2, sebelum repo dibuat publik.
- Open question: Retensi dan ekspor riwayat chat belum ditetapkan; penyimpanan key dan larangan backup awan sudah di architecture AD-15, AD-25. Menunggu: sebelum epic-rilis-publik.
- Open question: Kebijakan `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dan angka konsumsi baterai belum ditemukan. Menunggu: R6.
- Decision: 2026-11-14 — Urutan: slice dulu, lalu `bmad-architecture` di level initiative, lalu `bmad-ux` saat epic chat dan agent di-refine; alasannya risiko terbesar ada di sisi teknis dan komponen chat sudah ditetapkan K9.
- Decision: 2026-11-14 — Slicing: epic dipotong per kemampuan karena Lumynoid satu app Android (satu batas deploy); tujuh epic dalam urutan build di `tickets/tickets.toml`, setiap epic merilis ke GitHub Releases.
- Decision: 2026-11-14 — Fondasi platform adalah story pertama epic-chat-dan-provider, bukan epic tersendiri.
- Decision: 2026-11-14 — Keputusan bersama (kontrak harness dan registry tool, adapter provider, siklus hidup userland, model kartu, penyimpanan key) ditetapkan di architecture spine `bmad-architecture`, lalu dirujuk epic pemakainya.
- Decision: 2026-11-14 — Tabrakan kode dicegah lewat kontrak: epic 3 hanya memanggil task runner epic 2; epic 4 memiliki penyusunan konteks dengan titik sambung untuk epic 5 dan 6, yang karenanya menunggu epic 4.
- Decision: 2026-11-14 — Architecture spine `architecture-lumynoid.md` diadopsi sebagai companion; epic merujuk AD yang mengikatnya di References.
- Decision: 2026-11-14 — Replan (AD-2): epic-chat-dan-provider ikut membangun kerangka TaskRunner + AgentService tanpa tool, dan chat polos sudah lewat `TaskRunner.submit`; epic-agent-shell menambah loop tool call, registry, dan gerbang ke kerangka yang sama. Menggantikan "epic 3 hanya memanggil task runner epic 2": epic 3 memanggil TaskRunner yang kerangkanya dari epic 1 dan loop tool-nya dari epic 2. Alasan: mencegah epic 1 membangun jalur chat terpisah yang lalu dibongkar epic 2. Yang dibutuhkan tidak berubah, hanya cara mengirimnya.
- Decision: 2026-11-14 — Pencarian web v1 memakai DuckDuckGo HTML sebagai backend default tanpa key, setelah dibandingkan dengan SearXNG, Marginalia, Wikipedia, Brave, dan Tavily; rapuh karena bot challenge, tetapi `SearchPort` membuat penggantiannya murah.
- Decision: 2026-11-14 — Pertanyaan library agent dan aturan inklusi F-Droid ditutup oleh architecture AD-11 (adapter sendiri di atas Ktor, tanpa Koog/LangChain4j) dan AD-17 (stack sudah dicek lolos).
- Decision: 2026-11-14 — v1 hanya lewat APK unduhan manual di GitHub Releases; Google Play dan F-Droid di luar v1 (C14). Menggantikan pengajuan F-Droid di Done when dan epic-rilis-publik.
- Decision: 2026-11-14 — Verifikasi developer Android memakai akun developer Google milik pemilik proyek; application id `dev.lusalhi.lumynoid`. Keduanya bersama kunci tanda tangan didaftarkan di story fondasi epic-chat-dan-provider, karena nama package tidak bisa diubah setelah terbit.
- Decision: 2026-11-14 — Architecture spine diperbarui lewat `bmad-architecture`: application id, distribusi GitHub saja, kunci rilis dan verifikasi developer (AD-25), dependensi (AD-17), judul kartu dan deep-link (AD-10), serta konvensi identitas manifest, format angka/waktu, dan versi.
- Decision: 2026-11-14 — Verifikasi developer di Indonesia sejak 30 September 2026 baru berlaku untuk instal dari app store; unduhan langsung dari GitHub baru diperiksa saat rollout global 2027. Pendaftaran tetap dilakukan sebelum APK bertanda tangan pertama, termasuk pre-release `v0.x` (AD-25).
- Decision: 2026-11-14 — Bahasa UI v1 adalah Inggris; semua string di `strings.xml`, jadi terjemahan bisa menyusul.
- Decision: 2026-11-14 — Story epic-chat-dan-provider: story 1 tracer bullet, lisensi di story 1.2, kunci rilis dan verifikasi developer di story 1.3; menggantikan "story fondasi" dan "story hitl pertama" di Decision di atas. ContextAssembler versi naif dibangun story 1.7 (riwayat chat saja); AD-13 menunggu pembaruan lewat `bmad-architecture`.
- Decision: 2026-11-14 — Penanganan tool call kosong/rusak (AD-26) milik runner di epic-agent-shell; epic-hemat-kuota memegang retry karena limit lewat router.
- Decision: 2026-11-21 — Replan Epic 2 setelah PRoot lulus di Samsung API 37. Tahap awal R4 memakai satu APK dengan folder proyek biasa dan tool coding yang ditentukan aplikasi. Eksekusi kode pihak ketiga menunggu bukti perlindungan data privat dan egress. Epic skill memakai lifecycle userland dan gerbang tool dari Epic 2, lalu membuktikan perlindungan CLI skill sebelum mengaktifkannya. [Epic 2](tickets/epic-agent-shell/epic-agent-shell.md)
- Decision: 2026-11-21 — Desain fitur Projects disetujui: Project memiliki workspace dan instruksi bersama, Conversation memiliki konteks dan log sendiri. Chat biasa tetap ada. Implementasi terpisah dari penerimaan APK Epic 2 di Story 2.12.
- Decision: 2026-11-22 — Replan memasukkan Projects ke v1 sebagai R13 (K13). Pemilik menyetujui Epic 8 setelah rilis Epic 2 dan sebelum Tugas Terjadwal, Hemat Kuota, dan Rilis Publik. R1–R12, ID epic yang lama, dan Story 2.12 tetap.
