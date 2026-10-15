package id.cloudssd.core

import id.cloudssd.core.api.FileResponses
import id.cloudssd.core.util.Json
import id.cloudssd.core.util.ManualClock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareAndMiscTest {

    @Test fun `tautan berbagi, kata sandi, hanya-lihat, kedaluwarsa, dan pencabutan`() {
        val clock = ManualClock(System.currentTimeMillis())
        TestEnv(clock).use { env ->
            val c = env.admin()
            val img = ByteArray(5000) { it.toByte() }
            c.upload("/", "kucing.jpg", img)
            c.upload("/", "kontrak.pdf", "PDF".toByteArray())

            // Tautan biasa
            val s1 = c.post("/api/shares", mapOf("path" to "/kucing.jpg", "expiresHours" to 24, "allowDownload" to true))
            assertEquals(201, s1.status)
            val token1 = (s1.json["url"] as String).removePrefix("/s/")
            val anon = env.client()
            assertEquals(200, anon.get("/s/$token1").status) // halaman HTML
            val meta = anon.get("/api/public/share/$token1").json
            assertEquals("kucing.jpg", meta["name"])
            val (st, body) = anon.getBytes("/api/public/share/$token1/content?download=1")
            assertEquals(200, st)
            assertArrayEquals(img, body)

            // Hanya lihat + kata sandi
            val s2 = c.post("/api/shares", mapOf("path" to "/kucing.jpg", "password" to "rahasia1", "allowDownload" to false))
            val token2 = (s2.json["url"] as String).removePrefix("/s/")
            val anon2 = env.client()
            assertEquals(true, anon2.get("/api/public/share/$token2").json["needsPassword"])
            assertEquals(401, anon2.get("/api/public/share/$token2/content").status)
            assertEquals(403, anon2.post("/api/public/share/$token2/unlock", mapOf("password" to "salah")).status)
            assertEquals(200, anon2.post("/api/public/share/$token2/unlock", mapOf("password" to "rahasia1")).status)
            assertEquals(200, anon2.get("/api/public/share/$token2/content").status)               // pratinjau boleh
            assertEquals(403, anon2.get("/api/public/share/$token2/content?download=1").status)    // unduh tidak
            // Cookie hibah tidak berlaku untuk tautan lain
            val s3 = c.post("/api/shares", mapOf("path" to "/kontrak.pdf", "password" to "lainnya1"))
            val token3 = (s3.json["url"] as String).removePrefix("/s/")
            assertEquals(401, anon2.get("/api/public/share/$token3/content").status)

            // Kedaluwarsa
            clock.advance(25L * 3600_000)
            assertEquals(404, anon.get("/api/public/share/$token1").status)

            // Cabut
            val c2 = env.admin()
            val id2 = s2.json["id"] as String
            assertEquals(200, c2.send("DELETE", "/api/shares/$id2").status)
            assertEquals(404, anon2.get("/api/public/share/$token2/content").status)
            // Token tebakan
            assertEquals(404, anon.get("/api/public/share/AAAAAAAAAAAAAAAAAAAAAAAA").status)
        }
    }

    @Test fun `tautan berbagi tetap mengikuti kebijakan jaringan`() = TestEnv().use { env ->
        // Default hanya loopback; klien dari IP publik ditolak di lapisan jaringan (lihat SecurityTest.kebijakan jaringan).
        assertTrue(!env.server.settings.current.lanAccess && !env.server.settings.current.vpnAccess)
    }

    @Test fun `JSON bolak-balik`() {
        val v = mapOf("a" to 1L, "b" to listOf(true, null, "x\"y\n<tag>"), "c" to mapOf("d" to 1.5))
        assertEquals(v, Json.parse(Json.stringify(v)))
        assertEquals("ü€", Json.parse("\"\\u00fc\\u20ac\""))
        try { Json.parse("{\"a\":}"); throw AssertionError() } catch (_: IllegalArgumentException) {}
        assertTrue(!Json.stringify("</script>").contains("</"))
    }

    @Test fun `parsing header Range`() {
        assertEquals(0L to 99L, FileResponses.parseRange("bytes=0-99", 1000))
        assertEquals(900L to 999L, FileResponses.parseRange("bytes=-100", 1000))
        assertEquals(500L to 999L, FileResponses.parseRange("bytes=500-", 1000))
        assertEquals(500L to 999L, FileResponses.parseRange("bytes=500-5000", 1000))
        assertNull(FileResponses.parseRange("bytes=1000-", 1000))
        assertEquals(-1L to -1L, FileResponses.parseRange("bytes=0-1,5-6", 1000))
    }

    @Test fun `dashboard menampilkan data aktual`() = TestEnv().use { env ->
        val c = env.admin()
        c.post("/api/files/mkdir", mapOf("path" to "/", "name" to "A"))
        c.upload("/A", "1.txt", ByteArray(10))
        c.upload("/", "2.txt", ByteArray(20))
        env.server.stats.scanNow(id.cloudssd.core.storage.VPath.ROOT)
        val d = c.get("/api/dashboard").json
        val stats = d["stats"] as Map<*, *>
        assertEquals(2L, stats["files"])
        assertEquals(1L, stats["folders"])
        assertEquals(30L, stats["bytes"])
        val storage = d["storage"] as Map<*, *>
        assertTrue((storage["totalBytes"] as Long) > 0)
        assertTrue((d["activity"] as List<*>).isNotEmpty())
        assertEquals(null, (d["remote"] as Map<*, *>)["lastVerifiedAt"]) // tidak ada klaim akses jarak jauh tanpa bukti
    }

    @Test fun `HTTPS dengan sertifikat PKCS12 yang diimpor`() {
        val dir = tempDir("cssd-tls")
        val p12 = java.io.File(dir, "cloudssd.p12")
        val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool").path
        val proc = ProcessBuilder(keytool, "-genkeypair", "-alias", "cloudssd", "-keyalg", "EC", "-groupname", "secp256r1",
            "-validity", "30", "-storetype", "PKCS12", "-keystore", p12.path, "-storepass", "sandi-p12", "-dname", "CN=CloudSSD",
            "-ext", "SAN=ip:127.0.0.1").redirectErrorStream(true).start()
        assertEquals(0, proc.waitFor())
        val httpsPort = freePort()
        TestEnv().use { env ->
            try { env.server.importTlsCertificate(p12.readBytes(), "salah"); throw AssertionError("harus gagal") } catch (_: Exception) {}
            env.server.importTlsCertificate(p12.readBytes(), "sandi-p12")
            assertTrue(env.server.tlsCertificateInfo()!!.contains("CN=CloudSSD"))
            env.server.stop()
            env.server.settings.update { it.copy(httpsEnabled = true, httpsPort = httpsPort) }
            env.server.start()
            // Klien uji mempercayai sertifikat yang baru dibuat tersebut (bukan menonaktifkan verifikasi).
            val ks = java.security.KeyStore.getInstance("PKCS12").apply { p12.inputStream().use { load(it, "sandi-p12".toCharArray()) } }
            val trust = java.security.KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("c", ks.getCertificate("cloudssd")) }
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
            val client = java.net.http.HttpClient.newBuilder().sslContext(ssl).build()
            val r = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("https://127.0.0.1:$httpsPort/api/ping")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals(200, r.statusCode())
            // Cookie sesi lewat HTTPS diberi atribut Secure
            val login = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI("https://127.0.0.1:$httpsPort/api/login"))
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    Json.stringify(mapOf("username" to ADMIN, "password" to ADMIN_PW)))).build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            assertTrue(login.headers().firstValue("set-cookie").get().contains("Secure"))
        }
    }

    @Test fun `antarmuka web tersedia`()= TestEnv().use { env ->
        val c = env.client()
        val idx = c.get("/")
        assertEquals(200, idx.status)
        assertTrue(idx.body.contains("/assets/app.js"))
        assertTrue(idx.header("content-security-policy")!!.contains("script-src 'self'"))
        assertEquals(200, c.get("/assets/app.js").status)
        assertEquals(200, c.get("/assets/app.css").status)
        assertEquals(404, c.get("/assets/tidak-ada.js").status)
    }
}
