# Pengujian

## Ringkasan status

| Bagian | Status |
|---|---|
| Modul `core` (server, API, auth, penyimpanan, web) | ✅ Dikompilasi; **46/46 tes JUnit lulus** dengan heap JVM 96 MB |
| Antarmuka web | ✅ Uji end-to-end otomatis di Chromium (desktop 1400×900 & ponsel 390×844): **lulus**, tanpa pelanggaran CSP |
| Modul `app` (Android) | ⚠️ Kode Kotlin **lolos pemeriksaan tipe** terhadap `android.jar` API 35 (tanpa error). **Belum dibangun dengan Gradle/AGP** (resource, manifest merge, dex, lint belum dijalankan) karena lingkungan pembuatan tidak dapat mengakses repositori Google/Maven |
| Drive jaringan (WebDAV) | ✅ 7 tes JUnit + uji interoperabilitas dengan klien **rclone 1.68.2** (kapasitas, salin 50 MB + cek byte, MOVE/COPY di server, timpa, hapus, sandi salah, traversal, LOCK/UNLOCK) |
| Perangkat fisik Xiaomi Pad 6 + SSD | ⚠️ Pengguna melaporkan aplikasi terpasang, SSD 1 TB terbaca dengan kapasitas aktual, server berjalan, login berhasil. Uji transfer besar & skenario putus/restart masih perlu dilakukan. Wajib dilakukan sebelum dipakai untuk data penting — lihat daftar uji manual di bawah |

## Tes otomatis (`./gradlew :core:test`)

| Skenario wajib | Tes |
|---|---|
| Login dan logout | `AuthTest.login dan logout` (cookie HttpOnly/SameSite, token tidak bisa dipakai ulang setelah logout) |
| Unggah dan unduh file | `FilesTest.unggah dan unduh file` (isi byte identik, Range 206) |
| File besar | `FilesTest.file besar dialirkan tanpa memuat ke RAM` — 300 MB dengan heap 96 MB, SHA-256 cocok |
| Pembuatan folder | `FilesTest.buat folder, ganti nama, pindah, hapus` |
| Penghapusan file | sama; penghapusan item yang tidak ada dilaporkan gagal, bukan sukses |
| Nama file duplikat | `FilesTest.nama file duplikat tidak menimpa diam-diam` (409 → rename "(1)" → overwrite eksplisit) |
| SSD tidak tersedia | `StorageFailureTest.SSD tidak tersedia dilaporkan jujur`, `lokasi penyimpanan belum dipilih` |
| SSD terputus saat transfer | `StorageFailureTest.SSD terputus saat transfer lalu dilanjutkan` (unggah) dan `…saat unduhan memutus koneksi` (unduh) |
| Penyimpanan penuh | `penyimpanan penuh ditolak sebelum menulis` (507) dan `disk penuh saat menulis tidak dianggap berhasil` (ENOSPC) |
| Koneksi jaringan terputus | `FilesTest.koneksi terputus saat unggah lalu dilanjutkan` (socket diputus di tengah potongan) |
| Pengguna tanpa izin | `SecurityTest.izin dan isolasi pengguna`, `mode pribadi hanya mengizinkan admin`, `kuota dihitung dari data aktual` |
| Permintaan API tidak sah | `SecurityTest.permintaan API tanpa login ditolak`, `CSRF dan origin lintas situs ditolak`, `header Host asing ditolak` |
| Akses di luar direktori | `SecurityTest.path traversal dicegah`, `symlink keluar dari SSD tidak diikuti`, `VPath menolak segmen berbahaya` |
| Server dihentikan & dijalankan kembali | `StorageFailureTest.server dihentikan dan dijalankan kembali` (port tertutup, data & sesi tetap, pesan port terpakai), `AuthTest.sesi bertahan…` |
| Tambahan | pembatas login, hash sandi, setup tanpa admin bawaan, ganti sandi mengeluarkan sesi lain, tautan berbagi (sandi/kedaluwarsa/cabut/hanya-lihat), HTTPS dengan .p12, kebijakan jaringan (publik selalu ditolak), keep-alive tidak memblokir, konten HTML disajikan dalam sandbox, JSON, Range |

"SSD terputus" disimulasikan dengan `FaultyBackend` (pembungkus backend yang dapat memutus akses, memalsukan kapasitas, dan melempar ENOSPC). Ini menguji logika server, **bukan** perilaku driver USB Android yang sesungguhnya.

## Uji manual wajib di Xiaomi Pad 6

- [ ] APK terpasang, aplikasi terbuka, tema gelap tampil benar di lanskap & potret.
- [ ] Pilih folder di SSD lewat pemilih folder; status **Terhubung**, kapasitas sesuai SSD (bukan internal).
- [ ] Buat admin, mulai server; notifikasi tetap muncul dengan alamat IP.
- [ ] Dari HP & laptop di Wi-Fi yang sama: login, unggah file 5–20 GB, unduh kembali, bandingkan checksum.
- [ ] Cabut SSD saat mengunggah → web menampilkan SSD terputus & unggahan dijeda; sambungkan lagi → lanjutkan → file utuh.
- [ ] Matikan layar 30+ menit (dengan & tanpa charger) → server masih dapat dibuka.
- [ ] Restart tablet → aplikasi menampilkan server berhenti → mulai manual berhasil.
- [ ] Tailscale: buka alamat 100.x dari jaringan seluler → status berubah "Terverifikasi".
- [ ] (Opsional) impor .p12 → HTTPS di port 8443 berfungsi.
- [ ] SSD FAT32 (bila ada) → unggah file > 4 GB menampilkan pesan batas FAT32.

## Menjalankan uji end-to-end browser sendiri

Uji Playwright yang dipakai saat pengembangan tidak disertakan sebagai bagian build. Cara manual yang setara: jalankan `./gradlew :core:run --args="--root /tmp/uji --data /tmp/uji-data"`, buka browser, lalu ikuti alur setup → login → unggah → konflik nama → pratinjau → ganti nama → cari → bagikan → hapus → pengguna.
