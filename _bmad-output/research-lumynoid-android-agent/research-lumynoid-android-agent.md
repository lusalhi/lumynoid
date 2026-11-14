---
title: 'technical research: Lumynoid — AI agent Android open source 24/7 dengan API gratis'
type: 'technical'
topic: 'Lumynoid — AI agent Android open source 24/7 dengan API gratis'
decision: 'Apakah Lumynoid layak dibangun sebagai app Android open source, dengan arsitektur apa, dan di mana posisinya dibanding proyek yang sudah ada'
source: 'run'
status: complete
preset: 'standard'
validation: 'normal'
created: '2026-11-14'
updated: '2026-11-14'
claims_verified: 5
claims_unverified: 16
---

# Riset teknis: Lumynoid — AI agent Android open source 24/7 dengan API gratis

**Keputusan yang dilayani riset ini:** Apakah Lumynoid layak dibangun sebagai app Android open source, dengan arsitektur apa, dan di mana posisinya dibanding proyek yang sudah ada.

## Ringkasan eksekutif

**Kesimpulan: layak dibangun, dengan tiga syarat.** Ketiganya mengubah ide awal secara nyata:

1. **Didistribusikan di luar Google Play**, yaitu lewat GitHub Releases dan F-Droid. Kebijakan Play secara eksplisit melarang Accessibility API dipakai oleh app yang "secara otonom memulai, merencanakan, dan mengeksekusi tindakan" [18][19][20]. Kebijakan foreground service di Play juga mensyaratkan layanan hanya berjalan "selama dibutuhkan" [4][5]. Jalur di luar Play cocok dengan sifat open source, dan ada presedennya: asisten BYOK berbasis Accessibility sudah diterima di F-Droid [33]. Di Indonesia, APK langsung dan F-Droid **belum** terkena verifikasi developer sampai rollout global 2027 [28][29].
2. **"24 jam" diartikan "selalu bisa dihubungi", bukan "LLM berpikir terus".** Kuota gratis adalah batas yang paling keras: OpenRouter tanpa kredit hanya 50 request per hari [34], sementara satu tugas di HP butuh sekitar 10–20 panggilan LLM [44]. Itu cukup untuk beberapa tugas sehari, bukan untuk agent yang terus aktif. Prosesnya sendiri bisa dibuat tetap hidup lewat foreground service, ditambah pengguna mematikan optimasi baterai. Pengecualian baterai itu juga termasuk dalam daftar resmi syarat yang mengizinkan app memulai foreground service dari latar belakang [7]. Namun pabrikan HP seperti Xiaomi dan Samsung tetap agresif mematikan app [6].
3. **Bersaing lewat integrasi, bukan fitur tunggal.** Setiap komponennya sudah ada di proyek lain. **Operit** (LGPL-3.0, aktif, UI-nya sudah berbahasa Indonesia) sudah punya kontrol HP, terminal Ubuntu, workflow terjadwal, dan BYOK, tetapi belum punya mode 24/7 eksplisit maupun Telegram [46][47]. App "Claw" yang selalu menyala (PocketClaw di F-Droid, DroidClaw) tidak bisa mengendalikan UI HP [51][52]. PokeClaw bisa mengendalikan UI tetapi tidak punya penjadwal [53]. Celah yang masih kosong adalah **semuanya dalam satu app, dirancang untuk kuota gratis sejak awal, dan dikendalikan lewat chat**.

**Catatan terbesar:** API AI gratis menyusut dan sering berubah. GitHub Models sudah ditutup [41], Cerebras kini hanya trial 30 hari [40], dan Gemini tidak lagi mempublikasikan batas free tier [38]. Model gratis OpenCode Zen, yang Anda sebut di ide awal, menurut dokumentasinya **hanya bisa dipakai di dalam OpenCode** [37]. Satu proyek sejenis (ZeroClaw-Android, "AI agents 24/7 on Android") diarsipkan pemiliknya karena "terlalu besar dan terlalu tidak aman" [54].

## D1 — Agent 24 jam di Android

