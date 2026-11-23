---
type: epic
title: "Tugas terjadwal jalan tepat waktu saat app tertutup"
parent: initiative-lumynoid
covers: [R6]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: true
risk: high
---

# Tugas terjadwal jalan tepat waktu saat app tertutup

## Description

Pengguna membuat tugas sekali atau berulang lewat chat atau layar tugas terjadwal. Exact alarm membangunkan app dan memanggil TaskRunner (kerangka dari epic-chat-dan-provider, loop tool dan gerbang dari epic-agent-shell) tanpa mengubah loop-nya; hasilnya tampil sebagai notifikasi dan di percakapan. Onboarding pengecualian baterai dan panduan autostart per merek HP membuat tugas bertahan di HP yang agresif mematikan app.

## Outcome

Pengguna bisa mengandalkan asisten untuk menjalankan tugas pada waktunya, walau app tertutup dan layar mati.

## Requirements

- R1 (parent R6): Dari layar tugas terjadwal, pengguna membuat, mengubah, menonaktifkan, dan menghapus tugas sekali, harian, atau mingguan, serta memilih proyek tujuannya atau tanpa proyek. Daftar menampilkan setiap jadwal beserta waktu jalan berikutnya.
- R2 (parent R6): Dari chat, agent membuat, menampilkan, mengubah, dan menghapus jadwal. Pembuatan dan perubahan baru tersimpan setelah pengguna menyetujui kartu yang menampilkan teks tugas secara penuh. Jadwal dari chat proyek berjalan di proyek itu; dari chat biasa, tanpa proyek.
- R3 (parent R6): Tugas terjadwal berjalan tepat waktu saat app tertutup dan layar mati, tidak pernah dua kali untuk waktu jalan yang sama, dan tetap terjadwal setelah HP restart, app diperbarui, atau jam, zona waktu, atau izin alarm berubah. Jadwal yang terlewat mendapat paling banyak satu run susulan.
- R4 (parent R6): Hasil setiap run tampil di percakapan hasil milik jadwal itu dan sebagai notifikasi yang membuka percakapan tersebut. Run yang menunggu persetujuan, ditolak, atau gagal juga memberi tahu pengguna lewat notifikasi.
- R5 (parent R6; R13 lewat AD-27): Setelah proyek dihapus, jadwal proyek itu tidak berjalan lagi dan hilang dari daftar. Jadwal tidak bisa dibuat atau dipasang ulang untuk proyek yang sedang dihapus.
- R6 (parent R6): Saat jadwal pertama dibuat, app meminta izin notifikasi dan izin alarm tepat waktu. Selama izin alarm ditolak, jadwal tampil sebagai dijeda dengan jalan ke Pengaturan. App memandu pengecualian optimasi baterai dan pengaturan autostart per merek HP.

## Constraints

- C1 (parent C5; AD-2, AD-3, AD-16): Alarm hanya menyerahkan tugas ke `TaskRunner.submit` lewat AgentService dengan origin jadwal dan `runKey`. Scheduler tidak memanggil LLM dan tidak mengubah loop; run terjadwal mengantre bersama chat dengan satu tugas `RUNNING`.
- C2 (parent C9; AD-6, AD-7, AD-22): Run terjadwal memakai gerbang konfirmasi dan perilaku Decline yang sama dengan chat (Story 2.13), tanpa izin otomatis. Tugas berorigin jadwal tidak mendapat tool `schedule_*`.
- C3 (parent C3; AD-24): Izin baru hanya dari allowlist: `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, dan `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Tidak ada alarm tidak-tepat sebagai pengganti saat izin ditolak.
- C4 (AD-5, AD-16, AD-27): `ScheduledTask` hanya ditulis `:scheduler`. Scope workspace di-resolve lewat AD-27 saat tugas diterima. Pembatalan jadwal berjalan di gerbang penghapusan proyek sebelum cleanup; penghapusan proyek tidak selesai bila penonaktifan jadwal gagal.
- C5 (AD-19): Skema jadwal masuk lewat migrasi Room eksplisit dari versi 7, dikoordinasikan dengan Epic 4 (Decision 2026-11-23).

## Done when

