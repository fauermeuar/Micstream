# MicStream v1.0

Aplikasi Android sederhana: menangkap **mic + audio internal HP** (TTS, musik, dll),
mencampurnya, lalu mengirim via **SRT** ke OBS/VPS. OBS tinggal pasang Media Source
mode listener seperti biasa — tidak perlu Discord 2 akun lagi.

## Cara kerja

1. `MainActivity` meminta izin mic + screen capture (MediaProjection).
2. `StreamService` (foreground service):
   - `AudioRecord` mic (48 kHz mono)
   - `AudioRecord` + `AudioPlaybackCaptureConfiguration` untuk audio internal
     (USAGE_MEDIA / GAME / ASSISTANCE_ACCESSIBILITY — TTS Chrome masuk sini)
   - Kedua PCM dicampur (clamp anti-pecah), ditulis ke FIFO
   - `ffmpeg-kit` membaca FIFO, encode AAC 128k, publish
     `srt://HOST:PORT?latency=...` (MPEG-TS)
3. Di OBS (VPS): Media Source → `srt://0.0.0.0:8888?mode=listener`, Input Format `mpegts`.

## Build via GitHub Actions

Saya tidak menyertakan APK jadi — build sendiri gratis di GitHub:

1. Buat repo baru di GitHub (boleh private).
2. Upload **semua file** project ini ke repo (drag & drop di web GitHub bisa).
3. Buka tab **Actions** → workflow **Build APK** akan jalan otomatis
   (atau tekan **Run workflow**).
4. Tunggu sampai hijau → unduh **Artifacts → MicStream-debug-apk** → install di HP.

> APK debug bisa langsung diinstall (nanti ada peringatan "unknown source", wajar).

## Cara pakai

1. Di VPS: buka UDP port 8888 (Windows Firewall + panel provider),
   pasang Media Source SRT listener di OBS.
2. Di HP: buka MicStream → isi IP VPS, port, latency → **START STREAMING**.
3. Setujui dialog **screen capture** (wajib tiap mulai — syarat Android
   untuk mengambil audio internal).
4. Jangan swipe-tutup aplikasi saat streaming (minimize biasa aman).
   Matikan battery optimization untuk aplikasi ini biar tidak dimatikan sistem.

## FFmpeg & SRT

`com.arthenica:ffmpeg-kit-*` sudah dihapus dari Maven Central (proyek asli di-retire
April 2025), jadi project ini memakai fork terpelihara
`dev.ffmpegkit-maintained:ffmpeg-kit-full` (API & package sama).
Saat START, app mengecek apakah build FFmpeg punya protokol `srt`; kalau tidak,
status akan menampilkan pesan error. Solusinya: pakai build FFmpeg yang menyertakan
libsrt (mis. .aar sendiri di `app/libs/`).

## Catatan Android 14+

Service memakai tipe `mediaProjection|microphone` + izin `FOREGROUND_SERVICE_MICROPHONE`;
tanpa itu mic diam/crash di targetSdk 34.

## Batasan

- Android 10+ (API 29) ke atas.
- Tidak semua aplikasi bisa ditangkap audio internalnya (yang memblokir
  eksplisit / konten DRM). TTS Chrome umumnya bisa.
- Latency internet wajar 1–2 detik; naikkan nilai latency kalau putus-putus.
- v1.0 belum ada auto-reconnect; kalau putus, tekan STOP lalu START lagi.