- **Tidak ada jenis foreground service (FGS) yang cocok untuk agent yang terus menyala.** Untuk app yang menargetkan Android 15 ke atas, `dataSync` dibatasi total 6 jam per 24 jam. Saat batas tercapai, app wajib berhenti atau crash [1]. Google sendiri menyarankan memakai API alternatif seperti WorkManager [1].
- **Android 16 menghapus celah kuota.** Job yang dijalankan dari dalam FGS kini tetap mengikuti kuota normal [2].
- **Android 17 menambah risiko baru.** Batas memori berdasarkan RAM perangkat membuat proses besar yang hidup lama, misalnya userland Linux, berisiko dimatikan [3].
- **Pabrikan HP memperburuk keadaan.** dontkillmyapp memberi Xiaomi, Samsung, OnePlus, dan Huawei skor terburuk (5/5) [6]. Pengguna harus mengaktifkan autostart, mengunci app di recents, dan mengatur baterai ke "No restrictions" secara manual. Panduan untuk HyperOS belum ada, jadi seberapa mutakhir panduan ini belum terverifikasi (keyakinan sedang).
- **Agent bisa dibangunkan saat dibutuhkan.** Pengecualian resmi yang mengizinkan app memulai FGS dari latar belakang mencakup: pesan FCM berprioritas tinggi (bisa diturunkan prioritasnya oleh sistem), exact alarm untuk tindakan yang diminta pengguna, dan pengguna yang mematikan optimasi baterai untuk app tersebut [7]. Sebaliknya, Accessibility atau NotificationListener yang sedang terikat **tidak** termasuk daftar itu (disimpulkan dari ketiadaannya, keyakinan sedang) [7].
- Android 17 menambah exact alarm berbasis listener yang ditujukan bagi app yang selama ini menahan wakelock terus-menerus [8].
- **Pola di app lain.** ntfy dan Home Assistant memakai FGS dengan notifikasi permanen plus koneksi terbuka, dan keduanya memperingatkan dampaknya ke baterai. Angka konsumsi baterainya tidak dipublikasikan [9].
- **Pembunuh proses Android 12+.** "Phantom process killer" mematikan child process di atas 32 proses (batas ini dibagi untuk semua app) dan proses yang memakan CPU berlebihan [10]. Ini relevan kalau agent menjalankan shell, node, atau git.

## D2 — Kontrol HP dan browser, serta distribusi

**Kontrol HP**
- **Google Play tertutup untuk agent otonom.** Kebijakan Play: *"Any use of the Accessibility API that enables an app to autonomously initiate, plan, and execute actions or decisions is strictly prohibited"* [18]. Otomasi yang deterministik berdasarkan skrip buatan manusia masih diizinkan [18]. Larangan ini diulang di halaman izin sensitif [19][20]. Kategori "automation tools, assistants" tidak boleh mengklaim `isAccessibilityTool` [18].
- **Di luar Play, Accessibility tetap jalur utama, tetapi dengan hambatan.**
  - Android 13+ memblokir app hasil sideload dari Accessibility sampai pengguna mengizinkan "restricted settings". Sumbernya sekunder dan sudah lewat batas kesegaran.
  - Advanced Protection Mode di Android 17 (opt-in) memblokir sideload; ini terkonfirmasi di dokumentasi resmi [8].
  - Laporan bahwa mode itu juga mencabut Accessibility dari app non-aksesibilitas hanya berasal dari sumber sekunder [24] dan tidak disebut di halaman resmi Android 17. Status: **belum terverifikasi**.
- **API agent resmi Google belum terbuka untuk app pihak ketiga.** AppFunctions masih beta untuk Android 16+, dan akses pipeline lengkapnya dibatasi [21][23]. Framework UI-automation Google hanya tersedia sebagai preview untuk Gemini [22].
- **Shizuku via wireless debugging** bisa dipakai tanpa root, tetapi harus diulang setiap reboot [27]. Cocok sebagai opsi untuk power user, bukan jalur utama.
- **Membaca layar lewat MediaProjection tidak bisa tanpa pengguna.** Izinnya diminta setiap sesi dan sesi berhenti saat layar terkunci [25].

**Browser**
- Playwright untuk Android masih eksperimental dan membutuhkan komputer host via ADB [26].
- Di dalam app, pilihan yang realistis adalah WebView ditambah `evaluateJavascript` (keyakinan sedang, dari ringkasan pencarian). Satu lagi alternatifnya adalah mengendalikan Chrome lewat Accessibility.

**Distribusi**
- **Indonesia termasuk empat negara pertama** yang menerapkan verifikasi developer sejak 30 September 2026. Namun pada fase ini verifikasi hanya berlaku untuk instalasi dari 7 store mitra (Play, Galaxy Store, Xiaomi GetApps, dan lainnya) [28].
- FAQ resmi menyatakan bahwa untuk sideload langsung dan store lain (termasuk F-Droid), *"these new verification requirements won't apply to your app yet"* [29]. Rollout global dijadwalkan 2027 [30].
- **Setelah verifikasi berlaku untuk semua jalur**, app yang developernya tidak terdaftar hanya bisa dipasang lewat ADB atau "advanced flow". Advanced flow adalah pengaturan sekali jalan dengan masa tunggu 1 hari [29].
- Pilihan bagi developer [28][29]:
  - akun hobbyist gratis, maksimal 20 perangkat, sehingga tidak cocok untuk rilis publik;
  - akun penuh, biaya $25 dan wajib KTP/identitas;
  - tetap tidak terdaftar.
