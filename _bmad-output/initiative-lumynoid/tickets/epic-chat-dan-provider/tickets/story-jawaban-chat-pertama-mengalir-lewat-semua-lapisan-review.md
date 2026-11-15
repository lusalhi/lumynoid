# Review — First chat answer streams through every layer

Sumber: `story-jawaban-chat-pertama-mengalir-lewat-semua-lapisan-plan-quick.md`.

Review melalui inspeksi diff yang dibundel, tanpa menjalankan build atau tes. Lokasi mengacu pada kode dalam diff tersebut. Temuan yang beririsan antar-lensa dipertahankan.

## Adversarial

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:54-62` — **Kondisi:** `submit()` menulis task dan dua event di luar penanganan error; `ChatViewModel.send()` juga tidak menangkap kegagalannya. **Perbaikan:** kembalikan hasil submission bertipe, tangani kegagalan penyimpanan, dan jangan enqueue submission yang gagal. **Dampak:** kegagalan Room dapat menjatuhkan proses, bukan sekadar mengakhiri satu task.

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:41-46` — **Kondisi:** `cancel()` dapat masuk setelah pemeriksaan `cancelled.remove()` tetapi sebelum `running.value` diisi. **Perbaikan:** sinkronkan transisi dequeue–registrasi job dengan pembatalan. **Dampak:** pembatalan hilang; request LLM tetap berjalan dan entri pembatalan tertinggal. Temuan ini juga muncul pada Edge-Case Hunter.

- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/ProviderAdmin.kt:23-24` — **Kondisi:** `current()` menganggap adanya baris konfigurasi berarti key tersedia; `ChatViewModel` langsung menetapkan `keySaved=true`. **Perbaikan:** periksa ketersediaan secret di `:llm` dan kembalikan status konfigurasi yang mencerminkan key yang hilang. **Dampak:** Send tetap mengajukan task tanpa key, bertentangan dengan aturan “Missing config”; pengguna kemudian mendapat diagnosis `AuthFailed` yang keliru.

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/Screens.kt:159-160` — **Kondisi:** event `AssistantMessage` dapat teramati sebelum `TaskRunner.finally` membersihkan `live`. **Perbaikan:** jangan render bubble live jika event assistant untuk `live.taskId` sudah tersedia. **Dampak:** jawaban dapat tampil ganda sementara; kedua sumber state diperbarui secara terpisah.

- `.github/workflows/ci.yml:28-34` — **Kondisi:** pemeriksaan dependensi hanya membaca runtime classpath `:core`. Tidak ada pemeriksaan classpath aplikasi atau lisensi. **Perbaikan:** tambahkan pemeriksaan dependensi rilis yang sudah di-resolve, termasuk transitif, terhadap grup terlarang dan lisensi OSI sesuai AD-17. **Dampak:** kewajiban CI pada architecture spine belum ditegakkan.

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:83` — **Kondisi:** `LlmChunk.Routed` dibuang menjadi `Unit`; `TaskEvent` juga tidak menyediakan `ProviderSwitched`. **Perbaikan:** persist pilihan provider/model sebagai event bertipe ketika menerima `Routed`. **Dampak:** event log tidak merekam provider/model yang menghasilkan jawaban, meskipun AD-12 mewajibkannya.

- `data/src/main/kotlin/dev/lusalhi/lumynoid/data/KeystoreSecretStore.kt:25-38` — **Kondisi:** `writeBytes()` menimpa file secret langsung; penghentian proses saat penulisan dapat meninggalkan ciphertext terpotong. **Perbaikan:** gunakan penulisan atomik, misalnya `AtomicFile`, dan pertahankan ciphertext lama sampai penulisan baru berhasil. **Dampak:** pembaruan key dapat merusak key yang sebelumnya masih valid. Edge-Case Hunter menemukan jalur pembacaannya yang tidak tertangani.

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/ChatViewModel.kt:114` — **Kondisi:** dua `send()` pertama dapat sama-sama membaca `conversationId == null` sebelum `conversations.create()` selesai. **Perbaikan:** serialisasikan pembuatan conversation atau siapkan satu conversation sebelum menerima input. **Dampak:** pesan terpisah ke dua conversation; UI hanya mengamati salah satunya. Temuan ini juga muncul pada Edge-Case Hunter.

- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/ProviderAdmin.kt:26-33` — **Kondisi:** URL seperti `openrouter.ai/api/v1` lolos validasi karena hanya diperiksa tidak kosong. **Perbaikan:** validasi URL absolut beserta scheme/host dan tampilkan kesalahan pada field URL. **Dampak:** konfigurasi tidak valid tersimpan; kegagalan lokal kemudian ditampilkan sebagai error server provider.

- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/OpenAiCompatibleAdapter.kt:67-81` — **Kondisi:** EOF diperlakukan sama dengan `[DONE]`, tanpa memeriksa penanda penyelesaian. **Perbaikan:** bedakan EOF prematur dari terminasi protokol yang sah; emit kegagalan bila stream terputus sebelum selesai. **Dampak:** setelah menerima sebagian teks, koneksi yang terputus bersih dapat menghasilkan `AssistantMessage` dan `TaskFinished` untuk jawaban terpotong.

## Edge-Case Hunter

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:41-46` — **Kondisi:** pembatalan tiba di antara pemeriksaan antrean dan registrasi job aktif. **Perbaikan:** gunakan satu lock untuk transisi registrasi dan keputusan pembatalan. **Dampak:** task tetap berjalan setelah dibatalkan. Irisan dengan Adversarial.

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/ChatViewModel.kt:66-71` — **Kondisi:** service tidak tersambung sehingga cabang `emptyFlow()` tidak pernah memancarkan nilai. **Perbaikan:** gunakan `it?.live ?: flowOf(null)` dan tampilkan status koneksi gagal secara terpisah. **Dampak:** `combine` tidak menghasilkan state; layar tetap `LOADING`.

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/ChatViewModel.kt:110-117` — **Kondisi:** pengiriman kedua dimulai ketika pembuatan conversation pertama masih tertunda. **Perbaikan:** lindungi get-or-create conversation dengan mutex. **Dampak:** pesan pertama tersimpan pada conversation yang tidak lagi diamati. Irisan dengan Adversarial.

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/ChatViewModel.kt:98-117` — **Kondisi:** penyimpanan provider, pembuatan conversation, atau submission melempar exception. **Perbaikan:** konsumsi hasil port bertipe dan tampilkan error; tetap teruskan `CancellationException`. **Dampak:** exception coroutine tidak tertangani dapat menjatuhkan aplikasi. Irisan dengan temuan submission Adversarial.

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/ChatViewModel.kt:115` — **Kondisi:** service terputus setelah chat terbuka, lalu pengguna mengirim pesan. **Perbaikan:** tampilkan status unavailable dan pertahankan draft sampai submission dikonfirmasi. **Dampak:** `first()` menunggu tanpa batas sementara `Screens.kt:187` sudah mengosongkan draft.

- `data/src/main/kotlin/dev/lusalhi/lumynoid/data/KeystoreSecretStore.kt:35-40` — **Kondisi:** file kosong, panjang IV tidak valid, atau autentikasi ciphertext gagal. **Perbaikan:** validasi panjang record dan normalkan kegagalan pembacaan menjadi hasil secret bertipe. **Dampak:** exception melewati `SecretPort` dan berubah menjadi diagnosis server yang keliru.

- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/ProviderAdmin.kt:32-33` — **Kondisi:** penggantian secret berhasil tetapi penyimpanan konfigurasi gagal. **Perbaikan:** tulis key pengganti ke `SecretRef` baru, lalu ubah referensi konfigurasi setelah berhasil. **Dampak:** konfigurasi lama tetap aktif tetapi memakai key baru yang mungkin milik provider berbeda.

- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/OpenAiCompatibleAdapter.kt:73-76` — **Kondisi:** provider mengirim error rate-limit atau kuota melalui frame SSE. **Perbaikan:** decode kode error stream dan petakan ke varian `LlmError` yang sesuai. **Dampak:** semua error stream menjadi `Server`; informasi kuota dan pembatasan hilang.

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:95-105` — **Kondisi:** penulisan event gagal, lalu penulisan `TaskFailed` juga gagal. **Perbaikan:** publikasikan kegagalan persistensi melalui state sementara yang terlihat pengguna. **Dampak:** `runCatching` membuang kegagalan; teks live dibersihkan tanpa jawaban atau penjelasan tersimpan.

## Verification Gap

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:80` — **Kondisi:** tidak ada assertion terhadap nilai `live` saat stream masih berjalan. **Perbaikan:** tahan fake stream setelah delta pertama; assert `LiveAnswer.text`, lanjutkan delta berikutnya, lalu assert pertumbuhannya. **Dampak:** publikasi live dapat rusak tanpa tes gagal. **Consumer:** bubble streaming di `app/src/main/kotlin/dev/lusalhi/lumynoid/app/Screens.kt:160`. **Bukti:** `TaskRunnerTest.kt:55-77,150-167` hanya memeriksa `live == null` setelah eksekusi. **Gap:** `regression-gap`.

- `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:86` — **Kondisi:** cabang jawaban kosong tidak diuji. **Perbaikan:** berikan stream `Done` tanpa delta; assert `TaskFailed(SERVER)` dan tidak ada `AssistantMessage`. **Dampak:** jawaban kosong dapat berubah menjadi sukses tanpa tes gagal. **Consumer:** rendering `TaskFailed` di `app/src/main/kotlin/dev/lusalhi/lumynoid/app/Screens.kt:204`. **Bukti:** assertion `SERVER` pada `TaskRunnerTest.kt:128-147` berasal dari adapter yang melempar exception, bukan jawaban kosong. **Gap:** `regression-gap`.

- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/OpenAiCompatibleAdapter.kt:73` — **Kondisi:** tidak ada fixture error SSE setelah sebagian teks diterima. **Perbaikan:** kirim delta lalu frame `error`; assert urutan `TextDelta`, `Failed(Server)`, tanpa `Done`. **Dampak:** penghapusan pemeriksaan error stream membuat jawaban terpotong tercatat sukses. **Consumer:** `core/src/main/kotlin/dev/lusalhi/lumynoid/core/TaskRunner.kt:82`. **Bukti:** fixture pada `OpenAiCompatibleAdapterTest.kt` hanya mencakup komentar, delta, usage, `[DONE]`, dan error HTTP. **Gap:** `regression-gap`.

- `app/src/main/kotlin/dev/lusalhi/lumynoid/app/ChatViewModel.kt:37` — **Kondisi:** pengecualian key kosong ketika `keySaved=true` tidak diuji. **Perbaikan:** assert form lengkap dengan key kosong dan `keySaved=true`, tetapi tidak lengkap ketika `keySaved=false`. **Dampak:** perubahan gate dapat memaksa pengguna memasukkan ulang key setiap mengedit pengaturan. **Consumer:** `ChatViewModel.kt:94`. **Bukti:** pencarian tes tidak menemukan referensi `ProviderFormState` atau `keySaved`; tes `ProviderAdmin` tidak melewati gate form ini. **Gap:** `regression-gap`.

- `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/ProviderAdmin.kt:32` — **Kondisi:** normalisasi whitespace pada API key tidak diuji. **Perbaikan:** simpan input key dengan whitespace pembungkus dan assert secret yang disimpan sudah dinormalisasi. **Dampak:** key hasil paste dapat tersimpan tidak valid tanpa tes mendeteksinya. **Consumer:** pembacaan key di `llm/src/main/kotlin/dev/lusalhi/lumynoid/llm/LlmRouter.kt:25`. **Bukti:** `OpenAiCompatibleAdapterTest.kt:151-166` menguji trimming URL/model, tetapi key menggunakan konstanta yang sudah bersih. **Gap:** `regression-gap`.
