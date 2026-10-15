# Drive jaringan (WebDAV): CloudSSD sebagai penyimpanan virtual

Dengan fitur ini, CloudSSD tampil seperti **disk/penyimpanan** di aplikasi file HP, File Explorer Windows, Finder Mac, atau Linux, bukan hanya lewat browser. File dapat dibuka, disalin, dipindah, dan dihapus langsung dari aplikasi file.

**Kapasitas yang tampil adalah kapasitas sebenarnya SSD Anda** (mis. 1 TB total, ruang terpakai & tersisa sesuai isi SSD). Untuk pengguna yang diberi kuota, yang tampil adalah kuotanya. CloudSSD tidak memalsukan angka kapasitas.

## Mengaktifkan

- **Di tablet:** CloudSSD › kartu *Jaringan & keamanan* › nyalakan **Drive jaringan (WebDAV)** (langsung berlaku).
- **Atau di browser (admin):** *Pengaturan* › **Drive jaringan (WebDAV)** › *Simpan pengaturan*.

Alamat drive = alamat CloudSSD + `/dav/`, mis. **`http://192.168.1.23:8080/dav/`** (tampil di kartu *Alamat akses* tablet dan di Dashboard web). Login memakai **nama pengguna & kata sandi CloudSSD** yang sama. Akses tetap mengikuti aturan jaringan: Wi-Fi rumah (bila Akses LAN aktif) atau Tailscale.

## Android (HP / tablet lain)

**CX File Explorer** (gratis, Play Store):
1. Tab **Jaringan** › **Penyimpanan jarak jauh** › **+** › **WebDAV**.
2. Host: `192.168.1.23` · Port: `8080` · Path: `/dav/` · HTTPS: mati.
3. Nama pengguna & kata sandi CloudSSD › **OK**.

Aplikasi lain yang mendukung WebDAV: Solid Explorer, Total Commander (+ plugin WebDAV), FX File Explorer.

## Windows

**Disarankan: RaiDrive** (gratis) — memasang CloudSSD sebagai drive berhuruf (mis. `Z:`) dengan kapasitas yang benar:
1. **Add** › **NAS** › **WebDAV**, hapus centang *Address/Secure* (HTTPS) bila memakai HTTP.
2. Alamat `192.168.1.23`, port `8080`, path `/dav/`, isi akun CloudSSD › **Connect**.

*Map network drive* bawaan Windows juga bisa (`http://192.168.1.23:8080/dav/`), tetapi secara default Windows **menolak login Basic lewat HTTP** dan membatasi unduhan 50 MB. Mengubahnya butuh edit Registry (`HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters`: `BasicAuthLevel=2`, `FileSizeLimitInBytes=4294967295`, lalu restart layanan *WebClient*), dan tetap terbatas 4 GB per file. Klien WebDAV bawaan Windows juga sudah dinyatakan usang oleh Microsoft — gunakan RaiDrive, atau HTTPS (lihat AKSES-JARAK-JAUH.md).

## macOS

Finder › **Go › Connect to Server** (⌘K) › `http://192.168.1.23:8080/dav/` › **Connect** › isi akun. CloudSSD muncul di sidebar *Locations*.

## iPhone / iPad

Aplikasi *File* bawaan iOS tidak mendukung WebDAV (hanya SMB). Gunakan aplikasi seperti **Documents by Readdle** atau **FE File Explorer** › tambah koneksi **WebDAV** dengan alamat di atas. Browser (Safari) tetap bisa membuka CloudSSD seperti biasa.

## Linux

Files (Nautilus) › **Other Locations** › *Connect to Server*: `dav://192.168.1.23:8080/dav/`. Alternatif: `davfs2` atau `rclone mount`.

## Catatan & batasan

- File yang diunggah lewat drive ditulis ke file sementara dulu; file tujuan baru muncul/diganti setelah data lengkap tersimpan. Bila SSD terputus atau penuh, aplikasi file akan menerima error (503/507) dan file lama tidak rusak.
- **Kunci file (LOCK) bersifat sukarela**: diterima agar Windows/macOS/Office dapat menyimpan, tetapi tidak mencegah dua perangkat mengubah file yang sama bersamaan.
- Atribut tambahan dari Windows (atribut tersembunyi, cap waktu pembuatan) **tidak disimpan**; tanggal diubah mengikuti waktu penulisan di SSD.
- Login lewat HTTP di jaringan lokal tidak terenkripsi (sama seperti halaman web). Untuk luar rumah gunakan Tailscale, atau HTTPS.
- Teruji otomatis dengan klien **rclone** (salin 50 MB, cek isi byte-per-byte, pindah/salin di server, hapus, kapasitas) dan 7 tes JUnit. Belum diuji dengan setiap aplikasi di atas — laporkan bila ada aplikasi yang bermasalah.