- **F-Droid** masih beroperasi aktif (55 app baru dalam seminggu) dan menolak program verifikasi [31]. Label anti-fitur "NonFreeNet" hanya berfungsi sebagai peringatan, bukan alasan penolakan [32]. Presedennya TypeAssist: asisten BYOK berbasis Accessibility, GPL-3.0, sudah ada di F-Droid [33].

## D3 — Menjalankan kode di HP

- **Aturan W^X sejak Android 10.** Untuk app yang menargetkan SDK 29 ke atas, mengeksekusi file dari direktori yang bisa ditulis app adalah pelanggaran. Karena itu Termux versi F-Droid bertahan di targetSdk 28 [11]. Versi Termux di Play memakai trik `system_linker_exec` dan masih berlabel eksperimental (keyakinan sedang) [12].
- **Play melarang mengunduh kode native** (dex, JAR, .so) dari luar Play. Kode yang dijalankan di interpreter atau VM (JS, Python) dikecualikan, dengan syarat tidak membuka celah pelanggaran kebijakan Play [4]. Build di luar Play tidak terikat aturan ini.
- **CLI coding agent tidak jalan langsung di Android.**
  - OpenCode tidak punya build Android; issue-nya ditutup otomatis tanpa perbaikan [13]. Ada fork komunitas yang memakai shim glibc (keyakinan sedang) [14].
  - Claude Code rusak di Termux sejak v2.1.113. Issue #50270 **masih terbuka** per 2026-10-25 (terverifikasi) [15].
  - Jalur yang terbukti jalan adalah userland Linux lewat proot, cara yang dipakai Operit dan Vibe [46][55].
- **Runtime yang bisa ditanam di app.** Chaquopy (Python) aktif, rilis terakhir 17.2.0 pada Oktober 2026 [16]. nodejs-mobile masih tertahan di Node 18 [17].

## D4 — Akses LLM gratis

| Penyedia | Kondisi saat ini | Sumber |
|---|---|---|
| OpenRouter `:free` | 20 RPM; 50 request/hari (kurang dari 10 kredit), 1000/hari (10 kredit atau lebih). **Terverifikasi** dua kali di halaman resmi | [34] |
| OpenCode Zen free | 14 model gratis "untuk waktu terbatas", sebagian sudah dihentikan pada November 2026. **Wajib lewat OpenCode**; sebagian model memakai data pengguna untuk training | [37] |
| Gemini API | Tabel batas free tier tidak lagi dipublikasikan; diarahkan ke dashboard AI Studio | [38] |
| Gemini (laporan sekunder) | Dipangkas pada Desember 2025 (Flash sekitar 20–50 RPD), keyakinan rendah | [39] |
| Groq | Batas Free plan tidak terlihat publik; Developer plan 30 RPM / 1K RPD | [61] |
| Mistral Experiment | Tanpa angka publik; data bisa dipakai untuk training | [60] |
| Cerebras | Kini trial $5 selama 30 hari, wajib kartu pembayaran | [40] |
| GitHub Models | **Ditutup** 30 Juli 2026 | [41] |

- **Tool calling di model gratis tidak andal**, menurut laporan anekdot dari forum dan blog, bukan benchmark: ada error "no endpoints support tool use" dan tool call kosong [35][36].
- **Konsumsi per tugas.** Agent HP berbasis GUI butuh rata-rata 18,6 langkah per tugas, sedangkan agent berbasis CLI 10,7 langkah (preprint 2026, keyakinan sedang) [44]. Pemakaian token bisa berbeda hingga 30 kali untuk tugas yang sama [45].
- **Hitungan kasar** (keyakinan rendah karena masukannya sendiri tidak pasti): 50 RPD cukup untuk sekitar 2–5 tugas per hari, dan 1000 RPD untuk sekitar 50–100 tugas.
- **Model lokal di HP sebagai cadangan.** Prompt API Gemini Nano masih beta dan tidak mendukung tool calling langsung [42]. LiteRT-LM mendukung function calling [43], tetapi model kecil tidak sanggup merangkai banyak langkah. Model lokal cocok untuk *routing* intent, bukan sebagai otak agent.

## D5 — Peta proyek yang sudah ada

