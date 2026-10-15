# Batasan Android yang masih berlaku

Hal-hal berikut adalah batasan platform yang **tidak** dapat (atau tidak boleh) dilewati oleh aplikasi tanpa root. CloudSSD menanganinya dengan menampilkan status yang jujur dan menyediakan pemulihan manual.

1. **Server tidak dijamin berjalan selamanya.** Foreground service + notifikasi tetap + wake lock membuat server jauh lebih tahan, tetapi Android/HyperOS masih dapat menghentikannya (memori rendah, pembaruan sistem, pengelola baterai MIUI/HyperOS yang agresif). Aplikasi mencatat kejadiannya dan menampilkan banner "Server berhenti tanpa diminta".
2. **Tidak mulai otomatis setelah tablet restart.** Android 12+ membatasi memulai layanan latar depan dari latar belakang. Setelah restart, buka CloudSSD dan tekan **Mulai server**. (Bila sistem me-restart layanan sendiri dan Android menolaknya, kesalahan tercatat di log.)
3. **Jenis layanan latar depan `specialUse`.** Dipakai karena "server file lokal" tidak cocok dengan kategori lain (mis. `dataSync` dibatasi 6 jam/hari di Android 15). Cocok untuk APK yang dipasang sendiri; untuk distribusi di Play Store diperlukan peninjauan Google.
4. **Akses SSD hanya lewat Storage Access Framework.** Lebih lambat daripada akses file langsung, terutama saat membuka folder berisi ribuan file, pencarian rekursif, dan penghitungan jumlah file (dilakukan di latar belakang dan disimpan sementara). Pencarian dibatasi 500 hasil / 20 detik.
5. **Kapasitas SSD bergantung pada sistem.** Diambil dari `fstatvfs` pada file penanda di SSD atau dari `StorageVolume`; bila keduanya gagal, ditampilkan "tidak dilaporkan".
6. **Sistem file SSD.** exFAT disarankan. FAT32: maksimal 4 GB per file. NTFS/ext4/APFS umumnya tidak dapat dipasang oleh Android tanpa root. Nama dengan karakter `" * / : < > ? \ |`, diakhiri titik/spasi, atau > 255 byte ditolak (exFAT tidak mendukungnya dan Android akan mengganti nama diam-diam).
7. **Izin SAF dapat hilang** bila SSD dipasang dengan UUID berbeda (diformat ulang) atau aplikasi dihapus data. Pilih ulang folder SSD.
8. **Daya USB-C.** Tablet mungkin tidak memberi daya cukup untuk SSD tertentu; gunakan hub USB-C ber-daya. Melepas SSD saat transfer berisiko merusak file yang sedang ditulis (file sementara CloudSSD tidak akan muncul sebagai file jadi).
9. **Wi-Fi dapat tidur.** Wi-Fi lock dipakai, tetapi pada Android 14+ mode latensi rendah hanya berlaku saat aplikasi di depan; sebagian perangkat tetap menghemat Wi-Fi saat layar mati. Charger + pengaturan baterai mengurangi masalah ini.
10. **Akses jarak jauh membutuhkan VPN.** Aplikasi tidak dapat menembus CGNAT atau mengatur router. Gunakan Tailscale (lihat AKSES-JARAK-JAUH.md).
11. **HTTP di jaringan lokal tidak terenkripsi.** Gunakan hanya di Wi-Fi tepercaya, atau impor sertifikat untuk HTTPS. Clipboard API browser hanya tersedia di HTTPS; di HTTP, tombol salin memakai cara cadangan.
12. **Unduh folder sebagai ZIP belum tersedia.** Pilih file lalu unduh; untuk banyak file, browser akan meminta izin "unduhan ganda".
13. **Progres unduhan.** Di sebagian besar browser progres ditampilkan oleh pengelola unduhan browser. Progres di panel CloudSSD hanya di Chrome/Edge desktop (File System Access API).
14. **Pratinjau** bergantung pada dukungan browser (mis. HEVC/MKV sering tidak dapat diputar di Chrome). Mode "hanya lihat" pada tautan berbagi menyembunyikan tombol unduh tetapi tidak dapat mencegah penerima menyimpan apa yang sudah ia lihat.
15. **Tidak ada enkripsi data di SSD.** File tersimpan apa adanya di SSD; siapa pun yang memegang SSD fisik dapat membacanya. Data akun/sesi berada di penyimpanan internal aplikasi (dilindungi enkripsi berbasis file Android).
