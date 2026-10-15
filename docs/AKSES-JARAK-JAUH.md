# Akses dari luar rumah (aman)

CloudSSD **tidak pernah** membuka port ke internet dan **selalu menolak** koneksi dari alamat IP publik. Alamat seperti `192.168.x.x` hanya berlaku di jaringan rumah dan tidak bisa dibuka dari luar.

## Mengapa tidak port forwarding?

- Banyak ISP di Indonesia (termasuk sebagian besar jaringan seluler dan sebagian fiber rumah) memakai **CGNAT**: router Anda tidak punya IP publik sendiri, sehingga port forwarding tidak akan berfungsi sama sekali.
- Walau ada IP publik, membuka server file ke internet berarti halaman login terekspos ke pemindaian otomatis dari seluruh dunia.

## Rekomendasi: Tailscale (VPN pribadi WireGuard)

Tailscale membuat jaringan privat terenkripsi antara perangkat Anda dan **tetap bekerja di balik CGNAT** (memakai relai terenkripsi bila koneksi langsung tidak memungkinkan). Tidak perlu mengatur router.

1. Buat akun di tailscale.com (gratis untuk pemakaian pribadi).
2. Pasang aplikasi **Tailscale** di Xiaomi Pad 6 dari Play Store → login → aktifkan. Kecualikan Tailscale juga dari penghemat baterai (langkah yang sama seperti CloudSSD).
3. Pasang Tailscale di HP/laptop (Android, iOS, Windows, macOS, Linux) dengan akun yang sama.
4. Di aplikasi CloudSSD (tablet): aktifkan **Izinkan VPN Tailscale** → **Mulai ulang server**.
5. Kartu *Alamat akses* menampilkan alamat `http://100.x.y.z:8080/`. Buka alamat itu dari HP/laptop yang Tailscale-nya aktif.
6. Setelah ada perangkat yang benar-benar terhubung lewat VPN, status **Akses jarak jauh** berubah menjadi **Terverifikasi** (dengan IP & waktu). Sebelum itu statusnya "belum teruji" — aplikasi tidak mengklaim akses jarak jauh aktif tanpa bukti.

**Enkripsi:** lalu lintas Tailscale dienkripsi WireGuard dari perangkat ke tablet, sehingga `http://100.x.y.z` tetap terenkripsi di jalur internet. Bila ingin HTTPS juga di lapisan aplikasi, lihat bagian berikut.

Opsional: dengan MagicDNS, Anda dapat membuka `http://nama-tablet.tailnet-xxxx.ts.net:8080` — nama `*.ts.net` otomatis diizinkan saat opsi VPN aktif.

### Alternatif: WireGuard sendiri

Hanya bila router Anda memiliki **IP publik** (bukan CGNAT) dan mendukung server WireGuard (mis. OpenWrt, MikroTik). Klien terhubung ke router, lalu membuka IP LAN tablet. Aktifkan **Akses dari Wi-Fi lokal** (bukan opsi VPN), karena klien akan tampak sebagai jaringan lokal. Ini lebih rumit dan tidak bekerja di balik CGNAT.

## HTTPS (opsional)

CloudSSD dapat melayani HTTPS di port terpisah (default 8443) memakai sertifikat **PKCS#12 (.p12/.pfx)** yang Anda impor. Aplikasi tidak membuat sertifikat palsu "terpercaya"; pilih salah satu:

### A. Sertifikat lokal dengan mkcert (disarankan untuk rumah)

Di PC:
```bash
mkcert -install                          # membuat CA lokal di PC ini
mkcert -pkcs12 -p12-file cloudssd.p12 192.168.1.23 100.101.102.103 nama-tablet.tailnet-xxxx.ts.net
# kata sandi default file .p12 buatan mkcert: changeit
```
- Salin `cloudssd.p12` ke tablet → CloudSSD → **Impor sertifikat .p12** → masukkan kata sandi → **Mulai ulang server**.
- Pasang sertifikat CA mkcert (`mkcert -CAROOT` → `rootCA.pem`) di setiap perangkat klien agar browser mempercayainya (Android: *Setelan › Keamanan › Enkripsi & kredensial › Instal sertifikat › Sertifikat CA*; iOS: profil + *Pengaturan › Umum › Info › Pengaturan Kepercayaan Sertifikat*).
- Buka `https://192.168.1.23:8443/`.

### B. Sertifikat self-signed dengan keytool (JDK)

```bash
keytool -genkeypair -alias cloudssd -keyalg EC -groupname secp256r1 -validity 825 \
  -storetype PKCS12 -keystore cloudssd.p12 -dname "CN=CloudSSD" \
  -ext "SAN=ip:192.168.1.23,ip:100.101.102.103"
```
Browser akan menampilkan peringatan sampai sertifikat ditandai tepercaya secara manual di tiap perangkat.

Kata sandi .p12 dan sertifikat disimpan di penyimpanan internal aplikasi (tidak ikut dicadangkan). **Jangan** membagikan file .p12 — isinya kunci privat.

### Catatan Cloudflare Tunnel / layanan tunnel publik

Tunnel publik membuat CloudSSD dapat dijangkau siapa saja di internet (dilindungi hanya oleh login). Itu bertentangan dengan default keamanan proyek ini dan tidak didukung secara bawaan. Bila tetap ingin, letakkan di belakang autentikasi tambahan (mis. Cloudflare Access) dan pahami risikonya.

## Tautan berbagi

Tautan `/s/...` hanya terbuka dari jaringan yang diizinkan server (Wi-Fi rumah / Tailscale). Penerima di luar jaringan Anda tidak dapat membukanya kecuali mereka bergabung ke tailnet Anda (fitur *share node* Tailscale).
