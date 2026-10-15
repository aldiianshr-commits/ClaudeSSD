# Panduan pemakaian CloudSSD di Xiaomi Pad 6

## 1. Menghubungkan SSD eksternal

1. **Format SSD sebagai exFAT** (di PC) bila belum. FAT32 membatasi file maksimal 4 GB; NTFS umumnya tidak didukung penuh oleh Android. Pemformatan menghapus isi SSD — lakukan sendiri, CloudSSD tidak pernah memformat.
2. Sambungkan SSD ke port USB‑C tablet (langsung atau lewat adaptor/hub USB‑C OTG).
3. SSD 2,5"/NVMe dalam enclosure dapat menarik daya cukup besar. Bila SSD sering terputus, pakai **hub USB‑C ber-daya (PD)** sehingga tablet sekaligus terisi daya.
4. Android akan menampilkan notifikasi "Drive USB". Pastikan SSD terbaca di aplikasi *File Manager*.
5. Disarankan membuat folder khusus, mis. `CloudSSD`, di root SSD.

Aplikasi menampilkan **kapasitas aktual** yang dilaporkan sistem (SSD "1 TB" biasanya terbaca ±931 GiB / ±1,00 TB desimal). Bila sistem tidak melaporkan kapasitas, aplikasi menulis "tidak dilaporkan" — tidak menebak.

## 2. Memberi izin penyimpanan

CloudSSD tidak meminta izin "semua file". Aksesnya terbatas pada folder yang Anda pilih:

1. Buka CloudSSD → kartu **SSD / Penyimpanan** → **Pilih folder SSD**.
2. Pemilih folder Android terbuka. Ketuk ☰ / nama perangkat di kiri atas, pilih **SSD (Drive USB)**.
3. Masuk ke folder `CloudSSD` (atau buat baru) → **Gunakan folder ini** → **Izinkan**.
4. Status berubah menjadi **Terhubung**, dengan nama volume dan kapasitas.

Izin bersifat persisten. Jika SSD dicabut lalu disambung lagi ke port yang sama, izin biasanya tetap berlaku; bila status tetap **Terputus**, pilih ulang folder SSD.

Bila yang dipilih ternyata penyimpanan internal tablet, aplikasi memberi peringatan.

## 3. Pengaturan pertama & memulai server

1. Kartu **Pengaturan pertama**: buat nama pengguna & kata sandi administrator (minimal 10 karakter). Tidak ada akun bawaan.
2. (Opsional) aktifkan **Akses dari Wi-Fi lokal** — baca peringatan keamanannya.
3. Tekan **Mulai server**. Izinkan notifikasi bila diminta (Android 13+). Notifikasi tetap "CloudSSD — Berjalan di http://…" muncul selama server aktif, dengan tombol **Hentikan**.
4. Tekan **Uji koneksi** untuk memastikan server menjawab di 127.0.0.1 dan IP Wi-Fi.

Perubahan port, akses LAN/VPN, wake lock, dan HTTPS berlaku setelah **Mulai ulang server**.

## 4. Membuka CloudSSD dari HP/laptop (jaringan lokal)

1. HP/laptop terhubung ke **Wi-Fi yang sama** dengan tablet.
2. Lihat kartu **Alamat akses** di tablet, mis. `http://192.168.1.23:8080/`.
3. Ketik alamat itu di browser (Chrome, Safari, Edge, Firefox) → login.
4. Tips: atur **IP tetap/DHCP reservation** untuk tablet di router agar alamatnya tidak berubah.

Yang dapat dilakukan di browser: unggah (tombol atau seret-lepas), unduh, buat folder, ganti nama, pindah, hapus, pilih banyak, cari, urutkan, pratinjau, buat tautan berbagi, ganti kata sandi, lihat sesi login. Administrator juga dapat mengelola pengguna, kuota, dan melihat log.

**Unggahan besar**: file dipotong 8 MB. Bila Wi-Fi putus, unggahan mencoba ulang otomatis; bila SSD dicabut, unggahan dijeda dan dapat **dilanjutkan** setelah SSD tersambung. Bila browser tertutup, buka lagi CloudSSD → panel transfer menampilkan unggahan yang belum selesai → **Lanjutkan** → pilih file yang sama. File baru muncul di folder setelah **seluruhnya** tersimpan.

**Unduhan**: memakai pengelola unduhan browser (progres tampil di sana, dapat dilanjutkan). Di Chrome/Edge desktop, file > 20 MB menampilkan dialog simpan dan progres di panel CloudSSD.

## 5. Multi-pengguna (opsional)