| Proyek | Lisensi | Jalan penuh di HP | Kontrol UI HP | Selalu menyala / terjadwal | Coding | Kanal chat | Catatan |
|---|---|---|---|---|---|---|---|
| **Operit** [46][47] | LGPL-3.0 | Ya | Ya (Accessibility, Shizuku, root) | Workflow terjadwal, Tasker, intent; tanpa mode 24/7 eksplisit | Terminal Ubuntu (proot) | QQ bot, A2A; tanpa Telegram | Rilis tiap 4–8 minggu (v1.12.2, Sep 2026); UI berbahasa Indonesia; tidak ada di Play maupun F-Droid |
| PokeClaw [53] | Apache-2.0 | Ya | Ya (Accessibility) | Watchdog FGS; tanpa penjadwal | — | Telegram masih diuji | Model lokal Gemma via LiteRT-LM; v0.7.1 Mei 2026 |
| PocketClaw [52] | MIT | Ya | Tidak | Gateway FGS | python, git, gh | Telegram | Di F-Droid sejak Okt 2026 |
| DroidClaw (dgirard) [51] | Tidak dinyatakan | Ya | Tidak | FGS + cron (minimal 15 menit) | — | Telegram | 27 stars, belum ada rilis |
| openclaw-android [50] | MIT | Ya (Termux) | Tidak | Ya, tetapi rawan dimatikan sistem | Ya | Lewat OpenClaw | Panduan menonaktifkan phantom process killer |
| Vibe/AgentLabs OpenClaw APK [55] | Tidak dipublikasikan | Ya | Tidak | FGS | Ya (Node) | — | Ada di Play; klaim dari vendor sendiri |
| DroidRun / Mobilerun [48] | MIT | **Tidak** (perlu PC + ADB) | Ya | — | — | — | Sekitar 10k stars |
| Open-AutoGLM [49] | Apache-2.0 | **Tidak** (perlu PC) | Ya | — | — | — | Khusus riset |
| mobile-use [57] | Apache-2.0 | **Tidak** | Ya | — | — | — | — |
| Panda/Blurr [58] | Personal Use License (non-OSI) | Ya | Ya | — | — | — | Masih WIP |
| ZeroClaw-Android [54] | MIT | — | — | "24/7" | — | — | **Diarsipkan** Maret 2026 karena alasan keamanan dan cakupan |
| Hermes Agent [56] | MIT | Bisa lewat Termux (didukung resmi) | Tidak | Cron bawaan | Ya | Telegram, WhatsApp, dan lainnya | Hosting yang direkomendasikan: VPS atau serverless |

**Kenapa orang memakai VPS:** VPS hanya menampung loop agent, memori, dan terminal. Inferensinya tetap di API cloud, sehingga server $5 sudah cukup. Laptop tidak dipakai karena agent berhenti saat laptop tidur [56]. HP bisa menggantikan peran VPS itu, asalkan mampu bertahan dari batasan latar belakang Android (lihat D1).

## Wawasan lintas dimensi

- **Satu keputusan arsitektur sekaligus menentukan distribusi.** Tiga kebijakan Play saling menguatkan untuk menolak Lumynoid: Accessibility otonom [18], FGS yang berjalan terus [4][5], dan larangan mengunduh kode native [4]. Kehilangan Play nyaris tidak merugikan app open source karena F-Droid dan GitHub tersedia [31][33]. Namun hal yang sama membuat **rollout verifikasi 2027 menjadi risiko distribusi terbesar** [30].
- **Kuota API membatasi "24 jam" lebih ketat daripada Android.** Kalaupun prosesnya berhasil dibuat terus hidup, kuota 50–1000 request per hari [34] hanya cukup untuk beberapa sampai puluhan tugas. Rancangan yang masuk akal: proses ringan yang selalu siap (FGS ditambah long-poll Telegram, alarm, notifikasi), dan LLM baru dipanggil saat ada tugas. Ini juga sejalan dengan anjuran Google untuk memakai exact alarm berbasis listener [8].
- **Integrasi adalah pembeda, dan sekaligus risiko keamanan.** Gabungan aksi otonom, akses shell, dan Accessibility persis kombinasi yang membuat Google melarangnya di Play [19] dan membuat ZeroClaw diarsipkan [54]. Konfirmasi manusia untuk aksi sensitif dan izin per kemampuan bukan pelengkap, melainkan syarat agar proyek ini layak dipercaya.
- **Agent berbasis CLI lebih hemat langkah daripada agent GUI** (10,7 vs 18,6 langkah) [44]. Ini memperkuat argumen untuk memprioritaskan tool terstruktur (intent, API, shell) dan memakai kontrol UI hanya sebagai jalan terakhir, demi menghemat kuota.

