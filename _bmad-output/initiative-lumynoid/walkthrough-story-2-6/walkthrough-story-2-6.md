# Walkthrough story 2.6

Commit `87201715da712b2b6ab76ac64c0634e0a504b74f`

Blok aktif **2 · Gambaran umum**. Perubahan selama review **0**.

## 1. Intent

- [x] Selesai ditinjau

> Catat ToolCallRejected dan LlmRetry, tangani pemulihan crash dari event retry, dan coba tepat satu pesan perbaikan sebelum MALFORMED_TOOL_CALL. Terapkan MAX_LLM_CALLS default 20 yang bisa diatur pengguna dan akhir BUDGET_EXHAUSTED. Retry kuota tetap di luar scope; event audit masuk log 2.9.

Deskripsi asli [tiket 2.6](../tickets/epic-agent-shell/tickets/tickets.toml#L60).

## 2. Gambaran umum

- [ ] Sedang ditinjau

- Pengguna mengubah batas panggilan di layar AI providers. [Kontrol batas](../../../app/src/main/kotlin/dev/lusalhi/lumynoid/app/ProviderScreens.kt#L143)
- `AppGraph` menghubungkan pengaturan yang tersimpan ke runner. [Perakitan runner](../../../app/src/main/kotlin/dev/lusalhi/lumynoid/app/LumynoidApp.kt#L37)
- Runner menentukan kapan memanggil model, mencoba perbaikan, atau mengakhiri tugas. [Loop tugas](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt#L446)
- Repository mengenali tugas yang belum selesai dari event terakhir. [Pemulihan penyimpanan](../../../data/src/main/kotlin/dev/lusalhi/lumynoid/data/Repositories.kt#L73)
- Chat menerjemahkan alasan akhir tugas menjadi pesan pengguna. [Pesan chat](../../../app/src/main/kotlin/dev/lusalhi/lumynoid/app/Screens.kt#L184)

## 3. Retry dan batas panggilan saat menunggu persetujuan

- [ ] Belum dikunjungi

Runner mengambil batas ketika tugas dikirim dan menghitung setiap panggilan model, termasuk retry. Batch rusak dibuang utuh. Jika jatah masih ada, runner menambahkan satu instruksi perbaikan hanya pada permintaan berikutnya. Jika tugas berhenti sejenak untuk meminta persetujuan, tugas yang sama membawa hitungan dan status retry saat dilanjutkan.

- Batas diambil saat tugas diterima. [Pengiriman tugas](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt#L149)
- Runner memutuskan kapan memperbaiki respons dan kapan berhenti. [Pemeriksaan batas dan retry](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt#L460)
- Kedua adapter mengenali fragmen argumen yang salah tipe. [OpenAI-compatible](../../../llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/OpenAiCompatibleAdapter.kt#L87) · [Anthropic](../../../llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/AnthropicAdapter.kt#L147)
- Skenario persetujuan mencakup batas yang berubah dan retry yang sudah terpakai. [Fixture persetujuan](../../../core/src/test/kotlin/dev/lusalhi/lumynoid/core/TaskRunnerTest.kt#L352)

## 4. Event audit dan pemulihan setelah restart

- [ ] Belum dikunjungi

`ToolCallRejected` mencatat nomor panggilan yang ditolak. `LlmRetry` menggambarkan percobaan berikutnya dan disimpan sebelum model dipanggil lagi. Jika proses berhenti pada salah satu event itu, runner menutup tugas sebagai `INTERRUPTED` tanpa memanggil model atau tool lagi.

- Kedua event termasuk penanda tugas yang masih terbuka. [Jenis event](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskEvent.kt#L60) · [Isi event audit](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskEvent.kt#L118)
- Runner mengonfirmasi penulisan audit sebelum retry. [Penulisan retry](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt#L474)
- Jalur pemulihan membaca event terakhir dan menutup tugas. [Repository](../../../data/src/main/kotlin/dev/lusalhi/lumynoid/data/Repositories.kt#L73) · [Pemulihan runner](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt#L120) · [Fixture restart](../../../data/src/test/kotlin/dev/lusalhi/lumynoid/data/PersistenceTest.kt#L197)

## 5. Pengaturan dan pesan chat

- [ ] Belum dikunjungi

Layar AI providers menerima bilangan bulat positif dan memuat ulang nilai tersimpan saat dibuka. Nilai tersimpan berlaku untuk tugas baru. Chat menampilkan pesan berbeda untuk tool call yang tetap rusak dan jatah panggilan yang habis.

- Model tampilan memvalidasi draf dan memuat nilai tersimpan. [Validasi dan penyimpanan](../../../app/src/main/kotlin/dev/lusalhi/lumynoid/app/ProvidersViewModel.kt#L66)
- Preferensi aplikasi menyimpan nilai dan menggunakan default 20. [Default](../../../core/src/main/kotlin/dev/lusalhi/lumynoid/core/Ports.kt#L140) · [Preferensi](../../../data/src/main/kotlin/dev/lusalhi/lumynoid/data/LumynoidData.kt#L43)
- Kedua alasan kegagalan punya teks masing-masing. [Teks antarmuka](../../../app/src/main/res/values/strings.xml#L41)

## 6. Referensi pendukung

- [ ] Belum dikunjungi

- [AD-26](../architecture-lumynoid/architecture-lumynoid.md#L319) · Aturan jumlah panggilan model dan perbaikan tool call.
- [Rencana story 2.6](../tickets/epic-agent-shell/tickets/story-tool-call-rusak-dan-budget-punya-akhir-yang-jelas-plan.md) · Cakupan dan kriteria penerimaan.
