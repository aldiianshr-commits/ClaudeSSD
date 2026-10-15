package id.cloudssd.core

import id.cloudssd.core.auth.LoginLimiter
import id.cloudssd.core.auth.PasswordHasher
import id.cloudssd.core.server.CloudServer
import id.cloudssd.core.storage.LocalFileBackend
import id.cloudssd.core.util.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthTest {

    @Test fun `login dan logout`() = TestEnv().use { env ->
        val c = env.client()
        assertEquals(401, c.get("/api/me").status)
        val r = c.login()
        assertEquals(200, r.status)
        val cookie = r.header("set-cookie")!!
        assertTrue("cookie harus HttpOnly", cookie.contains("HttpOnly"))
        assertTrue("cookie harus SameSite=Strict", cookie.contains("SameSite=Strict"))
        assertEquals(200, c.get("/api/me").status)
        val oldCookies = HashMap(c.cookies)
        assertEquals(200, c.post("/api/logout").status)
        assertEquals(401, c.get("/api/me").status)
        // Token lama tidak bisa dipakai ulang setelah logout.
        val replay = env.client().also { it.cookies.putAll(oldCookies) }
        assertEquals(401, replay.get("/api/me").status)
    }

    @Test fun `kata sandi salah ditolak dan login dibatasi`() = TestEnv().use { env ->
        val c = env.client()
        repeat(5) { assertEquals(401, c.login(ADMIN, "salah-salah-salah").status) }
        val locked = c.login(ADMIN, "salah-salah-salah")
        assertEquals(429, locked.status)
        // Saat terkunci, kata sandi benar pun ditolak sementara.
        assertEquals(429, c.login().status)
    }

    @Test fun `pembatas login berbasis waktu`() {
        val clock = ManualClock()
        val l = LoginLimiter(clock)
        repeat(4) { l.recordFailure("ip:1") }
        assertEquals(0, l.retryAfterSeconds("ip:1"))
        l.recordFailure("ip:1")
        assertEquals(60, l.retryAfterSeconds("ip:1"))
        clock.advance(61_000)
        assertEquals(0, l.retryAfterSeconds("ip:1"))
        l.recordFailure("ip:1")
        assertEquals(120, l.retryAfterSeconds("ip:1"))
        l.recordSuccess("ip:1")
        assertEquals(0, l.retryAfterSeconds("ip:1"))
    }

    @Test fun `kata sandi disimpan sebagai hash bukan teks biasa`() = TestEnv().use { env ->
        val raw = File(env.data, "users.json").readText()
        assertFalse(raw.contains(ADMIN_PW))
        assertTrue(raw.contains("pbkdf2_sha256"))
        val h = PasswordHasher(1000)
        val a = h.hash("abc".toCharArray()); val b = h.hash("abc".toCharArray())
        assertNotEquals("salt harus acak", a, b)
        assertTrue(h.verify("abc".toCharArray(), a))
        assertFalse(h.verify("abd".toCharArray(), a))
    }

    @Test fun `ganti kata sandi mengeluarkan sesi lain`() = TestEnv().use { env ->
        val a = env.admin(); val b = env.admin()
        val r = a.post("/api/me/password", mapOf("current" to ADMIN_PW, "newPassword" to "kata-sandi-baru-456"))
        assertEquals(200, r.status)
        a.csrf = r.json["csrf"] as String
        assertEquals(200, a.get("/api/me").status)       // perangkat ini mendapat sesi baru
        assertEquals(401, b.get("/api/me").status)       // perangkat lain keluar
        assertEquals(401, env.client().login().status)    // sandi lama tidak berlaku
        assertEquals(200, env.client().login(ADMIN, "kata-sandi-baru-456").status)
        // Sandi saat ini salah -> ditolak
        assertEquals(403, a.post("/api/me/password", mapOf("current" to "keliru-sekali", "newPassword" to "x".repeat(12) + "abcd")).status)
    }

    @Test fun `tidak ada admin bawaan dan setup butuh kode`() {
        val data = tempDir("cssd-setup"); val root = tempDir("cssd-r")
        val port = freePort()
        val s = CloudServer(data, { LocalFileBackend(root) }, hashIterations = 1000)
        s.settings.update { it.copy(port = port) }
        s.start()
        try {
            val c = Client(port)
            assertEquals(true, c.get("/api/status").json["setupRequired"])
            assertEquals(401, c.login("admin", "admin").status)
            val code = s.setupCode
            assertNotNull(code)
            assertEquals(403, c.post("/api/setup", mapOf("code" to "00000000x", "username" to "boss", "password" to "panjang-sekali-1")).status)
            assertEquals(400, c.post("/api/setup", mapOf("code" to code, "username" to "boss", "password" to "pendek")).status)
            assertEquals(200, c.post("/api/setup", mapOf("code" to code, "username" to "boss", "password" to "panjang-sekali-1")).status)
            assertNull(s.setupCode)
            // Setup kedua ditolak.
            assertEquals(409, c.post("/api/setup", mapOf("code" to code, "username" to "jahat", "password" to "panjang-sekali-2")).status)
            assertEquals(200, c.login("boss", "panjang-sekali-1").status)
        } finally { s.shutdown() }
    }

    @Test fun `sesi bertahan setelah server dijalankan ulang`() = TestEnv().use { env ->
        val c = env.admin()
        env.server.stop()
        env.server.start()
        assertEquals(200, c.get("/api/me").status)
    }
}
