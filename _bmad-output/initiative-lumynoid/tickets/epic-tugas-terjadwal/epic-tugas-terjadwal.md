---
type: epic
title: "Tugas terjadwal jalan tepat waktu saat app tertutup"
parent: initiative-lumynoid
covers: [R6]
sources: []
companions:
  - _bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md
after: []
refined: false
risk: high
---

# Tugas terjadwal jalan tepat waktu saat app tertutup

## Description

Pengguna membuat tugas sekali atau berulang lewat chat atau layar tugas terjadwal. Exact alarm membangunkan app dan memanggil TaskRunner (kerangka dari epic-chat-dan-provider, loop tool dan gerbang dari epic-agent-shell) tanpa mengubah loop-nya; hasilnya tampil sebagai notifikasi dan di percakapan. Onboarding pengecualian baterai dan panduan autostart per merek HP membuat tugas bertahan di HP yang agresif mematikan app.

## Outcome

Pengguna bisa mengandalkan asisten untuk menjalankan tugas pada waktunya, walau app tertutup dan layar mati.

## Done when

- Tugas sekali dan berulang bisa dibuat lewat chat dan lewat layar tugas terjadwal, dan tampil di layar itu.
- Tugas harian berjalan tepat waktu dengan app tertutup dan layar mati setiap hari selama 7 hari, di satu HP Xiaomi dan satu HP Samsung yang disiapkan lewat panduan di app.
- Hasil tugas muncul sebagai notifikasi dan di percakapan.
- Onboarding pengecualian optimasi baterai dan panduan autostart per merek tersedia di app.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: kerangka TaskRunner dan AgentService (epic-chat-dan-provider); loop tool call dan gerbang konfirmasi (epic-agent-shell); menerima pesan masuk saat app tertutup (Telegram, v1.x).

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R6; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-tugas-terjadwal`
- architecture — `_bmad-output/initiative-lumynoid/architecture-lumynoid/architecture-lumynoid.md`, AD-2, AD-3, AD-5, AD-6, AD-7 (PERSISTENT), AD-10, AD-16, AD-22, AD-23, AD-24; Deferred (onboarding baterai dan izin)

## Notes

- Decision: 2026-11-14 — Replan (AD-2): TaskRunner yang dipanggil alarm kerangkanya dari epic-chat-dan-provider dan loop tool-nya dari epic-agent-shell; epic ini tetap menunggu epic-agent-shell karena `schedule_create` butuh registry tool dan gerbang konfirmasi.
- Decision: 2026-11-16 — Dari epic-chat-dan-provider: deklarasikan dan minta `POST_NOTIFICATIONS`, supaya notifikasi `task_running` FGS terlihat di Android 13+. Di HP pemilik notifikasi itu tersembunyi, dan Lumynoid tidak muncul di daftar app aktif sistem.
