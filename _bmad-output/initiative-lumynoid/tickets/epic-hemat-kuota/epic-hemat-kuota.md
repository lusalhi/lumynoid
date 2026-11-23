---
type: epic
title: "Tugas tetap selesai dengan kuota API gratis"
parent: initiative-lumynoid
covers: [R12]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: true
risk: medium
---

# Tugas tetap selesai dengan kuota API gratis

## Description

Rotasi key/provider saat kena limit, filter model yang mendukung tools, dan penghitung kuota. Epic ini juga memiliki penyusunan konteks: pemangkasan konteks dengan titik sambung yang stabil untuk memotong output tool panjang (dipakai epic-browsing-web) dan memuat instruksi skill tanpa terpangkas (dipakai epic-skill-pihak-ketiga).

## Outcome

Pengguna dengan key gratis tetap bisa menyelesaikan beberapa tugas sehari dan tahu sisa kuotanya.

## Requirements

- R1 (parent R12): Pengguna menyusun urutan cadangan provider beserta model untuk tiap provider. Saat provider yang dipakai kena limit atau kuotanya habis sebelum jawaban mulai, tugas berlanjut di provider berikutnya dan chat menampilkan perpindahan itu.
- R2 (parent R12): Request yang membawa tool hanya dikirim ke model yang mendukung tools. Model yang ditolak provider karena tools ditandai dan tidak dipilih lagi untuk request bertool.
- R3 (parent R12): Layar provider menampilkan status tiap provider (siap, kena limit sampai jam tertentu, kuota habis, key ditolak) dan sisa kuota bila provider menyediakannya.
- R4 (parent R12; R13 lewat AD-27): Saat riwayat melebihi batas konteks model, giliran lama dipangkas utuh. Instruksi proyek tetap terkirim, riwayat chat lain tidak masuk, dan log aksi tetap menyimpan output lengkap.
- R5 (parent R12): Output tool yang panjang dipotong di konteks menurut petunjuk pemotongan tool-nya. Titik sambung untuk output tool web (Epic 5) dan instruksi skill yang tidak dipangkas (Epic 6) terdokumentasi.

## Constraints

- C1 (parent C11; AD-12, AD-26): Rotasi dan filter model berada di `LlmRouter` dan hanya berpindah sebelum delta pertama. Signature `TaskRunner.submit` dan `TaskRequest` tidak berubah (Decision 2026-11-23); `MAX_LLM_CALLS` tetap berlaku lintas rotasi.
- C2 (AD-4, AD-23): `RouterState` adalah satu-satunya sumber status dan kuota, disimpan di memori saja, dan tidak masuk event log.
- C3 (AD-13, AD-27): Unit pemangkasan adalah giliran utuh beserta pasangan tool call dan hasilnya. Instruksi proyek berkelas `Pinned`; event log tidak pernah dipangkas.
- C4 (AD-15): Key hanya dipakai adapter dan pengambilan kuota lewat `SecretPort`; tidak tampil di UI, log, atau event.
- C5 (AD-19): Kolom baru masuk lewat migrasi Room eksplisit dari versi 7, dikoordinasikan dengan Epic 3 (Decision 2026-11-23).

## Done when

