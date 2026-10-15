package id.cloudssd.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random

class FilesTest {

    @Test fun `unggah dan unduh file`() = TestEnv().use { env ->
        val c = env.admin()
        val data = Random(1).nextBytes(3_500_000)
        val r = c.upload("/", "foto liburan.jpg", data)
        assertEquals(r.toString(), 200, r.status)
        assertEquals("/foto liburan.jpg", r.json["path"])
        assertArrayEquals(data, File(env.root, "foto liburan.jpg").readBytes())
        val (st, body) = c.getBytes("/api/files/download?path=" + c.q("/foto liburan.jpg"))
        assertEquals(200, st)
        assertArrayEquals(data, body)
        // Range (melanjutkan unduhan / video)
        val (st2, part) = c.getBytes("/api/files/download?path=" + c.q("/foto liburan.jpg"), mapOf("Range" to "bytes=100-199"))
        assertEquals(206, st2)
        assertArrayEquals(data.copyOfRange(100, 200), part)
        val list = c.get("/api/files?path=/").json["items"] as List<*>
        assertEquals(1, list.size)
        // File sementara & folder sistem tidak terlihat
        assertTrue(File(env.root, ".cloudssd/uploads").isDirectory)
        assertTrue(File(env.root, ".cloudssd/uploads").listFiles()!!.isEmpty())
    }

    @Test fun `file besar dialirkan tanpa memuat ke RAM`() = TestEnv().use { env ->
        // Uji dijalankan dengan -Xmx kecil; 300 MB tidak muat di heap bila dibuffer.
        val c = env.admin()
        val size = 300L * 1024 * 1024
        val init = c.post("/api/uploads", mapOf("path" to "/", "name" to "besar.bin", "size" to size))
        assertEquals(201, init.status)
        val id = init.json["id"] as String
        val md = MessageDigest.getInstance("SHA-256")
        val chunk = 32 * 1024 * 1024
        var off = 0L
        while (off < size) {
            val len = minOf(chunk.toLong(), size - off)
            // Kirim lewat socket mentah dari generator data, tanpa array sebesar file.
            val status = rawPut(env.port, c, "/api/uploads/$id?offset=$off", len, seed = off) { b, n -> md.update(b, 0, n) }
            assertEquals(200, status)
            off += len
        }
        assertEquals(200, c.post("/api/uploads/$id/complete").status)
        val expected = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals(size, File(env.root, "besar.bin").length())
        val (st, sha) = c.downloadSha256("/api/files/download?path=/besar.bin")
        assertEquals(200, st)
        assertEquals(expected, sha)
    }

    @Test fun `buat folder, ganti nama, pindah, hapus`() = TestEnv().use { env ->
        val c = env.admin()
        assertEquals(201, c.post("/api/files/mkdir", mapOf("path" to "/", "name" to "Dokumen")).status)
        assertEquals(409, c.post("/api/files/mkdir", mapOf("path" to "/", "name" to "Dokumen")).status)
        assertEquals(201, c.post("/api/files/mkdir", mapOf("path" to "/Dokumen", "name" to "2026")).status)
        assertTrue(File(env.root, "Dokumen/2026").isDirectory)
        assertEquals(200, c.upload("/Dokumen", "a.txt", "halo".toByteArray()).status)
        assertEquals(200, c.post("/api/files/rename", mapOf("path" to "/Dokumen/a.txt", "name" to "b.txt")).status)
        assertTrue(File(env.root, "Dokumen/b.txt").exists())
        val mv = c.post("/api/files/move", mapOf("paths" to listOf("/Dokumen/b.txt"), "dest" to "/Dokumen/2026"))
        assertEquals(1, (mv.json["moved"] as List<*>).size)
        assertTrue(File(env.root, "Dokumen/2026/b.txt").exists())
        // Tidak boleh memindah folder ke dalam dirinya sendiri
        val bad = c.post("/api/files/move", mapOf("paths" to listOf("/Dokumen"), "dest" to "/Dokumen/2026"))
        assertEquals(1, (bad.json["failed"] as List<*>).size)
        val del = c.post("/api/files/delete", mapOf("paths" to listOf("/Dokumen")))
        assertEquals(listOf("/Dokumen"), del.json["deleted"])
        assertFalse(File(env.root, "Dokumen").exists())
        // Menghapus yang tidak ada dilaporkan gagal, bukan sukses
        val del2 = c.post("/api/files/delete", mapOf("paths" to listOf("/tidak-ada.txt")))
        assertEquals(0, (del2.json["deleted"] as List<*>).size)
        assertEquals(1, (del2.json["failed"] as List<*>).size)
    }

    @Test fun `nama file duplikat tidak menimpa diam-diam`() = TestEnv().use { env ->
        val c = env.admin()
        assertEquals(200, c.upload("/", "laporan.pdf", "versi1".toByteArray()).status)
        val conflict = c.upload("/", "laporan.pdf", "versi2".toByteArray())
        assertEquals(409, conflict.status)
        assertEquals("conflict", conflict.json["code"])
        assertEquals("laporan (1).pdf", conflict.json["suggested"])
        assertEquals("versi1", File(env.root, "laporan.pdf").readText())
        val renamed = c.upload("/", "laporan.pdf", "versi2".toByteArray(), conflict = "rename")
        assertEquals("/laporan (1).pdf", renamed.json["path"])
        assertEquals("versi1", File(env.root, "laporan.pdf").readText())
        // Mengganti hanya bila diminta eksplisit
        assertEquals(200, c.upload("/", "laporan.pdf", "versi3".toByteArray(), conflict = "overwrite").status)
        assertEquals("versi3", File(env.root, "laporan.pdf").readText())
        // Ganti nama ke nama yang sudah ada ditolak
        assertEquals(409, c.post("/api/files/rename", mapOf("path" to "/laporan (1).pdf", "name" to "laporan.pdf")).status)
    }