- Tugas sekali dan berulang bisa dibuat lewat chat dan lewat layar tugas terjadwal, dan tampil di layar itu.
- Pada uji HP rilis epic, satu tugas terjadwal berjalan tepat waktu dengan app tertutup dan layar mati di HP pemilik (Samsung `SM-S731B`).
- Hasil tugas muncul sebagai notifikasi dan di percakapan.
- Jadwal dari chat proyek berjalan di workspace proyek asal dan hasilnya tetap di proyek itu. Setelah proyek dihapus, jadwal terkait tidak berjalan lagi.
- Onboarding pengecualian optimasi baterai dan panduan autostart per merek tersedia di app.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: kerangka TaskRunner dan AgentService (epic-chat-dan-provider); loop tool call dan gerbang konfirmasi (epic-agent-shell); menerima pesan masuk saat app tertutup (Telegram, v1.x).

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R6, R13; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-tugas-terjadwal`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-2, AD-3, AD-5, AD-6, AD-7 (PERSISTENT), AD-10, AD-16, AD-19, AD-22, AD-23, AD-24, AD-27 (target proyek dan penghapusan); Deferred (onboarding baterai dan izin)

## Notes

- Decision: 2026-11-14 — Replan (AD-2): TaskRunner yang dipanggil alarm kerangkanya dari epic-chat-dan-provider dan loop tool-nya dari epic-agent-shell; epic ini tetap menunggu epic-agent-shell karena `schedule_create` butuh registry tool dan gerbang konfirmasi.
- Decision: 2026-11-16 — Dari epic-chat-dan-provider: deklarasikan dan minta `POST_NOTIFICATIONS`, supaya notifikasi `task_running` FGS terlihat di Android 13+. Di HP pemilik notifikasi itu tersembunyi, dan Lumynoid tidak muncul di daftar app aktif sistem.
- Decision: 2026-11-22 — Tugas Terjadwal memakai identitas dan batas kepemilikan data dari Epic 8. Integrasi pembatalan jadwal saat proyek dihapus dimiliki epic ini, bukan syarat rilis Projects.
- Decision: 2026-11-23 — Pemilik menghapus uji daya tahan 7 hari di Xiaomi dan Samsung dari epic ini dan dari initiative. Pemilik menerima bahwa ketepatan waktu di HP yang agresif mematikan app (terutama Xiaomi) tidak dibuktikan di v1. Bukti tersisa hanya satu tugas tepat waktu dengan app tertutup pada uji HP rilis epic.
- Decision: 2026-11-23 — Epic 3 dan Epic 4 dikerjakan paralel. Migrasi Room mulai dari versi 7: lane yang merge lebih dulu memakai versi berikutnya, lane lain rebase dan menomori ulang migrasinya sebelum merge. Epic 4 tidak mengubah signature `TaskRunner.submit`. Rilis kedua epic boleh digabung dalam satu APK.
- Decision: 2026-11-23 — Refinement disetujui pemilik. Tugas terjadwal memakai model aktif global saat jadwal jalan; setelah Epic 4 model itu ikut rantai cadangan. Izin alarm tepat waktu yang ditolak membuat jadwal dijeda, tanpa alarm tidak-tepat. Panduan autostart berupa teks statis untuk Xiaomi/Redmi/POCO, Samsung, Oppo/Realme/OnePlus, Vivo, dan panduan umum, ditambah tombol ke halaman pengaturan bila tersedia, tanpa uji di HP merek lain. Tool `schedule_*` tidak membutuhkan workspace dan tersedia di chat biasa, sehingga request chat biasa juga membawa tool.
- Decision: 2026-11-23 — Breakdown 3.1–3.9 disetujui. Tracer 3.1: tugas sekali dibuat dari layar jadwal, berjalan saat app tertutup, dan hasilnya muncul di percakapan hasil serta notifikasi. Setelah tracer, 3.2 (jadwal berulang dan pemasangan ulang), 3.3 (layar), dan 3.5 (notifikasi persetujuan dan gagal) paralel; 3.4, 3.6, dan 3.7 menunggu 3.2 dan 3.3 karena berbagi port jadwal dan pemasangan ulang alarm. Refactor sweep 3.8 mendahului uji APK 3.9.
- Decision: 2026-11-23 — Epic 3 dan Epic 4 berbagi `TaskRunner.kt`, `Ports.kt`, `TaskEvent.kt`, `LumynoidApp.kt`, fixture `ProjectFixtureProvider`, enum `Screen`, dan `strings.xml`; tabrakan diselesaikan saat merge, migrasi mengikuti aturan versi di atas.
- Decision: 2026-11-23 — Validasi breakdown: tool `schedule_*` hanya menjangkau jadwal proyek chat saat ini, atau jadwal tanpa proyek dari chat biasa; `schedule_delete` berkelas `DESTRUCTIVE`; proyek jadwal tidak bisa diubah setelah dibuat. 3.7 menunggu 3.4 agar izin notifikasi dan alarm juga diminta pada jadwal pertama dari chat. 3.1 tidak butuh langkah pemilik (`hitl = false`). "App tertutup" berarti ditutup dari daftar app terbaru; Force stop membatalkan alarm dan bukan skenario uji.
- Decision: 2026-11-23 — 3.9 dan 4.7 wajib satu rilis APK: setelah 3.4 chat biasa selalu membawa tool `schedule_*`, sehingga butuh fallback tanpa tool dari 4.2 agar chat biasa tidak gagal pada model tanpa tools. 3.9 menunggu 4.2.
- Decision: 2026-11-23 — Tanpa run `bmad-ux`: layar jadwal, status dijeda, dan panduan autostart memakai komponen Monochrome dan pola layar provider serta Projects yang sudah ada.
- Decision: 2026-11-23 — Story 2.13 mendahului Epic 3. Epic ini memakai perilaku Decline dari TaskRunner bersama, bukan membuat kebijakan approval khusus penjadwal.
