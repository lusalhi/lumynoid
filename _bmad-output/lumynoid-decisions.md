---
title: 'Lumynoid — keputusan produk & arsitektur awal'
type: decisions
created: '2026-11-14'
sources:
  - research-lumynoid-android-agent/research-lumynoid-android-agent.md
status: input for initiative
---

# Lumynoid — keputusan awal (sesi 2026-11-14)

Catatan ini merekam keputusan pemilik produk dari diskusi setelah riset. Rujukan [n] menunjuk ke lampiran sumber di `research-lumynoid-android-agent/research-lumynoid-android-agent.md`. Temuan baru dari diskusi diberi tautan langsung.

## Visi

Lumynoid adalah app Android **open source dan gratis** untuk **orang awam**: install, isi endpoint AI sendiri, langsung punya asisten AI pribadi yang selalu bisa dihubungi. Pengguna tidak perlu langganan bot, tidak perlu VPS, tidak perlu setup teknis.

## Keputusan

| # | Keputusan | Alasan / catatan |
|---|---|---|
| K1 | Target pengguna **orang awam**, sehingga **tidak ada terminal yang terlihat** di UI | Kesederhanaan adalah nilai utama |
| K2 | Kemampuan inti v1: **chat**, **shell tersembunyi yang dikendalikan agent (termasuk coding)**, **browsing** (cari dan baca web, bukan mengendalikan Chrome), **tugas terjadwal** | "Browser" berarti browsing |
| K3 | **Tidak** mengendalikan app lain; **tidak memakai Accessibility** | Menghindari larangan Play soal Accessibility otonom [18][19][20] |
| K4 | **Harness agent dibuat sendiri, native** (otak di Kotlin). Linux userland (proot) **dinyalakan hanya saat tool shell dipakai** | Harness yang sudah ada mengandalkan proses yang terus hidup, rawan dimatikan Android [3][10][50]. `npm install` dan sejenisnya berjalan di userland |
| K5 | Harness **mendukung format Agent Skills (`SKILL.md`)** supaya skill pihak ketiga bisa dipasang | Contoh: Binance Agentic Wallet berupa CLI npm `baw` + `SKILL.md`. Login lewat kode pairing + konfirmasi di Binance App; transaksi lewat `contract-call preview/execute` dengan konfirmasi pengguna |
| K6 | **Chat di dalam app** untuk v1; Telegram menyusul di v1.x sebagai opsi lanjutan | Telegram mengharuskan pengguna membuat bot dan butuh koneksi yang terus hidup [51]. Chat di dalam app memungkinkan desain berbasis kejadian (alarm + notifikasi + FGS hanya saat ada tugas) [7][8] |
| K7 | **MVP: API key milik pengguna** (OpenAI-compatible + preset populer + custom; Anthropic API key) | Login memakai langganan Claude **dilarang** untuk app pihak ketiga ([Anthropic legal](https://code.claude.com/docs/en/legal-and-compliance)). "Sign in with ChatGPT" punya jalur resmi untuk app open source / locally hosted lewat Responses API ([OpenAI](https://developers.openai.com/siwc/token-sharing-open-source)): **kandidat pasca-MVP**, kelayakan app Android perlu dipastikan. Model gratis OpenCode Zen hanya bisa dipakai lewat OpenCode [37] |
| K8 | Stack: **Kotlin + Jetpack Compose** | Bagian tersulit (FGS, alarm, userland) native Android; iOS tidak memungkinkan shell/agent latar belakang, jadi keuntungan lintas platform kecil |
| K9 | UI chat MVP dengan **komponen tetap**: streaming + markdown, blok kode, kartu aktivitas tool, kartu konfirmasi, preview hasil (WebView/file), status kuota/provider, daftar percakapan, layar tugas terjadwal | **Kartu konfirmasi dan aktivitas tool dibuat oleh app, bukan oleh LLM**, supaya tidak bisa dipalsukan. GenUI/A2UI (`androidx.a2ui`, alpha) menyusul di v1.x dengan fallback teks; model gratis lemah di output terstruktur [35][36] |
| K10 | **Keamanan sejak awal**: shell disandbox di folder app, konfirmasi untuk aksi merusak, aksi keuangan, atau pengiriman data keluar, log aksi yang bisa dilihat pengguna, konten web diperlakukan sebagai data | ZeroClaw diarsipkan karena alasan keamanan [54] |
| K11 | Desain **hemat kuota**: rotasi provider/key, filter model yang mendukung tools, pemangkasan konteks, penghitung kuota | OpenRouter gratis 50 request/hari, padahal satu tugas butuh sekitar 10–20 panggilan [34][44] |
| K12 | Bangun **app baru**, bukan fork Operit; Operit (LGPL) dipakai sebagai referensi teknis | Operit menyasar power user [46][47] |

## Masih terbuka

- **Jalur distribusi**: GitHub + F-Droid sudah pasti. **Play Store mungkin terbuka lagi** karena v1 tidak memakai Accessibility. Yang belum pasti: kebijakan FGS untuk tugas agent [4][5], dan apakah shell (binary dibundel + `npm install` saat runtime) lolos aturan larangan unduh kode native [4][11]. Perlu diriset.
- Persiapan verifikasi developer 2027 (akun penuh vs advanced flow; jalur open-source registry) [28][29][30].
- Layanan pencarian web untuk tool "cari" (gratis atau murah): belum diriset.
- Kelayakan "Sign in with ChatGPT" untuk app Android.
- Ukuran userland dan strategi unduhnya; batas `npm install` di HP sungguhan (phantom process killer, memori Android 17) [3][10].
- Library agent Kotlin/JVM (mis. Koog, LangChain4j): belum dievaluasi.
- Apakah Advanced Protection di Android 17 memblokir Accessibility [24]: tidak relevan untuk v1 (K3), dicatat untuk masa depan.

## Urutan berikutnya

1. Sofia (`bmad-agent-po`) menulis initiative dari riset + catatan ini.
2. `bmad-ux`: desain chat (DESIGN.md / EXPERIENCE.md).
3. `bmad-architecture`: harness, provider adapter, sandbox userland, penjadwal, jalur distribusi.

## Keputusan lanjutan

### K13 — Projects di v1

2026-11-22 — Pemilik produk menyetujui replan setelah membahas struktur **Project → Conversation**. Satu proyek dapat memiliki beberapa percakapan yang berbagi file, paket lokal, dan instruksi proyek, tanpa berbagi riwayat atau log chat. Chat biasa tetap tersedia tanpa proyek. Setelah konfirmasi, hapus chat menyisakan file proyek; hapus proyek membersihkan data milik proyek saja. Upgrade mempertahankan data yang sudah ada. Projects tidak menambah cakupan uji APK Epic 2 di Story 2.12.