## Rekomendasi

Masukan untuk brief/spec dan architecture spine:

1. **Distribusi:** GitHub Releases dan F-Droid (dengan anti-fitur NonFreeNet), tidak lewat Play. Sebelum 2027, putuskan antara mendaftar akun penuh ($25 + identitas) atau mengandalkan advanced flow, dan cek jalur "open-source registry" (lihat pertanyaan terbuka). *Keyakinan tinggi; sumber utama [18][28][29][33].*
2. **Arsitektur "selalu bisa dihubungi":**
   - FGS `specialUse` ditambah onboarding pengecualian baterai, yang juga membuka hak memulai FGS dari latar belakang [7];
   - panduan autostart per merek HP [6];
   - exact alarm untuk jadwal [7][8];
   - kanal input via Telegram (long polling, tanpa IP publik [51]) ditambah UI chat di app.

   *Keyakinan tinggi untuk mekanismenya; daya tahan di HP Xiaomi/Samsung harus diuji sendiri.*
3. **Lapisan kontrol bertingkat:** tool terstruktur (intent, API, shell) terlebih dahulu → Accessibility sebagai fallback → Shizuku opsional. Browser memakai WebView di dalam app. MediaProjection tidak dipakai untuk mode tanpa pengguna [25]. *Keyakinan tinggi.*
4. **Eksekusi kode:** userland Linux lewat proot yang dibundel di app, atau Chaquopy untuk sandbox Python yang lebih ringan. Jangan bergantung pada CLI OpenCode atau Claude Code native, karena keduanya rusak di Android [13][15]. Bangun agent loop sendiri. *Keyakinan tinggi.*
5. **LLM dirancang untuk kuota gratis sejak awal:**
   - multi-provider BYOK dengan rotasi kunci dan provider;
   - filter model yang mendukung tools [34];
   - pemangkasan konteks;
   - penghitung kuota (OpenRouter mengekspos sisa kuota harian [34]);
   - model lokal hanya untuk routing.

   Jangan menjanjikan dukungan "OpenCode free" [37]. *Keyakinan tinggi untuk angka OpenRouter; rendah untuk keandalan tool calling model gratis.*
6. **Putuskan dulu: bangun dari nol atau kontribusi/fork Operit.** Operit sudah mencakup sebagian besar fitur dan lisensinya LGPL [46][47]. Pembeda Lumynoid baru jelas kalau fokus ke 24/7, desain kuota gratis, kanal Telegram/WhatsApp, dan keamanan. *Keyakinan sedang; perilaku latar belakang Operit belum diuji langsung.*

## Pertanyaan terbuka

