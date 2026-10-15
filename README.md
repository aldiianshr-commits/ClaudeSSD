# CloudSSD

Cloud storage pribadi yang menjadikan **Xiaomi Pad 6** sebagai server file, dengan **SSD eksternal USB‑C** sebagai penyimpanan. File diunggah, diunduh, dan dikelola lewat browser dari HP, laptop, atau tablet lain.

- Aplikasi Android native (Kotlin), tanpa root, tanpa AndroidX.
- Akses SSD lewat **Storage Access Framework** (izin resmi Android).
- Server HTTP sendiri di dalam aplikasi (foreground service + notifikasi tetap).
- Antarmuka web responsif, mode gelap (hitam/abu-abu/putih, aksen biru).
- Default aman: server mati, hanya bisa diakses dari tablet sendiri, tidak ada akun bawaan.

> **Status jujur:** modul inti (server, API, keamanan, web UI) sudah dikompilasi dan **39 tes otomatis lulus**, ditambah uji end-to-end di Chromium. Modul Android sudah **dicek tipenya terhadap `android.jar` API 35**, tetapi **APK belum dibangun dengan Gradle dan belum diuji di Xiaomi Pad 6 fisik** — lingkungan pembuatan tidak memiliki akses ke repositori Google/Maven. Lihat [docs/PENGUJIAN.md](docs/PENGUJIAN.md).

---

## Struktur proyek

```
CloudSSD/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradle/wrapper/
├── core/                                  ← Kotlin/JVM murni (tanpa API Android), dapat diuji di PC
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/id/cloudssd/core/
│       │   ├── http/      HttpServer.kt, HttpMessages.kt      ← server HTTP/1.1 streaming, keep-alive, TLS
│       │   ├── api/       Api.kt, FileResponses.kt, Mime.kt   ← seluruh endpoint REST + Range + file statis
│       │   ├── auth/      Crypto.kt, UserStore.kt, Sessions.kt← PBKDF2, sesi, pembatas login
│       │   ├── storage/   StorageBackend.kt, VPath.kt, LocalFileBackend.kt ← abstraksi & validasi path
│       │   ├── server/    CloudServer.kt, Uploads.kt, Shares.kt, Settings.kt,
│       │   │              NetworkPolicy.kt, StorageStats.kt, Logs.kt
│       │   ├── util/      Json.kt, AtomicFile.kt
│       │   └── DevMain.kt                                    ← menjalankan server di PC (opsional)
│       ├── main/resources/web/  index.html, app.js, app.css, share.html, share.js, icon.svg
│       └── test/kotlin/id/cloudssd/core/                     ← 39 tes JUnit
└── app/                                   ← aplikasi Android
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/id/cloudssd/app/
        │   ├── CloudApp.kt          ← instance server bersama, lokasi SSD tersimpan
        │   ├── MainActivity.kt      ← dashboard native di tablet
        │   ├── ServerService.kt     ← foreground service, wake/Wi-Fi lock, notifikasi
        │   ├── StorageMonitor.kt    ← deteksi SSD tersambung/terputus
        │   ├── NetworkAddresses.kt  ← IP Wi-Fi/VPN via ConnectivityManager
        │   ├── storage/SafStorageBackend.kt, VolumeInfo.kt   ← SAF + kapasitas aktual
        │   └── ui/Ui.kt
        └── res/  (ikon, tema, network security config, aturan backup)
```

Komponen terpisah sesuai permintaan: **server** (`http/`), **sistem file** (`storage/` + `SafStorageBackend`), **autentikasi** (`auth/`), **API** (`api/`), **antarmuka** (`resources/web/` untuk browser, `MainActivity` untuk tablet).

## Mulai cepat

1. Build APK — [docs/BUILD.md](docs/BUILD.md) (`./gradlew :app:assembleDebug`).
2. Pasang di Xiaomi Pad 6, sambungkan SSD, pilih folder SSD, buat admin, mulai server — [docs/PANDUAN.md](docs/PANDUAN.md).
3. Buka `http://<IP-tablet>:8080` dari HP/laptop di Wi-Fi yang sama.
4. Akses dari luar rumah dengan aman (Tailscale) — [docs/AKSES-JARAK-JAUH.md](docs/AKSES-JARAK-JAUH.md).
5. Batasan Android yang tetap berlaku — [docs/BATASAN.md](docs/BATASAN.md).

## Fitur

| Area | Yang sudah diimplementasikan |
|---|---|
| Penyimpanan | Pemilih folder SAF, izin persisten, kapasitas **aktual** (fstatvfs / StorageVolume), deteksi SSD terputus, peringatan ruang hampir penuh, deteksi lokasi internal vs SSD, tidak pernah memformat/menghapus otomatis |
| File | Unggah, unduh, buat/buka folder, ganti nama, pindah, hapus (dengan konfirmasi), pilih banyak, cari, urutkan nama/ukuran/tanggal, jenis & ekstensi, pratinjau gambar/video/audio/teks/PDF |
| Transfer | Unggah bertahap 8 MB yang **dapat dilanjutkan** (koneksi putus, SSD dicabut, browser ditutup), data di-fsync sebelum diakui, file sementara baru dipindahkan saat lengkap; unduhan streaming dengan HTTP Range (lanjutkan unduhan, putar video) |
| Nama sama | Tidak pernah menimpa diam-diam: tanya → *Lewati / Simpan sebagai "nama (1)" / Ganti* |
| Keamanan | Admin dibuat saat pertama, PBKDF2-SHA256 + salt, sesi token acak (hanya hash yang disimpan), cookie HttpOnly + SameSite=Strict, CSRF token, cek Origin & Host (anti DNS-rebinding), pembatasan login, validasi path (anti traversal & symlink), CSP ketat, konten pengguna disajikan dalam sandbox |
| Jaringan | Default hanya `127.0.0.1`; LAN harus diaktifkan dengan peringatan; VPN Tailscale opsional; **IP internet publik selalu ditolak**; HTTPS opsional via sertifikat `.p12` |
| Pengguna | Mode pribadi (default), multi-pengguna opsional, folder per pengguna, izin baca/tulis, kuota dari data aktual, nonaktifkan akun, reset sandi dari tablet |
| Berbagi | Tautan dengan masa berlaku, kata sandi opsional, mode hanya-lihat, cabut, hitung akses — tetap tunduk pada kebijakan jaringan |
| Server | Mulai/hentikan, status, IP & port, status SSD/izin/akses jarak jauh, log kesalahan, uji koneksi, notifikasi tetap, panduan baterai Xiaomi |