- Dengan dua key/provider gratis terkonfigurasi, tugas tetap selesai saat key pertama kena limit.
- Request bertool tidak dikirim lagi ke model yang diketahui tidak mendukung tools.
- Sisa kuota dan status provider terlihat di app.
- Titik sambung penyusunan konteks untuk output tool panjang dan instruksi skill tersedia dan didokumentasikan untuk epic 5 dan 6.
- Saat konteks dipangkas, instruksi proyek tetap tersedia dan riwayat chat lain tidak masuk request.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: menambah dan mengetes provider (epic-chat-dan-provider); penanganan tool call kosong/rusak dan budget panggilan per tugas (epic-agent-shell, AD-26); model lokal untuk routing (di luar v1).
- Touch point milik epic ini: endpoint sisa kuota provider (mis. OpenRouter).

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R12, R13; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-hemat-kuota`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-4, AD-11, AD-12, AD-13, AD-15, AD-19, AD-23, AD-26, AD-27 (snapshot instruksi proyek dan batas riwayat); Deferred (rotasi, cooldown, sumber kuota)

## Notes

- Decision: 2026-11-14 — Penanganan tool call kosong/rusak pindah ke epic-agent-shell (AD-26: ditegakkan runner); epic ini tetap memegang retry karena limit dan kuota lewat router.
- Decision: 2026-11-23 — Refinement disetujui pemilik. Satu key adalah satu entri provider; pengguna mengurutkan cadangan dan memilih satu model per provider. `RouterState` disimpan di memori saja; setelah restart provider yang tadinya kena limit dicoba lagi lalu dirotasi. Sisa kuota diambil dari endpoint `/api/v1/key` OpenRouter dan dari header rate-limit provider yang mengirimnya; provider tanpa keduanya hanya menampilkan status. Model yang dukungan tool-nya belum diketahui dianggap mendukung sampai provider menolak. Batas konteks memakai `context_length` OpenRouter, atau 32k token bila tidak ada, dengan estimasi jumlah karakter dibagi 4 dan cadangan 20%.
- Decision: 2026-11-23 — Ditunda, bukan v1: banyak key dalam satu entri provider, menyimpan cooldown ke DB, dan UI pengaturan batas konteks.
- Decision: 2026-11-23 — Breakdown 4.1–4.7 disetujui. Tracer 4.1: dua provider dalam urutan cadangan, provider pertama mengembalikan limit, tugas selesai di provider kedua, dan chat menampilkan perpindahan. 4.1 memiliki seluruh migrasi Epic 4. Setelah 4.2, 4.3 (status dan kuota) dan 4.4→4.5 (pemangkasan) paralel. Refactor sweep 4.6 mendahului uji APK 4.7, yang boleh memakai APK yang sama dengan 3.9.
- Decision: 2026-11-23 — Rantai rotasi dimulai dari model aktif, lalu provider lain menurut urutan cadangan. `ProviderSwitched` dicatat hanya bila provider yang menjawab berbeda dari awal rantai; baris chat menampilkan asal, tujuan, dan alasannya. Pemangkasan berlaku pada setiap panggilan LLM dalam tugas, termasuk setelah putaran tool dan resume approval.
- Decision: 2026-11-23 — Validasi breakdown: budget konteks memakai batas terkecil di rantai rotasi. Cooldown 429 memakai `Retry-After` atau 60 detik. 402 dan 401 juga merotasi, dan provider itu dilewati sampai app restart atau konfigurasinya diubah. Semua provider habis berakhir dengan alasan gagal baru yang terlihat, juga di notifikasi 3.5. Bila tidak ada model pendukung tools di rantai, router mengirim tanpa tool dan chat memberi tahu; 3.9 dan 4.7 satu rilis karena fallback ini.
- Decision: 2026-11-23 — Tanpa run `bmad-ux`: urutan cadangan, status provider, dan baris perpindahan memakai komponen Monochrome yang sudah ada.
- Decision: 2026-11-23 — Epic 3 dan Epic 4 berbagi `TaskRunner.kt`, `Ports.kt`, `TaskEvent.kt`, `LumynoidApp.kt`, fixture `ProjectFixtureProvider`, enum `Screen`, dan `strings.xml`; tabrakan diselesaikan saat merge.
- Decision: 2026-11-23 — Epic 4 dikerjakan paralel dengan Epic 3. Migrasi Room mulai dari versi 7: lane yang merge lebih dulu memakai versi berikutnya, lane lain rebase dan menomori ulang migrasinya sebelum merge. Epic ini tidak mengubah signature `TaskRunner.submit`, titik yang dipanggil alarm Epic 3. Rilis kedua epic boleh digabung dalam satu APK.
- Decision: 2026-11-22 — Pemangkasan menggunakan sumber instruksi proyek dan batas riwayat per percakapan yang ditetapkan Epic 8, tanpa mendefinisikan ulang kepemilikan konteks.
