package id.cloudssd.core

import id.cloudssd.core.storage.Capacity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.Socket
import kotlin.random.Random

class StorageFailureTest {

    @Test fun `SSD tidak tersedia dilaporkan jujur`() = TestEnv().use { env ->
        val c = env.admin()
        env.backend.connected = false
        val list = c.get("/api/files?path=/")
        assertEquals(503, list.status)
        assertEquals("storage_unavailable", list.json["code"])
        assertEquals(503, c.post("/api/files/mkdir", mapOf("path" to "/", "name" to "x")).status)
        assertEquals(503, c.post("/api/uploads", mapOf("path" to "/", "name" to "a.txt", "size" to 3)).status)
        val dash = c.get("/api/dashboard").json
        val storage = dash["storage"] as Map<*, *>
        assertEquals(false, storage["available"])
        assertNull(storage["totalBytes"])
        env.backend.connected = true
        assertEquals(200, c.get("/api/files?path=/").status)
    }

    @Test fun `lokasi penyimpanan belum dipilih`() = TestEnv().use { env ->
        val c = env.admin()
        env.storageSelected = false
        val r = c.get("/api/files?path=/")
        assertEquals(503, r.status)
        assertEquals(false, (c.get("/api/dashboard").json["storage"] as Map<*, *>)["configured"])
    }

    @Test fun `SSD terputus saat transfer lalu dilanjutkan`() = TestEnv().use { env ->
        val c = env.admin()
        val data = Random(3).nextBytes(4_000_000)
        val init = c.post("/api/uploads", mapOf("path" to "/", "name" to "arsip.zip", "size" to data.size))
        val id = init.json["id"] as String
        assertEquals(200, c.send("PUT", "/api/uploads/$id?offset=0", raw = data.copyOfRange(0, 1_000_000)).status)
        env.backend.disconnectAfterBytes = 300_000
        val r = c.send("PUT", "/api/uploads/$id?offset=1000000", raw = data.copyOfRange(1_000_000, 2_000_000))
        assertEquals(r.toString(), 503, r.status)
        assertEquals("storage_unavailable", r.json["code"])
        // Tidak boleh tampak berhasil
        assertFalse(File(env.root, "arsip.zip").exists())
        assertTrue(c.post("/api/uploads/$id/complete").status != 200)
        // SSD disambungkan kembali
        env.backend.disconnectAfterBytes = null
        env.backend.connected = true
        assertEquals(1_000_000L, c.get("/api/uploads/$id").json["committed"])
        assertEquals(200, c.send("PUT", "/api/uploads/$id?offset=1000000", raw = data.copyOfRange(1_000_000, data.size)).status)
        assertEquals(200, c.post("/api/uploads/$id/complete").status)
        assertArrayEquals(data, File(env.root, "arsip.zip").readBytes())
    }

    @Test fun `SSD terputus saat unduhan memutus koneksi, bukan file terpotong yang dianggap lengkap`() = TestEnv().use { env ->
        val c = env.admin()
        val data = Random(5).nextBytes(8_000_000)
        File(env.root, "film.mp4").writeBytes(data)
        Socket("127.0.0.1", env.port).use { s ->
            s.getOutputStream().write(("GET /api/files/download?path=/film.mp4 HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: cssd_sid=${c.cookies["cssd_sid"]}\r\n\r\n").toByteArray())
            val inp = s.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(inp.read().toChar())
            assertTrue(head.contains("Content-Length: 8000000"))
            val buf = ByteArray(65536)
            var got = 0L
            got += inp.read(buf)
            // Cabut SSD: file dihapus/terputus di tengah unduhan
            env.backend.connected = false
            File(env.root, "film.mp4").delete()
            s.soTimeout = 10_000
            while (true) { val n = try { inp.read(buf) } catch (e: java.io.IOException) { -1 }; if (n < 0) break; got += n }
            // Koneksi ditutup sebelum Content-Length terpenuhi -> klien tahu unduhan gagal.
            assertTrue("diterima $got", got < 8_000_000)
        }
    }

    @Test fun `penyimpanan penuh ditolak sebelum menulis`() = TestEnv().use { env ->
        val c = env.admin()
        env.backend.fakeCapacity = Capacity(totalBytes = 1_000_000_000_000, freeBytes = 50L * 1024 * 1024)
        val r = c.post("/api/uploads", mapOf("path" to "/", "name" to "besar.iso", "size" to 100L * 1024 * 1024))
        assertEquals(507, r.status)
        assertEquals("no_space", r.json["code"])
        val dash = c.get("/api/dashboard").json["storage"] as Map<*, *>
        assertEquals(true, dash["lowSpace"])
        assertEquals(1_000_000_000_000L, dash["totalBytes"])
    }

    @Test fun `disk penuh saat menulis tidak dianggap berhasil`() = TestEnv().use { env ->
        val c = env.admin()
        env.backend.noSpaceAfterBytes = 200_000
        val r = c.upload("/", "data.bin", ByteArray(1_500_000))
        assertEquals(507, r.status)
        assertEquals("no_space", r.json["code"])
        assertFalse(File(env.root, "data.bin").exists())
    }

    @Test fun `server dihentikan dan dijalankan kembali`() = TestEnv().use { env ->
        val c = env.admin()
        assertEquals(200, c.upload("/", "tetap.txt", "ada".toByteArray()).status)
        env.server.stop()
        try { Socket("127.0.0.1", env.port).close(); throw AssertionError("port masih terbuka") } catch (_: ConnectException) {}
        env.server.start()
        assertEquals(200, c.get("/api/me").status)
        val items = c.get("/api/files?path=/").json["items"] as List<*>
        assertEquals(listOf("tetap.txt"), items.map { (it as Map<*, *>)["name"] })
        // Port dipakai aplikasi lain -> pesan jelas
        env.server.stop()
        java.net.ServerSocket(env.port).use {
            try { env.server.start(); throw AssertionError("harus gagal") } catch (_: java.net.BindException) {}
            assertTrue(env.server.lastError!!.contains("Port"))
        }
        env.server.start()
        assertEquals(200, c.get("/api/me").status)
    }
}
