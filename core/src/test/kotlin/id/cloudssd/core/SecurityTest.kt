package id.cloudssd.core

import id.cloudssd.core.server.ClientNetwork
import id.cloudssd.core.server.NetworkPolicy
import id.cloudssd.core.server.ServerSettings
import id.cloudssd.core.storage.InvalidNameException
import id.cloudssd.core.storage.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files

class SecurityTest {

    @Test fun `permintaan API tanpa login ditolak`() = TestEnv().use { env ->
        val c = env.client()
        for (p in listOf("/api/files?path=/", "/api/dashboard", "/api/files/download?path=/x", "/api/admin/users", "/api/shares", "/api/uploads"))
            assertEquals(p, 401, c.get(p).status)
        assertEquals(401, c.post("/api/files/delete", mapOf("paths" to listOf("/"))).status)
        // Cookie palsu
        c.cookies["cssd_sid"] = "token-palsu"
        assertEquals(401, c.get("/api/files?path=/").status)
    }

    @Test fun `CSRF dan origin lintas situs ditolak`() = TestEnv().use { env ->
        val c = env.admin()
        File(env.root, "penting.txt").writeText("x")
        val noCsrf = c.send("POST", "/api/files/delete", json = mapOf("paths" to listOf("/penting.txt")), csrfHeader = false)
        assertEquals(403, noCsrf.status)
        val badOrigin = c.send("POST", "/api/files/delete", json = mapOf("paths" to listOf("/penting.txt")), headers = mapOf("Origin" to "http://evil.example"))
        assertEquals(403, badOrigin.status)
        assertTrue(File(env.root, "penting.txt").exists())
        // Login lintas situs / bukan JSON ditolak
        assertEquals(403, env.client().send("POST", "/api/login", json = mapOf("username" to ADMIN, "password" to ADMIN_PW), headers = mapOf("Origin" to "http://evil.example")).status)
        assertEquals(415, env.client().send("POST", "/api/login", raw = "username=admin".toByteArray(), headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")).status)
        // Origin yang sama diterima
        val same = c.send("POST", "/api/files/mkdir", json = mapOf("path" to "/", "name" to "ok"), headers = mapOf("Origin" to "http://127.0.0.1:${env.port}"))
        assertEquals(201, same.status)
    }

    @Test fun `path traversal dicegah`() = TestEnv().use { env ->
        val outside = File(env.root.parentFile, "rahasia-${System.nanoTime()}.txt").apply { writeText("JANGAN") ; deleteOnExit() }
        val c = env.admin()
        val attempts = listOf(
            "/../${outside.name}", "../${outside.name}", "/a/../../${outside.name}", "/%2e%2e/${outside.name}",
            "/..%2f${outside.name}", "\\..\\${outside.name}", "/./x", "/.cloudssd/uploads", "/.cloudssd",
        )
        for (p in attempts) {
            val r = c.get("/api/files/download?path=" + c.q(p))
            assertTrue("path $p -> ${r.status}", r.status in setOf(400, 403, 404))
            assertFalse(r.body.contains("JANGAN"))
        }
        // Traversal pada nama file unggahan & folder
        assertEquals(400, c.post("/api/uploads", mapOf("path" to "/", "name" to "../evil.txt", "size" to 1)).status)
        assertEquals(400, c.post("/api/files/mkdir", mapOf("path" to "/../", "name" to "x")).status)
        assertEquals(400, c.post("/api/files/rename", mapOf("path" to "/", "name" to "x")).status.let { if (it == 400) 400 else it })
        // Path mentah di URL
        Socket("127.0.0.1", env.port).use { s ->
            s.getOutputStream().write("GET /assets/../../../../etc/passwd HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertFalse(resp.contains("root:"))
        }
        assertEquals("JANGAN", outside.readText())
    }

    @Test fun `symlink keluar dari SSD tidak diikuti`() = TestEnv().use { env ->
        val secret = tempDir("cssd-secret").also { File(it, "kunci.txt").writeText("RAHASIA") }
        try { Files.createSymbolicLink(File(env.root, "tautan").toPath(), secret.toPath()) } catch (e: Exception) { return@use }
        val c = env.admin()
        val list = c.get("/api/files?path=/").json["items"] as List<*>
        assertTrue("symlink tidak boleh tampil", list.none { (it as Map<*, *>)["name"] == "tautan" })
        val r = c.get("/api/files/download?path=/tautan/kunci.txt")
        assertNotEquals(200, r.status)
        assertFalse(r.body.contains("RAHASIA"))
    }

    @Test fun `VPath menolak segmen berbahaya`() {
        for (bad in listOf("..", "a/../b", "a/./b", "a\\b", "a\u0000b", "x:y")) {
            try { VPath.parse(bad); fail("harus ditolak: $bad") } catch (_: InvalidNameException) {}
        }
        assertEquals("/a/b", VPath.parse("//a///b/").toString())
        assertTrue(VPath.parse("/a/b/c").startsWith(VPath.parse("/a")))
        assertFalse(VPath.parse("/ab").startsWith(VPath.parse("/a")))
    }

    @Test fun `izin dan isolasi pengguna`() = TestEnv(configure = { it.copy(multiUser = true) }).use { env ->
        val admin = env.admin()
        File(env.root, "milik-admin.txt").writeText("admin")
        assertEquals(201, admin.post("/api/admin/users", mapOf("username" to "budi", "password" to "sandi-budi-123")).status)
        assertEquals(201, admin.post("/api/admin/users", mapOf("username" to "tamu", "password" to "sandi-tamu-123", "canWrite" to false)).status)
        val budi = env.client().also { assertEquals(200, it.login("budi", "sandi-budi-123").status) }
        // Budi terkurung di /users/budi
        assertEquals(200, budi.upload("/", "punya-budi.txt", "b".toByteArray()).status)
        assertTrue(File(env.root, "users/budi/punya-budi.txt").exists())
        val items = budi.get("/api/files?path=/").json["items"] as List<*>
        assertEquals(listOf("punya-budi.txt"), items.map { (it as Map<*, *>)["name"] })
        assertEquals(400, budi.get("/api/files/download?path=" + budi.q("/../../milik-admin.txt")).status)
        assertEquals(404, budi.get("/api/files/download?path=/milik-admin.txt").status)
        // Bukan admin -> endpoint admin ditolak
        assertEquals(403, budi.get("/api/admin/users").status)
        assertEquals(403, budi.send("PATCH", "/api/admin/users/x", json = mapOf("disabled" to false)).status)
        // Tamu tanpa izin tulis
        val tamu = env.client().also { assertEquals(200, it.login("tamu", "sandi-tamu-123").status) }
        assertEquals(403, tamu.upload("/", "x.txt", "x".toByteArray()).status)
        assertEquals(403, tamu.post("/api/files/mkdir", mapOf("path" to "/", "name" to "x")).status)
        assertEquals(200, tamu.get("/api/files?path=/").status)
        // Upload milik orang lain tidak dapat diakses
        val init = budi.post("/api/uploads", mapOf("path" to "/", "name" to "z.bin", "size" to 10))
        val id = init.json["id"] as String
        assertEquals(404, tamu.get("/api/uploads/$id").status)
        // Menonaktifkan akun langsung mengeluarkan sesi
        val budiId = (admin.get("/api/admin/users").json["users"] as List<*>).map { it as Map<*, *> }.first { it["username"] == "budi" }["id"]
        assertEquals(200, admin.send("PATCH", "/api/admin/users/$budiId", json = mapOf("disabled" to true)).status)
        assertEquals(401, budi.get("/api/me").status)
        assertEquals(403, env.client().login("budi", "sandi-budi-123").status)
    }

    @Test fun `kuota dihitung dari data aktual`() = TestEnv(configure = { it.copy(multiUser = true) }).use { env ->
        val admin = env.admin()
        admin.post("/api/admin/users", mapOf("username" to "sari", "password" to "sandi-sari-123", "quotaBytes" to 1_000_000))
        val sari = env.client().also { it.login("sari", "sandi-sari-123") }
        assertEquals(200, sari.upload("/", "a.bin", ByteArray(600_000)).status)
        val over = sari.upload("/", "b.bin", ByteArray(600_000))
        assertEquals(507, over.status)
        assertEquals("quota", over.json["code"])
        assertFalse(File(env.root, "users/sari/b.bin").exists())
        // Setelah menghapus, kuota tersedia lagi
        assertEquals(200, sari.post("/api/files/delete", mapOf("paths" to listOf("/a.bin"))).status)
        assertEquals(200, sari.upload("/", "b.bin", ByteArray(600_000)).status)
    }

    @Test fun `mode pribadi hanya mengizinkan admin`() = TestEnv(configure = { it.copy(multiUser = true) }).use { env ->
        val admin = env.admin()
        admin.post("/api/admin/users", mapOf("username" to "rina", "password" to "sandi-rina-123"))
        val rina = env.client().also { assertEquals(200, it.login("rina", "sandi-rina-123").status) }
        assertEquals(200, admin.send("PATCH", "/api/admin/settings", json = mapOf("multiUser" to false)).status)
        assertEquals(403, rina.get("/api/files?path=/").status)
        assertEquals(403, env.client().login("rina", "sandi-rina-123").status)
    }

    @Test fun `kebijakan jaringan`() {
        val s = ServerSettings()
        fun ip(a: String) = InetAddress.getByName(a)
        assertEquals(ClientNetwork.LOOPBACK, NetworkPolicy.classify(ip("127.0.0.1")))
        assertEquals(ClientNetwork.LAN, NetworkPolicy.classify(ip("192.168.1.20")))
        assertEquals(ClientNetwork.LAN, NetworkPolicy.classify(ip("10.0.0.5")))
        assertEquals(ClientNetwork.VPN, NetworkPolicy.classify(ip("100.101.102.103")))
        assertEquals(ClientNetwork.VPN, NetworkPolicy.classify(ip("fd7a:115c:a1e0::1")))
        assertEquals(ClientNetwork.PUBLIC, NetworkPolicy.classify(ip("8.8.8.8")))
        assertEquals(ClientNetwork.LAN, NetworkPolicy.classify(ip("::ffff:192.168.1.9")))
        // Default: hanya tablet sendiri
        assertTrue(NetworkPolicy.isAllowed(ip("127.0.0.1"), s))
        assertFalse(NetworkPolicy.isAllowed(ip("192.168.1.20"), s))
        assertFalse(NetworkPolicy.isAllowed(ip("100.100.1.1"), s))
        val lan = s.copy(lanAccess = true)
        assertTrue(NetworkPolicy.isAllowed(ip("192.168.1.20"), lan))
        // Internet publik tidak pernah diizinkan
        assertFalse(NetworkPolicy.isAllowed(ip("8.8.8.8"), lan.copy(vpnAccess = true)))
        // DNS rebinding
        assertTrue(NetworkPolicy.isHostAllowed("192.168.1.5:8080", s))
        assertTrue(NetworkPolicy.isHostAllowed("[fd7a::1]:8080", s))
        assertFalse(NetworkPolicy.isHostAllowed("evil.example:8080", s))
        assertFalse(NetworkPolicy.isHostAllowed("pad.tail1.ts.net", s))
        assertTrue(NetworkPolicy.isHostAllowed("pad.tail1.ts.net", s.copy(vpnAccess = true)))
        assertTrue(NetworkPolicy.isHostAllowed("files.home", s.copy(extraHosts = listOf("files.home"))))
    }

    @Test fun `header Host asing ditolak`() = TestEnv().use { env ->
        Socket("127.0.0.1", env.port).use { s ->
            s.getOutputStream().write("GET /api/status HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n".toByteArray())
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(resp, resp.startsWith("HTTP/1.1 403"))
        }
    }

    @Test fun `konten pengguna disajikan aman`() = TestEnv().use { env ->
        val c = env.admin()
        c.upload("/", "x.html", "<script>alert(1)</script>".toByteArray())
        val r = c.get("/api/files/download?path=/x.html&inline=1")
        assertEquals(200, r.status)
        assertEquals("application/octet-stream", r.header("content-type"))
        assertTrue(r.header("content-security-policy")!!.startsWith("sandbox"))
        assertEquals("nosniff", r.header("x-content-type-options"))
    }
}