    @Test fun `pencarian dan nama tidak valid`() = TestEnv().use { env ->
        val c = env.admin()
        c.post("/api/files/mkdir", mapOf("path" to "/", "name" to "Foto"))
        c.upload("/Foto", "Pantai Kuta.jpg", byteArrayOf(1, 2, 3))
        c.upload("/", "catatan.txt", byteArrayOf(1))
        val r = c.get("/api/files/search?path=/&q=kuta")
        assertEquals(listOf("/Foto/Pantai Kuta.jpg"), (r.json["items"] as List<*>).map { (it as Map<*, *>)["path"] })
        for (bad in listOf("a/b", "a:b", "con*", "titik.", "a\u0001b", "..", "a\\b")) {
            assertEquals("nama '$bad'", 400, c.post("/api/files/mkdir", mapOf("path" to "/", "name" to bad)).status)
        }
    }

    @Test fun `koneksi terputus saat unggah lalu dilanjutkan`() = TestEnv().use { env ->
        val c = env.admin()
        val data = Random(7).nextBytes(5_000_000)
        val init = c.post("/api/uploads", mapOf("path" to "/", "name" to "video.mp4", "size" to data.size))
        val id = init.json["id"] as String
        assertEquals(200, c.send("PUT", "/api/uploads/$id?offset=0", raw = data.copyOfRange(0, 2_000_000)).status)
        // Kirim potongan berikutnya sebagian lalu putuskan koneksi (Wi-Fi hilang).
        Socket("127.0.0.1", env.port).use { s ->
            val out = s.getOutputStream()
            out.write(("PUT /api/uploads/$id?offset=2000000 HTTP/1.1\r\nHost: 127.0.0.1:${env.port}\r\n" +
                "Cookie: cssd_sid=${c.cookies["cssd_sid"]}\r\nX-CSRF-Token: ${c.csrf}\r\nContent-Length: 2000000\r\n\r\n").toByteArray())
            out.write(data, 2_000_000, 700_000)
            out.flush()
        }
        Thread.sleep(300)
        // Server hanya mengakui potongan yang selesai & tersinkron.
        val st = c.get("/api/uploads/$id")
        assertEquals(2_000_000L, st.json["committed"])
        // Offset salah ditolak dengan offset yang benar
        val wrong = c.send("PUT", "/api/uploads/$id?offset=2700000", raw = ByteArray(10))
        assertEquals(409, wrong.status)
        assertEquals(2_000_000L, wrong.json["expected"])
        // File belum muncul di folder tujuan
        assertFalse(File(env.root, "video.mp4").exists())
        assertEquals(400, c.post("/api/uploads/$id/complete").status)
        // Lanjutkan
        assertEquals(200, c.send("PUT", "/api/uploads/$id?offset=2000000", raw = data.copyOfRange(2_000_000, data.size)).status)
        assertEquals(200, c.post("/api/uploads/$id/complete").status)
        assertArrayEquals(data, File(env.root, "video.mp4").readBytes())
    }

    @Test fun `koneksi keep-alive yang menganggur tidak memblokir klien lain`() = TestEnv().use { env ->
        // Browser membuka hingga 6 koneksi per perangkat; koneksi idle tidak boleh menahan request baru.
        val idle = (1..12).map { Socket("127.0.0.1", env.port) }
        try {
            val start = System.currentTimeMillis()
            val c = env.admin()
            assertEquals(200, c.get("/api/me").status)
            assertTrue("lama: ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 3000)
        } finally { idle.forEach { it.close() } }
    }

    companion object {
        /** PUT dengan body hasil generator deterministik, tanpa menyimpan seluruh body di memori. */
        fun rawPut(port: Int, c: Client, path: String, length: Long, seed: Long, onBytes: (ByteArray, Int) -> Unit): Int {
            Socket("127.0.0.1", port).use { s ->
                val out = s.getOutputStream().buffered(1 shl 16)
                out.write(("PUT $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nCookie: cssd_sid=${c.cookies["cssd_sid"]}\r\n" +
                    "X-CSRF-Token: ${c.csrf}\r\nContent-Length: $length\r\nConnection: close\r\n\r\n").toByteArray())
                val rnd = Random(seed)
                val buf = ByteArray(1 shl 16)
                var left = length
                while (left > 0) {
                    val n = minOf(buf.size.toLong(), left).toInt()
                    rnd.nextBytes(buf, 0, n)
                    onBytes(buf, n)
                    out.write(buf, 0, n)
                    left -= n
                }
                out.flush()
                val statusLine = readLine(s.getInputStream())
                return statusLine.split(' ')[1].toInt()
            }
        }

        private fun readLine(i: InputStream): String {
            val sb = StringBuilder()
            while (true) { val ch = i.read(); if (ch == -1 || ch == '\n'.code) break; if (ch != '\r'.code) sb.append(ch.toChar()) }
            return sb.toString()
        }
    }
}
