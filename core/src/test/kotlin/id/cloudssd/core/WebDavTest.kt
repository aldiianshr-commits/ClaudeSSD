package id.cloudssd.core

import id.cloudssd.core.storage.Capacity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

class WebDavTest {
    private val http = HttpClient.newHttpClient()

    private fun dav(env: TestEnv, method: String, path: String, body: ByteArray? = null, user: String = ADMIN, pw: String = ADMIN_PW,
                    headers: Map<String, String> = emptyMap(), auth: Boolean = true): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:${env.port}$path"))
        if (auth) b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$pw".toByteArray()))
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (body != null) HttpRequest.BodyPublishers.ofByteArray(body) else HttpRequest.BodyPublishers.noBody())
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun enabled(extra: (id.cloudssd.core.server.ServerSettings) -> id.cloudssd.core.server.ServerSettings = { it }) =
        TestEnv(configure = { extra(it.copy(webdavEnabled = true)) })

    @Test fun `nonaktif secara default dan butuh login`() {
        TestEnv().use { env -> assertEquals(404, dav(env, "PROPFIND", "/dav/").statusCode()) }
        enabled().use { env ->
            val r = dav(env, "PROPFIND", "/dav/", auth = false)
            assertEquals(401, r.statusCode())
            assertTrue(r.headers().firstValue("www-authenticate").get().startsWith("Basic"))
            assertEquals(401, dav(env, "PROPFIND", "/dav/", pw = "salah-salah").statusCode())
            assertEquals(207, dav(env, "PROPFIND", "/dav/").statusCode())
        }
    }

    @Test fun `kapasitas yang dilaporkan adalah kapasitas aktual SSD`() = enabled().use { env ->
        env.backend.fakeCapacity = Capacity(totalBytes = 1_000_204_886_016, freeBytes = 484_000_000_000)
        File(env.root, "a.txt").writeText("x")
        File(env.root, ".cloudssd").mkdirs()
        val r = dav(env, "PROPFIND", "/dav/", headers = mapOf("Depth" to "1"))
        assertEquals(207, r.statusCode())
        assertTrue(r.body().contains("<D:quota-available-bytes>484000000000</D:quota-available-bytes>"))
        assertTrue(r.body().contains("<D:quota-used-bytes>516204886016</D:quota-used-bytes>"))
        assertTrue(r.body().contains("<D:href>/dav/a.txt</D:href>"))
        assertFalse("folder sistem harus tersembunyi", r.body().contains(".cloudssd"))
    }

    @Test fun `unggah, unduh, timpa, folder, pindah, salin, hapus`() = enabled().use { env ->
        val data = ByteArray(3_000_000) { (it % 251).toByte() }
        assertEquals(201, dav(env, "MKCOL", "/dav/Dokumen").statusCode())
        assertEquals(405, dav(env, "MKCOL", "/dav/Dokumen").statusCode())
        assertEquals(409, dav(env, "MKCOL", "/dav/tidak/ada").statusCode())
        assertEquals(201, dav(env, "PUT", "/dav/Dokumen/data%20saya.bin", data).statusCode())
        assertArrayEquals(data, File(env.root, "Dokumen/data saya.bin").readBytes())
        val g = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${env.port}/dav/Dokumen/data%20saya.bin"))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$ADMIN:$ADMIN_PW".toByteArray())).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())
        assertArrayEquals(data, g.body())
        assertEquals(204, dav(env, "PUT", "/dav/Dokumen/data%20saya.bin", "baru".toByteArray()).statusCode())
        assertEquals("baru", File(env.root, "Dokumen/data saya.bin").readText())
        val dest = mapOf("Destination" to "http://127.0.0.1:${env.port}/dav/salinan.bin")
        assertEquals(201, dav(env, "COPY", "/dav/Dokumen/data%20saya.bin", headers = dest).statusCode())
        assertEquals(412, dav(env, "COPY", "/dav/Dokumen/data%20saya.bin", headers = dest + ("Overwrite" to "F")).statusCode())
        assertEquals(201, dav(env, "MOVE", "/dav/salinan.bin", headers = mapOf("Destination" to "http://127.0.0.1:${env.port}/dav/Dokumen/pindah.bin")).statusCode())
        assertTrue(File(env.root, "Dokumen/pindah.bin").exists())
        assertFalse(File(env.root, "salinan.bin").exists())
        // Ganti nama folder
        assertEquals(201, dav(env, "MOVE", "/dav/Dokumen/", headers = mapOf("Destination" to "/dav/Arsip/")).statusCode())
        assertTrue(File(env.root, "Arsip/pindah.bin").exists())
        assertEquals(204, dav(env, "DELETE", "/dav/Arsip").statusCode())
        assertFalse(File(env.root, "Arsip").exists())
        assertEquals(404, dav(env, "DELETE", "/dav/Arsip").statusCode())
        // File sementara tidak tertinggal
        assertTrue(File(env.root, ".cloudssd/uploads").listFiles()!!.isEmpty())
    }

    @Test fun `keamanan path dan izin pengguna`() = enabled { it.copy(multiUser = true) }.use { env ->
        File(env.root, "rahasia-admin.txt").writeText("x")
        val admin = env.admin()
        admin.post("/api/admin/users", mapOf("username" to "tamu", "password" to "sandi-tamu-123", "canWrite" to false, "quotaBytes" to 5_000_000))
        val r = dav(env, "PROPFIND", "/dav/", user = "tamu", pw = "sandi-tamu-123", headers = mapOf("Depth" to "1"))
        assertEquals(207, r.statusCode())
        assertFalse(r.body().contains("rahasia-admin"))
        assertTrue("kuota pengguna dilaporkan", r.body().contains("<D:quota-available-bytes>5000000</D:quota-available-bytes>"))
        assertEquals(403, dav(env, "PUT", "/dav/x.txt", "x".toByteArray(), user = "tamu", pw = "sandi-tamu-123").statusCode())
        assertEquals(400, dav(env, "GET", "/dav/..%2frahasia-admin.txt", user = "tamu", pw = "sandi-tamu-123").statusCode())
        assertEquals(403, dav(env, "PROPFIND", "/dav/.cloudssd").statusCode())
        // Tujuan MOVE di luar drive ditolak
        File(env.root, "a.txt").writeText("a")
        assertEquals(502, dav(env, "MOVE", "/dav/a.txt", headers = mapOf("Destination" to "http://127.0.0.1:${env.port}/api/x")).statusCode())
        // Mode pribadi: pengguna biasa ditolak
        admin.send("PATCH", "/api/admin/settings", json = mapOf("multiUser" to false))
        assertEquals(403, dav(env, "PROPFIND", "/dav/", user = "tamu", pw = "sandi-tamu-123").statusCode())
    }

    @Test fun `percobaan login dibatasi`() = enabled().use { env ->
        repeat(5) { assertEquals(401, dav(env, "PROPFIND", "/dav/", pw = "salah-$it-xxxx").statusCode()) }
        assertEquals(429, dav(env, "PROPFIND", "/dav/", pw = "salah-6-xxxx").statusCode())
    }

    @Test fun `SSD terputus dan disk penuh dilaporkan jujur`() = enabled().use { env ->
        File(env.root, "lama.txt").writeText("data lama penting")
        env.backend.noSpaceAfterBytes = 100_000
        val r = dav(env, "PUT", "/dav/lama.txt", ByteArray(500_000))
        assertEquals(507, r.statusCode())
        assertEquals("file lama tidak boleh rusak", "data lama penting", File(env.root, "lama.txt").readText())
        env.backend.noSpaceAfterBytes = null
        env.backend.connected = false
        assertEquals(503, dav(env, "PROPFIND", "/dav/").statusCode())
        assertEquals(503, dav(env, "PUT", "/dav/baru.txt", "x".toByteArray()).statusCode())
    }

    @Test fun `LOCK dan UNLOCK untuk klien Windows dan macOS`() = enabled().use { env ->
        val body = "<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner>win</D:owner></D:lockinfo>"
        val r = dav(env, "LOCK", "/dav/baru.docx", body.toByteArray(), headers = mapOf("Content-Type" to "application/xml"))
        assertEquals(201, r.statusCode())
        val token = r.headers().firstValue("lock-token").get()
        assertTrue(token.contains("opaquelocktoken:"))
        assertTrue(File(env.root, "baru.docx").exists())
        assertEquals(204, dav(env, "UNLOCK", "/dav/baru.docx", headers = mapOf("Lock-Token" to token)).statusCode())
        val o = dav(env, "OPTIONS", "/", auth = false)
        assertEquals("1, 2", o.headers().firstValue("dav").get())
    }
}
