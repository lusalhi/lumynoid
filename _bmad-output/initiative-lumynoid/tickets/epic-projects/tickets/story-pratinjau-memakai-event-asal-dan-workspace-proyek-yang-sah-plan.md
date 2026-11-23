---
title: 'Pratinjau memakai event asal dan workspace proyek yang sah'
type: feature
ticket: 3
created: '2026-11-22'
status: done
baseline_revision: 'ed6bdf2f0d431691552e022a1155413be9ff495a'
route: oneshot
route_source: auto
risk: high
review: quick
review_source: pinned
lenses_ran: []
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Preview teks hanya memeriksa publikasi pada daftar event UI lalu membaca path yang diterima dari UI. HTML memeriksa ulang event persisten di prosesnya sendiri. AD-18 mewajibkan kedua jalur memverifikasi event asal dan mengambil workspace dari keanggotaan yang tersimpan.

**Approach:** Gunakan satu resolver AppGraph untuk membaca ToolResult berdasarkan ID percakapan dan ID event, memilih artefak berdasarkan indeks, dan membuka workspace hanya lewat `ConversationPort.workspace`. Modal teks memeriksa kecocokan path dan tipe yang ditampilkan sebelum membaca. Activity HTML hanya menerima pelacak event dan indeks, memilih file persisten sendiri, dan tetap berjalan di proses offline. Gunakan pembaca path terbatas Epic 2 tanpa perubahan. Percakapan yang masih menunggu adopsi, chat biasa, percakapan hilang, atau scope yang tidak dapat di-resolve harus tidak menampilkan file. Story 8.6 memiliki penandaan penghapusan proyek; resolver preview harus bergantung pada keputusan scope yang sama dengan TaskRunner, bukan menambahkan status kedua.

</frozen-after-approval>

## Implementation Notes

- AppGraph resolves the persisted result by conversation and event ID, then resolves the workspace from the same repository used by TaskRunner. Neither the text dialog nor the HTML intent supplies a workspace ID.
- The connected fixture seeds the persisted pending marker, runs the real filesystem adoption, and opens both file types from the same pre-adoption event. An unrelated workspace also contains `index.html`, so the wrong-conversation test detects a lookup that ignores event ownership.

Oneshot: satu batas otorisasi pada AppGraph, dua pemanggil preview, dan perluasan skenario UI preview yang sudah ada. Tidak perlu migrasi produksi baru.

## Plan Change Log

## Review Triage Log

- Quick inline review: no unresolved findings. Checked that the event query constrains both IDs, the stored artifact is selected before workspace resolution, text rejects an identity mismatch, and HTML still creates its WebView only after the existing socket checks. The common workspace resolver is the boundary that Story 8.6 must retain for `DELETING`.

## Verification

- `./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*ToolCardsTest' --tests '*ActionLogTest'` — kompilasi kedua APK dan verifikasi perutean artefak yang ada.
- Jalankan `PreviewNavigationTest` pada Android: event lama tidak dapat dibuka saat adopsi masih pending; event yang sama membuka teks dan HTML setelah adopsi. Event dari percakapan proyek lain dan target di luar workspace ditolak. Opt-in JavaScript dan sentinel jaringan tetap membuktikan preview offline.

**Host:** APK debug dan APK instrumentasi berhasil dibangun; 20 tes `ToolCardsTest` dan `ActionLogTest` lulus.

**Android:** `PreviewNavigationTest` lulus (1/1) pada Redroid arm64 API 34. Proses HTML menolak event sebelum adopsi; setelah adopsi, event yang sama membuka teks dan HTML. Pasangan event dan percakapan proyek lain serta file di luar workspace ditolak. JavaScript tetap opt-in dan sentinel loopback tidak menerima koneksi. Ini memakai fixture debug, bukan upgrade APK bertanda tangan pada HP (Story 8.8).
