# Build APK & menjalankan tes

## Kebutuhan

- **Android Studio** Ladybug (2024.2) atau lebih baru, *atau* JDK 17 + Android SDK (Platform 35, Build-Tools 35).
- Koneksi internet saat build pertama (Gradle mengunduh AGP 8.7.3, Kotlin 2.1.0, JUnit).

Versi yang dipakai: Gradle **8.11.1** (wrapper), Android Gradle Plugin **8.7.3**, Kotlin **2.1.0**, `compileSdk`/`targetSdk` **35**, `minSdk` **30**. Xiaomi Pad 6 (Android 13 / HyperOS Android 14–15) berada dalam rentang ini.

## Lewat Android Studio

1. *File › Open* → pilih folder `CloudSSD`.
2. Tunggu *Gradle sync*. Bila Android Studio menawarkan pembaruan AGP, boleh ditolak.
3. *Build › Build App Bundle(s) / APK(s) › Build APK(s)*.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Lewat terminal

```bash
cd CloudSSD
# Linux/macOS (Windows: gradlew.bat)
./gradlew :core:test            # 39 tes modul inti (heap dibatasi 96 MB)
./gradlew :app:assembleDebug    # APK debug
./gradlew :app:assembleRelease  # APK rilis (default ditandatangani kunci debug)
```

Jika `local.properties` belum ada, buat dengan isi `sdk.dir=/path/ke/Android/Sdk` (Android Studio membuatnya otomatis).

## Tanda tangan rilis (disarankan)

Kunci debug cukup untuk pemakaian pribadi, tetapi untuk APK rilis yang stabil buat keystore sendiri:

```bash
keytool -genkeypair -v -keystore cloudssd-release.jks -alias cloudssd -keyalg RSA -keysize 3072 -validity 10000
```

lalu tambahkan `signingConfigs { create("release") { ... } }` di `app/build.gradle.kts` dan jangan pernah meng-commit file `.jks` atau kata sandinya (`.gitignore` sudah mengecualikannya).

## Instalasi di Xiaomi Pad 6

**Cara A — ADB (disarankan):**
1. Tablet: *Setelan › Tentang tablet* → ketuk *Versi OS* 7× untuk mengaktifkan Opsi pengembang.
2. *Setelan › Setelan tambahan › Opsi pengembang* → aktifkan *Debugging USB* (dan *Instal via USB* bila diminta).
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

**Cara B — salin APK:** kirim APK ke tablet, buka dengan *File Manager*, izinkan "Instal aplikasi tak dikenal" untuk aplikasi tersebut.

HyperOS dapat menampilkan peringatan keamanan untuk aplikasi di luar Play Store; ini wajar untuk aplikasi yang dibangun sendiri.

## Mencoba server di PC (tanpa Android)

Modul `core` bisa dijalankan di Windows/macOS/Linux untuk mencoba antarmuka web:

```bash
./gradlew :core:run --args="--root /path/ke/folder --data ./cloudssd-data --port 8080"
# tambahkan --lan agar perangkat lain di Wi-Fi yang sama bisa mengakses
```

Terminal menampilkan **kode pengaturan admin**; buka `http://127.0.0.1:8080`, isi kode, lalu buat akun.

## Tanpa laptop: build otomatis di GitHub

File `.github/workflows/build-apk.yml` membuat GitHub membangun APK di servernya sendiri (gratis).

1. Buat akun di github.com dan repositori baru (sebaiknya **Private**).
2. Masukkan isi folder `CloudSSD` ke repositori tersebut (branch `main`).
3. Buka tab **Actions** → tunggu workflow **Build APK** selesai (±5–10 menit, tanda centang hijau).
4. Buka **Releases › CloudSSD (build terbaru)** → unduh `CloudSSD.apk` langsung dari HP/tablet.
   (Alternatif: halaman hasil workflow › bagian *Artifacts* › `CloudSSD-apk`, berupa ZIP.)
5. Pasang seperti bagian "Instalasi di Xiaomi Pad 6".

Jika workflow gagal (tanda silang merah), buka log langkah yang gagal dan kirimkan pesan error-nya.
