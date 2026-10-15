# Referensi API

Semua path file bersifat **relatif terhadap folder pengguna** (admin: seluruh SSD). Respons error: `{"error": "...", "code": "..."}`.
Request yang mengubah data wajib membawa header `X-CSRF-Token` (dari `/api/login` atau `/api/me`) dan cookie sesi `cssd_sid`.

| Metode & path | Keterangan |
|---|---|
| `GET /api/ping` | Cek hidup (publik) |
| `GET /api/status` | `{setupRequired, multiUser, version}` (publik) |
| `POST /api/setup` | `{code, username, password}` — hanya bila belum ada admin; kode dari aplikasi tablet |
| `POST /api/login` | `{username, password}` → cookie + `{user, csrf}`; dibatasi 5 percobaan lalu dikunci bertahap |
| `POST /api/logout` | Mengakhiri sesi |
| `GET /api/me` · `POST /api/me/password` | Info akun · `{current, newPassword}` (mengeluarkan sesi lain) |
| `GET /api/me/sessions` · `POST /api/me/sessions/revoke` | Daftar sesi · `{id}` atau `{others: true}` |
| `GET /api/dashboard` | Status server, SSD, kapasitas, jumlah file/folder, alamat, akses jarak jauh, aktivitas |
| `GET /api/files?path=` | Isi folder |
| `GET /api/files/stat?path=` · `GET /api/files/search?path=&q=` | Info file · pencarian rekursif |
| `GET /api/files/download?path=&inline=0\|1` | Unduh/stream, mendukung `Range` |
| `POST /api/files/mkdir` | `{path, name}` |
| `POST /api/files/rename` | `{path, name}` |
| `POST /api/files/move` | `{paths: [...], dest}` → `{moved, failed}` |
| `POST /api/files/delete` | `{paths: [...]}` → `{deleted, failed}` |
| `POST /api/uploads` | `{path, name, size, conflict: ask\|rename\|overwrite}` → `{id, committed, chunkSize}`; 409 `conflict` berisi `suggested` |
| `PUT /api/uploads/{id}?offset=N` | Body biner potongan (maks 64 MB); 409 `offset` berisi `expected` |
| `GET /api/uploads` · `GET /api/uploads/{id}` | Unggahan belum selesai · status |
| `POST /api/uploads/{id}/complete` · `DELETE /api/uploads/{id}` | Selesaikan (file baru muncul di sini) · batalkan |
| `GET/POST /api/shares` · `DELETE /api/shares/{id}` | Tautan berbagi `{path, expiresHours?, password?, allowDownload}` |
| `GET /api/public/share/{token}` · `POST …/unlock` · `GET …/content[?download=1]` | Akses penerima tautan |
| `GET/POST /api/admin/users` · `PATCH /api/admin/users/{id}` · `POST /api/admin/users/{id}/usage` | Kelola pengguna, kuota, izin, folder |
| `GET/PATCH /api/admin/settings` · `GET /api/admin/logs` | Mode multi-pengguna, batas peringatan ruang, nama host · log |

Kode status penting: `401` belum login, `403` tidak berizin/CSRF/jaringan, `409` konflik, `413` terlalu besar, `429` dibatasi, `503` SSD tidak tersedia, `507` SSD penuh/kuota habis.