- **Apakah Advanced Protection di Android 17 final benar-benar mencabut Accessibility dari app non-aksesibilitas?** Sejauh ini hanya ada sumber sekunder [24]. Untuk menjawabnya: cek dokumentasi `AdvancedProtectionManager` atau uji di perangkat dengan Android 17.
- **Seberapa andal penjadwal dan latar belakang Operit di HP Xiaomi/Samsung?** Perlu dicoba langsung, atau ditelusuri lewat issue tracker-nya.
- **Jalur "open-source registry" untuk verifikasi developer**, dan apakah APK yang ditandatangani F-Droid bisa ikut terdaftar. Perlu membaca panduannya di developer.android.com.
- **Apakah app BYOK gratis wajib terdaftar sebagai PSE Komdigi?** Ini lead yang belum dikejar.
- **Kebijakan `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** dan angka konsumsi baterai FGS yang selalu menyala. Belum ditemukan angka yang dipublikasikan.
- **Angka free tier Groq dan Gemini terkini**, yang hanya terlihat di console masing-masing.
- **Benchmark keandalan tool calling model gratis.** Belum ada; perlu uji sendiri.

## Lampiran sumber

| # | Mendukung | Penerbit | Tgl terbit | Diakses | Keyakinan |
|---|---|---|---|---|---|
| [1] | Timeout dataSync 6 jam/24 jam (Android 15) | [Android Developers](https://developer.android.com/develop/background-work/services/fgs/timeout) | undated | 2026-11-14 | tinggi |
| [2] | Android 16: job dari FGS ikut kuota | [Android Developers](https://developer.android.com/develop/background-work/services/fgs/changes) | undated | 2026-11-14 | tinggi |
| [3] | Android 17: batas memori berbasis RAM | [Android Developers](https://developer.android.com/about/versions/17/behavior-changes-all) | undated | 2026-11-14 | tinggi |
| [4] | Kebijakan FGS Play; larangan unduh kode native | [Google Play Console Help](https://support.google.com/googleplay/android-developer/answer/16273414) | undated | 2026-11-14 | tinggi |
| [5] | Deklarasi tipe FGS, specialUse terbatas | [Google Play Console Help](https://support.google.com/googleplay/android-developer/answer/13392821) | undated | 2026-11-14 | tinggi |
| [6] | Peringkat OEM yang mematikan app | [dontkillmyapp.com](https://dontkillmyapp.com/) | undated | 2026-11-14 | sedang |
| [7] | Pengecualian memulai FGS dari latar belakang | [Android Developers](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | undated | 2026-11-14 | tinggi |
| [8] | Android 17: exact alarm berbasis listener; AAPM blokir sideload | [Android Developers](https://developer.android.com/about/versions/17/features) | undated | 2026-11-14 | tinggi |
| [9] | Pola always-on ntfy (FGS + notifikasi permanen) | [ntfy docs](https://docs.ntfy.sh/subscribe/phone/) | undated | 2026-11-14 | tinggi |
| [10] | Phantom process killer Android 12+ | [Termux (GitHub)](https://github.com/termux/termux-app) | undated | 2026-11-14 | tinggi |
| [11] | W^X Android 10; Termux tetap targetSdk 28 | [Termux wiki](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10) | 2024-05 | 2026-11-14 | tinggi |
| [12] | Build Termux untuk Play (eksperimental) | [termux-play-store](https://github.com/termux-play-store) | 2024-06–2026-01 | 2026-11-14 | sedang |
| [13] | OpenCode tanpa build Android | [OpenCode GitHub #12515](https://github.com/anomalyco/opencode/issues/12515) | 2026-02 | 2026-11-14 | tinggi |
| [14] | Fork komunitas OpenCode untuk Termux | [newreleases.io / Hope2333](https://newreleases.io/project/github/Hope2333/opencode-termux/release/Push260719) | 2026-07 | 2026-11-14 | sedang |
| [15] | Claude Code rusak di Termux, issue masih terbuka | [anthropics/claude-code #50270](https://github.com/anthropics/claude-code/issues/50270) | 2026-04 (aktivitas 2026-10-25) | 2026-11-14 | tinggi |
| [16] | Chaquopy aktif (17.2.0) | [Chaquo changelog](https://chaquo.com/chaquopy/doc/current/changelog.html) | 2026-10 | 2026-11-14 | tinggi |
| [17] | nodejs-mobile tertahan di Node 18 | [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile/releases) | undated | 2026-11-14 | sedang |
| [18] | Larangan Accessibility otonom; "assistants" tidak boleh isAccessibilityTool | [Google Play Console Help](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en) | undated (©2026) | 2026-11-14 | tinggi |
| [19] | Larangan diulang di kebijakan izin sensitif | [Google Play Console Help](https://support.google.com/googleplay/android-developer/answer/16585319) | undated (©2026) | 2026-11-14 | tinggi |
| [20] | Larangan Accessibility otonom; USE_EXACT_ALARM terbatas | [Google Play Console Help](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en) | undated | 2026-11-14 | tinggi |
| [21] | AppFunctions: akses terbatas | [Android Developers](https://developer.android.com/ai/appfunctions) | undated (mention 2026-05) | 2026-11-14 | tinggi |
| [22] | UI automation framework hanya preview untuk Gemini | [Android Developers Blog](https://developer.android.com/blog/posts/the-intelligent-os-making-ai-agents-more-helpful-for-android-apps) | 2026-02-25 | 2026-11-14 | tinggi |
| [23] | AppFunctions beta (ADS 2026) | [Android Developers Blog](https://developer.android.com/blog/posts/build-it-your-way-a-next-era-of-android-development) | 2026-10-28 | 2026-11-14 | tinggi |
| [24] | AAPM Android 17 mencabut Accessibility (sekunder) | [Security Affairs](https://securityaffairs.com/189497/security/advanced-protection-mode-in-android-17-prevents-apps-from-misusing-accessibility-services.html) | 2026-03-16 | 2026-11-14 | sedang (unverified) |
| [25] | MediaProjection: izin per sesi, berhenti saat layar terkunci | [Android Developers](https://developer.android.com/media/grow/media-projection) | undated | 2026-11-14 | tinggi |
| [26] | Playwright Android eksperimental, butuh host ADB | [Playwright docs](https://playwright.dev/docs/api/class-android) | undated | 2026-11-14 | tinggi |
| [27] | Shizuku wireless: diulang setiap reboot | [Shizuku manual](https://shizuku.rikka.app/guide/setup.html) | undated | 2026-11-14 | sedang |
| [28] | Verifikasi developer di Indonesia: hanya 7 store mitra | [Android Developers](https://developer.android.com/developer-verification/guides) | undated (timeline 2026-07-15) | 2026-11-14 | tinggi |
| [29] | FAQ: sideload langsung belum terkena; ADB & advanced flow | [Android Developers FAQ](https://developer.android.com/developer-verification/guides/faq) | 2026-07-15 | 2026-11-14 | tinggi |
| [30] | Rollout global 2027; akun hobbyist 20 perangkat | [Android Developers Blog](https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html) | 2026-03-30 | 2026-11-14 | tinggi |
| [31] | F-Droid aktif, menolak verifikasi | [F-Droid TWIF](https://f-droid.org/en/2026/11/09/twif.html) | 2026-11-09 | 2026-11-14 | tinggi |
| [32] | Anti-fitur F-Droid (NonFreeNet) | [F-Droid docs](https://f-droid.org/en/docs/Anti-Features/) | undated | 2026-11-14 | tinggi |
| [33] | Preseden TypeAssist (BYOK + Accessibility) di F-Droid | [F-Droid](https://f-droid.org/packages/com.typeassist.app/) | 2026-08-13 | 2026-11-14 | tinggi |
| [34] | Batas model gratis OpenRouter; endpoint sisa kuota | [OpenRouter docs](https://openrouter.ai/docs/api-reference/limits) | live | 2026-11-14 | tinggi (verified) |
| [35] | Tool calling model gratis tidak andal (anekdot) | [n8n community](https://community.n8n.io/t/tool-calling-bug-for-agents-using-openrouter-various-models/295318) | undated | 2026-11-14 | rendah |
| [36] | Rantai fallback model gratis (anekdot) | [DEV Community](https://dev.to/mrclaw207/i-wired-openrouter-free-models-into-my-openclaw-fallback-chain-heres-what-actually-works-580f) | undated | 2026-11-14 | rendah |
| [37] | Model gratis OpenCode Zen: terbatas waktu, wajib OpenCode, privasi | [OpenCode docs](https://opencode.ai/docs/zen/) | undated (deprecasi 2026-11) | 2026-11-14 | tinggi |
| [38] | Gemini tidak lagi mempublikasikan batas free tier | [Google AI Studio](https://aistudio.google.com/docs/rate-limits) | undated | 2026-11-14 | tinggi |
| [39] | Pemangkasan free tier Gemini (sekunder) | [agentdeals.dev](https://agentdeals.dev/gemini-api-pricing-changes) | 2026-04-08 | 2026-11-14 | rendah |
| [40] | Cerebras kini trial $5/30 hari | [Cerebras docs](https://inference-docs.cerebras.ai/support/rate-limits) | undated (perubahan 2026-07) | 2026-11-14 | tinggi |
| [41] | GitHub Models ditutup 2026-07-30 | [GitHub Docs](https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models) | 2026-07-30 | 2026-11-14 | tinggi |
| [42] | Prompt API Gemini Nano beta, tanpa tool calling langsung | [Google ML Kit](https://developers.google.com/ml-kit/genai/prompt/android) | undated | 2026-11-14 | tinggi |
| [43] | LiteRT-LM mendukung function calling | [Google AI Edge](https://ai.google.dev/edge/litert-lm/overview) | undated | 2026-11-14 | sedang |
| [44] | Langkah per tugas: CLI 10,7 vs GUI 18,6 | [alphaXiv 2606.19388](https://www.alphaxiv.org/abs/2606.19388.md) | 2026-06 | 2026-11-14 | sedang |
| [45] | Variansi token agent hingga 30× | [Stanford Digital Economy Lab](https://digitaleconomy.stanford.edu/publication/how-do-ai-agents-spend-your-money) | 2026-05 | 2026-11-14 | sedang |
| [46] | Operit: kemampuan, lisensi, bahasa UI | [Operit (GitHub)](https://github.com/AAswordman/Operit) | live | 2026-11-14 | tinggi |
| [47] | Operit: rilis v1.12.x, QQ bot, latar belakang | [Operit releases](https://github.com/AAswordman/Operit/releases) | 2026-03 – 2026-09 | 2026-11-14 | tinggi |
| [48] | DroidRun: butuh PC + ADB | [DroidRun (GitHub)](https://github.com/droidrun/droidrun) | undated | 2026-11-14 | tinggi |
| [49] | Open-AutoGLM: butuh PC, khusus riset | [Open-AutoGLM (GitHub)](https://github.com/zai-org/Open-AutoGLM) | undated | 2026-11-14 | tinggi |
| [50] | OpenClaw di Termux; masalah proses dimatikan | [openclaw-android (GitHub)](https://github.com/AidanPark/openclaw-android) | undated (pin 2026.10) | 2026-11-14 | tinggi |
| [51] | DroidClaw (dgirard): FGS, cron, Telegram long-poll | [dgirard/droidclaw](https://github.com/dgirard/droidclaw) | undated | 2026-11-14 | tinggi |
| [52] | PocketClaw di F-Droid | [F-Droid](https://f-droid.org/packages/com.lord1egypt.pocketclaw/) | 2026-10-08 | 2026-11-14 | tinggi |
| [53] | PokeClaw: kontrol UI on-device, tanpa penjadwal | [PokeClaw (GitHub)](https://github.com/agents-io/PokeClaw) | 2026-05-26 | 2026-11-14 | tinggi |
| [54] | ZeroClaw-Android diarsipkan (keamanan/cakupan) | [natfii/zeroclaw-android](https://github.com/natfii/zeroclaw-android) | 2026-03-07 | 2026-11-14 | tinggi |
| [55] | Vibe/AgentLabs: APK OpenClaw on-device | [VibeBrowser blog](https://www.vibebrowser.app/blog/building-openclaw-native-android-ai-agent) | 2026-07-24 | 2026-11-14 | sedang |
| [56] | Hermes Agent: MIT, hosting VPS, dukungan Termux | [Nous Research (GitHub)](https://github.com/NousResearch/hermes-agent) | live | 2026-11-14 | tinggi (verified) |
| [57] | mobile-use: butuh host | [minitap-ai/mobile-use](https://github.com/minitap-ai/mobile-use) | live | 2026-11-14 | tinggi |
| [58] | Panda/Blurr: lisensi non-OSI, WIP | [Blurr (GitHub)](https://github.com/Ayush0Chaudhary/blurr) | undated | 2026-11-14 | tinggi |
| [60] | Mistral Experiment: data untuk training | [Mistral Help](https://help.mistral.ai/en/articles/450104-how-can-i-try-the-api-for-free-with-the-experiment-plan) | undated | 2026-11-14 | sedang |
| [61] | Batas Groq Developer plan | [Groq docs](https://console.groq.com/docs/rate-limits) | undated | 2026-11-14 | sedang |

## Peta kedaluwarsa

Dihitung oleh `recon_kit.py staleness` dari ledger klaim. Batas kesegaran: versi/kompatibilitas 1 bulan; kebijakan, harga/kuota, dan peta lanskap 3 bulan; ekosistem, kapabilitas, dan kegagalan 6 bulan. Untuk dokumentasi resmi tanpa tanggal, tanggal terbitnya dihitung sebagai tanggal akses.

| Klaim | Kelas | Cek ulang | Status |
|---|---|---|---|
| [1] Timeout dataSync, [2] kuota Android 16 | version-compat | 2026-12-01 | segar |
| [44] Langkah per tugas | capability | 2026-12-01 | segar |
| [46] Kemampuan Operit | landscape | 2026-12-01 | segar |
| [41] GitHub Models, [52] PocketClaw | ecosystem / landscape | 2027-01-01 | segar |
| [18] [4] Kebijakan Play, [34] [37] kuota gratis | policy / pricing-limits | 2027-02-01 | segar |
| [33] TypeAssist di F-Droid | ecosystem | 2027-02-01 | segar |
| [15] Claude Code di Termux | failure | 2027-04-01 | segar |
| [6] OEM killer, [7] pengecualian FGS | failure / capability | 2027-05-01 | segar |
| [11] W^X Android 10 | version-compat | 2024-06-01 | **kedaluwarsa menurut tanggal sumber**; aturan dasarnya masih berlaku [16] |
| [24] AAPM mencabut Accessibility | version-compat | 2026-04-01 | **kedaluwarsa**, dan belum terverifikasi |
| [53] PokeClaw, [54] ZeroClaw | landscape | 2026-08 / 2026-06 | **kedaluwarsa**: cek rilis terbaru |
| [13] OpenCode tanpa build Android | failure | 2026-08-01 | **kedaluwarsa**: cek apakah build Android sudah ada |
| [29] FAQ verifikasi developer, [40] Cerebras | policy / pricing-limits | 2026-10-01 | **kedaluwarsa**: tinjau ulang sebelum memutuskan distribusi |

Cek ulang paling awal: **[11]** (aturan W^X, sumber 2024). Yang paling mendesak untuk keputusan adalah **[29]** (verifikasi developer) dan **[24]** (Advanced Protection di Android 17).