Default adalah **mode pribadi** (hanya admin). Untuk keluarga/teman:
1. Browser (admin) → **Pengguna** → **Aktifkan** mode multi-pengguna.
2. **Tambah pengguna**: nama, kata sandi, folder (default `/users/<nama>`), kuota GB, izin baca/tulis.
3. Pengguna hanya melihat foldernya sendiri. Kuota dihitung dari ukuran file sebenarnya. Total kuota boleh melebihi kapasitas SSD, tetapi unggahan tetap ditolak bila SSD penuh.

## 6. Pengaturan baterai Xiaomi Pad 6 (MIUI/HyperOS)

Agar server tidak dihentikan saat layar mati:
1. Di aplikasi: **Izinkan berjalan di latar** → *Izinkan*.
2. *Setelan › Aplikasi › Kelola aplikasi › CloudSSD › Penghemat baterai* → **Tidak ada batasan**.
3. Pada halaman yang sama, aktifkan **Mulai otomatis (Autostart)** bila tersedia.
4. Buka layar *Aplikasi terbaru*, tekan lama kartu CloudSSD → **ikon gembok** (kunci).
5. Biarkan opsi **Jaga CPU & Wi-Fi tetap aktif** menyala, dan sambungkan tablet ke charger.

Nama menu dapat sedikit berbeda antarversi HyperOS. Langkah ini **mengurangi**, tetapi tidak menghilangkan, kemungkinan sistem menghentikan server (lihat [BATASAN.md](BATASAN.md)).

## 7. Memulihkan layanan

| Situasi | Yang terlihat | Tindakan |
|---|---|---|
| Tablet restart | Server **Berhenti**; banner "Server berhenti tanpa diminta" | Buka CloudSSD → **Mulai server** |
| SSD dicabut | SSD **Terputus**, notifikasi "⚠ SSD terputus", web menampilkan "SSD tidak tersedia", unggahan dijeda | Sambungkan SSD → **Periksa ulang**; bila tetap terputus → **Pilih folder SSD** lagi → lanjutkan unggahan |
| Sistem menghentikan aplikasi | Notifikasi hilang, web tidak bisa dibuka | Buka CloudSSD → **Mulai server**; ikuti bagian 6 |
| Port dipakai aplikasi lain | "Port 8080 sedang dipakai…" | Ubah port → **Simpan port** → mulai ulang |
| Lupa kata sandi | — | Tablet → kartu **Akun** → setel ulang |

## 8. Mengatasi error

| Pesan / gejala | Penyebab & solusi |
|---|---|
| Browser: "tidak dapat dijangkau" | Server belum berjalan; Akses Wi-Fi lokal belum aktif; HP di Wi-Fi lain/data seluler; router mengaktifkan *AP/client isolation* (matikan di router atau pakai jaringan non-tamu). |
| "Akses dari jaringan ini tidak diizinkan" | IP klien bukan dari LAN/VPN yang diizinkan. Aktifkan opsi yang sesuai di tablet. |
| "Nama host tidak diizinkan" | Membuka lewat nama domain. Gunakan alamat IP, atau daftarkan nama host di *Pengaturan › Nama host tambahan*. |
| "Terlalu banyak percobaan login" | Tunggu sesuai hitungan mundur (1–30 menit). |
| "Kode pengaturan salah" | Kode ada di aplikasi tablet (kartu Alamat akses) — atau buat admin langsung di tablet. |
| "SSD tidak tersedia" (503) | SSD terputus/izin hilang. Lihat bagian 7. |
| "Ruang kosong SSD tidak cukup" / "Penyimpanan penuh" (507) | Hapus file atau ganti SSD lebih besar. Server menyisakan 32 MB cadangan. |
| "Kuota penyimpanan Anda tidak cukup" | Admin menaikkan kuota di halaman Pengguna. |
| "File melebihi batas ukuran sistem file SSD" | SSD masih FAT32 → format ulang sebagai exFAT (salin data dulu). |
| "Penyimpanan mengubah nama…" | Penyedia penyimpanan non-standar mengganti nama file; pilih folder di SSD lewat penyedia bawaan "Drive USB". |
| Video tidak bisa diputar | Codec tidak didukung browser (mis. HEVC/MKV di Chrome). Unduh dan putar di aplikasi pemutar. |
| Kapasitas "tidak dilaporkan" | Sistem tidak memberi informasi untuk lokasi tersebut; fungsi file tetap berjalan. |

Log kesalahan lengkap: tablet (kartu **Log kesalahan** → *Salin log*) atau browser admin (*Pengaturan › Log kesalahan*).
