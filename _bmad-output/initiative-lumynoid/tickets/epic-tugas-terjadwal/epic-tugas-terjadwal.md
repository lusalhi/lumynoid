---
type: epic
title: "Tugas terjadwal jalan tepat waktu saat app tertutup"
parent: initiative-lumynoid
covers: [R6]
sources: []
companions: []
after: []
refined: false
risk: high
---

# Tugas terjadwal jalan tepat waktu saat app tertutup

## Description

Pengguna membuat tugas sekali atau berulang lewat chat atau layar tugas terjadwal. Exact alarm membangunkan app dan memanggil task runner dari epic-agent-shell tanpa mengubah loop-nya; hasilnya tampil sebagai notifikasi dan di percakapan. Onboarding pengecualian baterai dan panduan autostart per merek HP membuat tugas bertahan di HP yang agresif mematikan app.

## Outcome

Pengguna bisa mengandalkan asisten untuk menjalankan tugas pada waktunya, walau app tertutup dan layar mati.

## Done when

- Tugas sekali dan berulang bisa dibuat lewat chat dan lewat layar tugas terjadwal, dan tampil di layar itu.
- Tugas harian berjalan tepat waktu dengan app tertutup dan layar mati setiap hari selama 7 hari, di satu HP Xiaomi dan satu HP Samsung yang disiapkan lewat panduan di app.
- Hasil tugas muncul sebagai notifikasi dan di percakapan.
- Onboarding pengecualian optimasi baterai dan panduan autostart per merek tersedia di app.
- Dirilis di GitHub Releases.

## Boundaries

- Bukan di sini: loop agent dan task runner (epic-agent-shell); menerima pesan masuk saat app tertutup (Telegram, v1.x).

## References

- parent — `_bmad-output/initiative-lumynoid/initiative-lumynoid.md`, Requirements R6; Constraints; Notes
- breakdown — `_bmad-output/initiative-lumynoid/tickets/tickets.toml`, epic `epic-tugas-terjadwal`
