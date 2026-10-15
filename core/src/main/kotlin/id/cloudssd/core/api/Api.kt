package id.cloudssd.core.api

import id.cloudssd.core.auth.Role
import id.cloudssd.core.auth.Session
import id.cloudssd.core.auth.Tokens
import id.cloudssd.core.auth.User
import id.cloudssd.core.auth.ValidationException
import id.cloudssd.core.http.ApiError
import id.cloudssd.core.http.Body
import id.cloudssd.core.http.HttpRequest
import id.cloudssd.core.http.HttpResponse
import id.cloudssd.core.http.PayloadTooLarge
import id.cloudssd.core.server.ClientNetwork
import id.cloudssd.core.server.CloudServer
import id.cloudssd.core.server.ConflictPolicy
import id.cloudssd.core.server.NetworkPolicy
import id.cloudssd.core.server.OffsetMismatchException
import id.cloudssd.core.server.RemoteAccessCheck
import id.cloudssd.core.server.Share
import id.cloudssd.core.server.Upload
import id.cloudssd.core.server.UploadConflictException
import id.cloudssd.core.storage.AlreadyExistsException
import id.cloudssd.core.storage.FileEntry
import id.cloudssd.core.storage.InvalidNameException
import id.cloudssd.core.storage.NameRules
import id.cloudssd.core.storage.NoSpaceException
import id.cloudssd.core.storage.NotFoundException
import id.cloudssd.core.storage.StorageException
import id.cloudssd.core.storage.StorageUnavailableException
import id.cloudssd.core.storage.VPath
import id.cloudssd.core.util.bool
import id.cloudssd.core.util.list
import id.cloudssd.core.util.long
import id.cloudssd.core.util.str
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Seluruh endpoint HTTP CloudSSD. Setiap request melewati:
 * 1) filter jaringan (IP klien & header Host)  2) autentikasi sesi  3) CSRF untuk request yang mengubah data
 * 4) validasi path & izin pengguna.
 */
class Api(private val server: CloudServer) {

    private class Ctx(val req: HttpRequest, val user: User, val session: Session)

    /** Hibah akses tautan berbagi yang dilindungi kata sandi: grantToken -> (shareId, kedaluwarsa). */
    private val shareGrants = ConcurrentHashMap<String, Pair<String, Long>>()
    private val webdav = WebDav(server)

    fun handle(req: HttpRequest): HttpResponse {
        val settings = server.settings.current
        if (!NetworkPolicy.isAllowed(req.remoteAddress, settings)) {
            return HttpResponse.text(403, "Akses dari jaringan ini tidak diizinkan oleh CloudSSD.").also { it.closeConnection = true }
        }
        if (!NetworkPolicy.isHostAllowed(req.header("host"), settings)) {
            return HttpResponse.text(403, "Nama host tidak diizinkan. Buka CloudSSD memakai alamat IP-nya.").also { it.closeConnection = true }
        }
        if (NetworkPolicy.classify(req.remoteAddress) == ClientNetwork.VPN) {
            server.lastRemoteAccess = RemoteAccessCheck(server.clock.now(), req.remoteIp)
        }
        val res = try {
            route(req)
        } catch (e: Throwable) {
            mapError(e, req)
        }
        return secureHeaders(res)
    }

    private fun secureHeaders(res: HttpResponse): HttpResponse {
        res.setHeader("X-Content-Type-Options", "nosniff")
        res.setHeader("Referrer-Policy", "no-referrer")
        res.setHeader("X-Frame-Options", "DENY")
        if (!res.headers.containsKey("Content-Security-Policy")) {
            res.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; style-src 'self'; script-src 'self'; " +
                    "connect-src 'self'; frame-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"
            )
        }
        return res
    }

    private fun mapError(e: Throwable, req: HttpRequest): HttpResponse = when (e) {
        is ApiError -> HttpResponse.error(e.status, e.message, e.code, e.extra)
        is ValidationException -> HttpResponse.error(400, e.message ?: "Data tidak valid", "invalid")
        is IllegalArgumentException -> HttpResponse.error(400, e.message ?: "Data tidak valid", "invalid")
        is InvalidNameException -> HttpResponse.error(400, e.message ?: "Nama tidak valid", "invalid_name")
        is UploadConflictException -> HttpResponse.error(409, e.message ?: "Konflik", "conflict",
            mapOf("existing" to e.existingName, "suggested" to e.suggestedName))
        is OffsetMismatchException -> HttpResponse.error(409, e.message ?: "Offset", "offset", mapOf("expected" to e.expected)).also { it.closeConnection = true }
        is AlreadyExistsException -> HttpResponse.error(409, e.message ?: "Sudah ada", "exists")
        is NotFoundException -> HttpResponse.error(404, e.message ?: "Tidak ditemukan", "not_found")
        is NoSpaceException -> HttpResponse.error(507, e.message ?: "Penyimpanan penuh", "no_space").also { it.closeConnection = true }
        is StorageUnavailableException -> HttpResponse.error(503, e.message ?: "SSD tidak tersedia", "storage_unavailable").also { it.closeConnection = true }
        is StorageException -> {
            server.logs.error("Kesalahan penyimpanan pada ${req.method} ${req.path}", e)
            HttpResponse.error(500, "Operasi penyimpanan gagal: ${e.message}", "storage_error").also { it.closeConnection = true }
        }
        is PayloadTooLarge -> HttpResponse.error(413, "Data terlalu besar", "too_large").also { it.closeConnection = true }
        is IOException -> HttpResponse.error(400, "Koneksi terputus atau data tidak lengkap", "connection").also { it.closeConnection = true }
        else -> {
            server.logs.error("Kesalahan internal pada ${req.method} ${req.path}", e)
            HttpResponse.error(500, "Kesalahan server internal", "internal")
        }
    }

    // ------------------------------------------------------------------ routing

    private fun route(req: HttpRequest): HttpResponse {
        val p = req.path
        val m = req.method
        if (m == "OPTIONS" || p == WebDav.PREFIX || p.startsWith(WebDav.PREFIX + "/")) return webdav.handle(req)
        if (!p.startsWith("/api/")) {
            if (m != "GET" && m != "HEAD") throw ApiError(405, "Metode tidak diizinkan")
            return StaticFiles.serve(p)
        }
        val seg = p.removePrefix("/api/").trimEnd('/').split('/')

        // ---- Endpoint publik
        when {
            m == "GET" && p == "/api/ping" -> return HttpResponse.ok(mapOf("ok" to true, "app" to "CloudSSD"))
            m == "GET" && p == "/api/status" -> return HttpResponse.ok(mapOf(
                "setupRequired" to !server.users.hasAdmin(), "multiUser" to server.settings.current.multiUser, "version" to VERSION))
            m == "POST" && p == "/api/setup" -> return setup(req)
            m == "POST" && p == "/api/login" -> return login(req)
            seg[0] == "public" && seg.getOrNull(1) == "share" && seg.size >= 3 -> return publicShare(req, seg)
        }

        // ---- Endpoint terautentikasi
        val ctx = authenticate(req)
        if (m != "GET" && m != "HEAD") checkCsrf(ctx)

        return when {
            m == "POST" && p == "/api/logout" -> logout(ctx)
            m == "GET" && p == "/api/me" -> me(ctx)
            m == "POST" && p == "/api/me/password" -> changePassword(ctx)
            m == "GET" && p == "/api/me/sessions" -> listSessions(ctx)
            m == "POST" && p == "/api/me/sessions/revoke" -> revokeSession(ctx)
            m == "GET" && p == "/api/dashboard" -> dashboard(ctx)

            m == "GET" && p == "/api/files" -> listFiles(ctx)
            m == "GET" && p == "/api/files/stat" -> statFile(ctx)
            m == "GET" && p == "/api/files/search" -> search(ctx)
            (m == "GET" || m == "HEAD") && p == "/api/files/download" -> download(ctx)
            m == "POST" && p == "/api/files/mkdir" -> mkdir(ctx)
            m == "POST" && p == "/api/files/rename" -> rename(ctx)
            m == "POST" && p == "/api/files/move" -> move(ctx)
            m == "POST" && p == "/api/files/delete" -> delete(ctx)

            m == "GET" && p == "/api/uploads" -> HttpResponse.ok(mapOf("uploads" to server.uploads.forUser(ctx.user.id).map { uploadJson(ctx.user, it) }))
            m == "POST" && p == "/api/uploads" -> uploadInit(ctx)
            seg[0] == "uploads" && seg.size == 2 && m == "GET" -> uploadStatus(ctx, seg[1])
            seg[0] == "uploads" && seg.size == 2 && m == "PUT" -> uploadChunk(ctx, seg[1])
            seg[0] == "uploads" && seg.size == 3 && seg[2] == "complete" && m == "POST" -> uploadComplete(ctx, seg[1])
            seg[0] == "uploads" && seg.size == 2 && m == "DELETE" -> uploadCancel(ctx, seg[1])

            m == "GET" && p == "/api/shares" -> listShares(ctx)
            m == "POST" && p == "/api/shares" -> createShare(ctx)
            seg[0] == "shares" && seg.size == 2 && m == "DELETE" -> revokeShare(ctx, seg[1])

            seg[0] == "admin" -> admin(ctx, seg)
            else -> throw ApiError(404, "Endpoint tidak ditemukan")
        }
    }

    // ------------------------------------------------------------------ auth

    private fun authenticate(req: HttpRequest): Ctx {
        val token = req.cookies[SESSION_COOKIE]
        val (session, user) = server.sessions.validate(token) { server.users.byId(it) }
            ?: throw ApiError(401, "Silakan login terlebih dahulu", "unauthenticated")
        if (!user.isAdmin && !server.settings.current.multiUser) throw ApiError(403, "Mode pribadi aktif: hanya administrator yang dapat masuk", "personal_mode")
        return Ctx(req, user, session)
    }

    private fun checkCsrf(ctx: Ctx) {
        val token = ctx.req.header("x-csrf-token") ?: throw ApiError(403, "Token CSRF hilang", "csrf")
        if (!Tokens.constantTimeEquals(token, ctx.session.csrf)) throw ApiError(403, "Token CSRF tidak valid", "csrf")
        checkOrigin(ctx.req)
    }

    /** Request lintas origin ditolak. */
    private fun checkOrigin(req: HttpRequest) {
        val origin = req.header("origin") ?: return
        val host = req.header("host") ?: throw ApiError(403, "Host tidak ada")
        val originHost = try {
            val u = URI(origin)
            val h = u.host ?: throw IllegalArgumentException()
            val hostPart = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
            if (u.port == -1) hostPart else "$hostPart:${u.port}"
        } catch (_: Exception) { null }
        if (originHost == null || normHost(originHost) != normHost(host)) {
            throw ApiError(403, "Permintaan lintas situs ditolak", "origin")
        }
    }

    private fun normHost(h: String): String = h.trim().lowercase().removeSuffix(":80").removeSuffix(":443")

    private fun sessionCookie(req: HttpRequest, token: String, maxAge: Long): String =
        "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAge" + if (req.secure) "; Secure" else ""

    private fun setup(req: HttpRequest): HttpResponse {
        checkOrigin(req)
        val body = req.jsonBody()
        val ip = req.remoteIp
        server.limiter.retryAfterSeconds("setup:$ip").let { if (it > 0) throw ApiError(429, "Terlalu banyak percobaan. Coba lagi dalam $it detik", "rate_limited", mapOf("retryAfter" to it)) }
        synchronized(server.users) {
            if (server.users.hasAdmin()) throw ApiError(409, "Administrator sudah dibuat", "already_setup")
            val code = server.setupCode ?: throw ApiError(409, "Pengaturan tidak tersedia")
            if (!Tokens.constantTimeEquals(body.str("code")?.trim() ?: "", code)) {
                server.limiter.recordFailure("setup:$ip")
                throw ApiError(403, "Kode pengaturan salah. Lihat kode di aplikasi CloudSSD pada tablet.", "bad_code")
            }
            val admin = server.users.createAdmin(body.str("username") ?: "", body.str("password") ?: "")
            server.consumeSetupCode()
            server.logs.activity.add(admin.username, "setup", "Akun administrator dibuat", ip)
        }
        return HttpResponse.ok(mapOf("ok" to true))
    }

    private fun login(req: HttpRequest): HttpResponse {
        checkOrigin(req)
        val body = req.jsonBody()
        val username = (body.str("username") ?: "").trim().lowercase()
        val password = body.str("password") ?: ""
        val ip = req.remoteIp
        val keys = arrayOf("ip:$ip", "user:$username")
        val wait = server.limiter.retryAfterSeconds(*keys)
        if (wait > 0) throw ApiError(429, "Terlalu banyak percobaan login. Coba lagi dalam $wait detik", "rate_limited", mapOf("retryAfter" to wait))
        if (username.isEmpty() || password.isEmpty() || password.length > 256) throw ApiError(400, "Nama pengguna dan kata sandi wajib diisi")
        val user = server.users.verify(username, password)
        if (user == null) {
            server.limiter.recordFailure(*keys)
            server.logs.activity.add(username.take(32), "login_failed", "Login gagal", ip)
            throw ApiError(401, "Nama pengguna atau kata sandi salah", "bad_credentials")
        }
        server.limiter.recordSuccess(*keys)
        if (user.disabled) throw ApiError(403, "Akun ini dinonaktifkan oleh administrator", "disabled")
        if (!user.isAdmin && !server.settings.current.multiUser) throw ApiError(403, "Mode pribadi aktif: hanya administrator yang dapat masuk", "personal_mode")
        val (token, session) = server.sessions.create(user, ip, req.header("user-agent") ?: "")
        server.logs.activity.add(user.username, "login", "Login berhasil", ip)
        ensureHome(user)
        return HttpResponse.ok(mapOf("user" to user.publicJson(), "csrf" to session.csrf))
            .header("Set-Cookie", sessionCookie(req, token, server.sessions.absoluteTimeoutMs / 1000))
    }

    private fun logout(ctx: Ctx): HttpResponse {
        server.sessions.revoke(ctx.session.tokenHash)
        server.logs.activity.add(ctx.user.username, "logout", "Logout", ctx.req.remoteIp)
        return HttpResponse.ok(mapOf("ok" to true)).header("Set-Cookie", sessionCookie(ctx.req, "", 0))
    }

    private fun me(ctx: Ctx): HttpResponse = HttpResponse.ok(mapOf(
        "user" to ctx.user.publicJson(), "csrf" to ctx.session.csrf, "multiUser" to server.settings.current.multiUser,
    ))

    private fun changePassword(ctx: Ctx): HttpResponse {
        val body = ctx.req.jsonBody()
        val current = body.str("current") ?: ""
        val keys = arrayOf("pw:${ctx.user.id}")
        server.limiter.retryAfterSeconds(*keys).let { if (it > 0) throw ApiError(429, "Terlalu banyak percobaan. Coba lagi dalam $it detik", "rate_limited") }
        if (server.users.verify(ctx.user.username, current)?.id != ctx.user.id) {
            server.limiter.recordFailure(*keys)
            throw ApiError(403, "Kata sandi saat ini salah", "bad_credentials")
        }
        server.limiter.recordSuccess(*keys)
        val updated = server.users.setPassword(ctx.user.id, body.str("newPassword") ?: "")
        // Semua sesi lama (termasuk perangkat lain) gugur karena credentialVersion naik; buat sesi baru untuk perangkat ini.
        server.sessions.revokeAllForUser(ctx.user.id)
        val (token, session) = server.sessions.create(updated, ctx.req.remoteIp, ctx.req.header("user-agent") ?: "")
        server.logs.activity.add(ctx.user.username, "password", "Kata sandi diganti; sesi lain dikeluarkan", ctx.req.remoteIp)
        return HttpResponse.ok(mapOf("ok" to true, "csrf" to session.csrf))
            .header("Set-Cookie", sessionCookie(ctx.req, token, server.sessions.absoluteTimeoutMs / 1000))
    }

    private fun listSessions(ctx: Ctx) = HttpResponse.ok(mapOf("sessions" to server.sessions.forUser(ctx.user.id).map {
        mapOf("id" to it.id, "ip" to it.ip, "userAgent" to it.userAgent, "createdAt" to it.createdAt, "lastSeen" to it.lastSeen,
            "current" to (it.id == ctx.session.id))
    }))

    private fun revokeSession(ctx: Ctx): HttpResponse {
        val body = ctx.req.jsonBody()
        if (body.bool("others") == true) {
            server.sessions.revokeAllForUser(ctx.user.id, except = ctx.session.tokenHash)
        } else {
            val id = body.str("id") ?: throw ApiError(400, "id wajib")
            val s = server.sessions.forUser(ctx.user.id).firstOrNull { it.id == id } ?: throw ApiError(404, "Sesi tidak ditemukan")
            server.sessions.revoke(s.tokenHash)
        }
        return HttpResponse.ok(mapOf("ok" to true))
    }

    // ------------------------------------------------------------------ path & izin

    /** Mengubah path klien (relatif home) menjadi path SSD; mencegah akses ke luar home & folder internal. */
    private fun storagePath(user: User, raw: String?): VPath {
        val rel = VPath.parse(raw)
        val full = user.home.resolve(rel)
        if (full.segments.firstOrNull() == NameRules.RESERVED_DIR) throw ApiError(403, "Folder sistem CloudSSD tidak dapat diakses", "forbidden")
        if (full.segments.any { it.startsWith(Upload.PART_PREFIX) }) throw ApiError(403, "File sementara tidak dapat diakses", "forbidden")
        return full
    }

    private fun clientPath(user: User, storage: VPath): String =
        if (storage.startsWith(user.home)) storage.relativeTo(user.home).toString() else "/"

    private fun requireRead(user: User) { if (!user.canRead && !user.isAdmin) throw ApiError(403, "Anda tidak memiliki izin baca", "forbidden") }
    private fun requireWrite(user: User) { if (!user.canWrite && !user.isAdmin) throw ApiError(403, "Anda tidak memiliki izin tulis", "forbidden") }

    private fun ensureHome(user: User) {
        if (user.home.isRoot) return
        try {
            val b = server.backendOrNull() ?: return
            if (!b.isAvailable()) return
            var cur = VPath.ROOT
            for (s in user.home.segments) {
                val next = cur.child(s)
                if (b.stat(next) == null) try { b.mkdir(cur, s) } catch (_: AlreadyExistsException) {}
                cur = next
            }
        } catch (e: IOException) {
            server.logs.error("Gagal membuat folder home ${user.username}", e)
        }
    }

    private fun entryJson(user: User, e: FileEntry): Map<String, Any?> = linkedMapOf(
        "name" to e.name, "path" to clientPath(user, e.path), "dir" to e.isDirectory, "size" to e.size, "modified" to e.modified,
        "ext" to if (e.isDirectory) "" else NameRules.extension(e.name), "kind" to Mime.kind(e.name, e.isDirectory),
        "mime" to if (e.isDirectory) null else Mime.of(e.name),
    )

    private fun visible(e: FileEntry): Boolean =
        !(e.path.parent?.isRoot == true && e.name == NameRules.RESERVED_DIR) && !e.name.startsWith(Upload.PART_PREFIX)

    // ------------------------------------------------------------------ file

    private fun listFiles(ctx: Ctx): HttpResponse {
        requireRead(ctx.user)
        ensureHome(ctx.user)
        val dir = storagePath(ctx.user, ctx.req.query["path"])
        val b = server.backend()
        val st = b.stat(dir) ?: throw NotFoundException("Folder tidak ditemukan")
        if (!st.isDirectory) throw ApiError(400, "Bukan folder")
        val items = b.list(dir).filter { visible(it) }.map { entryJson(ctx.user, it) }
        return HttpResponse.ok(mapOf("path" to clientPath(ctx.user, dir), "items" to items))
    }

    private fun statFile(ctx: Ctx): HttpResponse {
        requireRead(ctx.user)
        val path = storagePath(ctx.user, ctx.req.query["path"])
        val e = server.backend().stat(path) ?: throw NotFoundException()
        return HttpResponse.ok(entryJson(ctx.user, e))
    }

    private fun search(ctx: Ctx): HttpResponse {
        requireRead(ctx.user)
        val q = (ctx.req.query["q"] ?: "").trim().lowercase()
        if (q.isEmpty()) throw ApiError(400, "Kata kunci kosong")
        val start = storagePath(ctx.user, ctx.req.query["path"])
        val b = server.backend()
        val results = ArrayList<Map<String, Any?>>()
        val deadline = System.currentTimeMillis() + 20_000
        val stack = ArrayDeque<VPath>().apply { addLast(start) }
        var truncated = false
        while (stack.isNotEmpty()) {
            if (System.currentTimeMillis() > deadline || results.size >= 500) { truncated = true; break }
            val dir = stack.removeFirst()
            val children = try { b.list(dir) } catch (e: NotFoundException) { continue }
            for (e in children) {
                if (!visible(e)) continue
                if (e.name.lowercase().contains(q)) results.add(entryJson(ctx.user, e))
                if (e.isDirectory) stack.addLast(e.path)
            }
        }
        return HttpResponse.ok(mapOf("items" to results, "truncated" to truncated))
    }

    private fun download(ctx: Ctx): HttpResponse {
        requireRead(ctx.user)
        val path = storagePath(ctx.user, ctx.req.query["path"])
        val inline = ctx.req.query["inline"] == "1"
        return FileResponses.stream(server.backend(), path, ctx.req, inline, allowAttachment = true)
    }

    private fun mkdir(ctx: Ctx): HttpResponse {
        requireWrite(ctx.user)
        val body = ctx.req.jsonBody()
        val dir = storagePath(ctx.user, body.str("path"))
        val name = body.str("name")?.trim() ?: throw ApiError(400, "Nama folder wajib diisi")
        NameRules.validate(name)
        if (dir.isRoot && name.equals(NameRules.RESERVED_DIR, true)) throw ApiError(400, "Nama dicadangkan")
        val e = server.backend().mkdir(dir, name)
        server.stats.adjust(e.path, 0, 1, 0)
        server.logs.activity.add(ctx.user.username, "mkdir", "Membuat folder ${clientPath(ctx.user, e.path)}", ctx.req.remoteIp)
        return HttpResponse.json(201, entryJson(ctx.user, e))
    }

    private fun rename(ctx: Ctx): HttpResponse {
        requireWrite(ctx.user)
        val body = ctx.req.jsonBody()
        val path = storagePath(ctx.user, body.str("path"))
        if (path == ctx.user.home) throw ApiError(400, "Folder utama tidak dapat diganti nama")
        val name = body.str("name")?.trim() ?: throw ApiError(400, "Nama baru wajib diisi")
        NameRules.validate(name)
        if (path.parent?.isRoot == true && name.equals(NameRules.RESERVED_DIR, true)) throw ApiError(400, "Nama dicadangkan")
        if (name.startsWith(Upload.PART_PREFIX)) throw ApiError(400, "Nama tidak diizinkan")
        val e = server.backend().rename(path, name)
        server.logs.activity.add(ctx.user.username, "rename", "${clientPath(ctx.user, path)} → ${e.name}", ctx.req.remoteIp)
        return HttpResponse.ok(entryJson(ctx.user, e))
    }

    private fun move(ctx: Ctx): HttpResponse {
        requireWrite(ctx.user)
        val body = ctx.req.jsonBody()
        val dest = storagePath(ctx.user, body.str("dest"))
        val paths = body.list("paths")?.filterIsInstance<String>() ?: throw ApiError(400, "paths wajib")
        val b = server.backend()
        val d = b.stat(dest) ?: throw NotFoundException("Folder tujuan tidak ditemukan")
        if (!d.isDirectory) throw ApiError(400, "Tujuan bukan folder")
        val moved = ArrayList<String>(); val failed = ArrayList<Map<String, Any?>>()
        for (raw in paths) {
            try {
                val src = storagePath(ctx.user, raw)
                if (src == ctx.user.home) throw ApiError(400, "Folder utama tidak dapat dipindah")
                if (dest.startsWith(src)) throw ApiError(400, "Tidak dapat memindahkan folder ke dalam dirinya sendiri")
                b.move(src, dest)
                moved.add(raw)
            } catch (e: StorageUnavailableException) { throw e } catch (e: Exception) {
                failed.add(mapOf("path" to raw, "error" to (e.message ?: "gagal")))
            }
        }
        if (moved.isNotEmpty()) server.logs.activity.add(ctx.user.username, "move", "${moved.size} item → ${clientPath(ctx.user, dest)}", ctx.req.remoteIp)
        return HttpResponse.ok(mapOf("moved" to moved, "failed" to failed))
    }

    private fun delete(ctx: Ctx): HttpResponse {
        requireWrite(ctx.user)
        val body = ctx.req.jsonBody()
        val paths = body.list("paths")?.filterIsInstance<String>() ?: throw ApiError(400, "paths wajib")
        if (paths.isEmpty()) throw ApiError(400, "Tidak ada yang dipilih")
        val b = server.backend()
        val deleted = ArrayList<String>(); val failed = ArrayList<Map<String, Any?>>()
        for (raw in paths) {
            try {
                val p = storagePath(ctx.user, raw)
                if (p == ctx.user.home || p.isRoot) throw ApiError(400, "Folder utama tidak dapat dihapus")
                val st = b.stat(p) ?: throw NotFoundException()
                b.delete(p)
                if (st.isDirectory) server.stats.invalidateAll() else server.stats.adjust(p, -1, 0, -st.size)
                deleted.add(raw)
            } catch (e: StorageUnavailableException) { throw e } catch (e: Exception) {
                failed.add(mapOf("path" to raw, "error" to (e.message ?: "gagal")))
            }
        }
        if (deleted.isNotEmpty()) server.logs.activity.add(ctx.user.username, "delete", "Menghapus ${deleted.size} item: " + deleted.take(3).joinToString(", "), ctx.req.remoteIp)
        return HttpResponse.ok(mapOf("deleted" to deleted, "failed" to failed))
    }

    // ------------------------------------------------------------------ unggah

    private fun uploadJson(user: User, u: Upload) = linkedMapOf(
        "id" to u.id, "dir" to clientPath(user, VPath.parse(u.dir)), "name" to u.name, "size" to u.size,
        "committed" to u.committed, "updatedAt" to u.updatedAt, "chunkSize" to CHUNK_SIZE,
    )

    private fun uploadInit(ctx: Ctx): HttpResponse {
        requireWrite(ctx.user)
        val body = ctx.req.jsonBody()
        val dir = storagePath(ctx.user, body.str("path"))
        val name = body.str("name") ?: throw ApiError(400, "Nama file wajib")
        val size = body.long("size") ?: throw ApiError(400, "Ukuran file wajib")
        val conflict = ConflictPolicy.parse(body.str("conflict"))
        checkQuota(ctx.user, size)
        val u = server.uploads.init(ctx.user.id, dir, name, size, conflict)
        return HttpResponse.json(201, uploadJson(ctx.user, u))
    }

    private fun checkQuota(user: User, additional: Long) {
        if (user.quotaBytes <= 0) return
        val used = server.stats.usage(user.home) + server.uploads.pendingBytesFor(user.id)
        if (used + additional > user.quotaBytes) {
            throw ApiError(507, "Kuota penyimpanan Anda tidak cukup (terpakai ${used} dari ${user.quotaBytes} byte)", "quota",
                mapOf("used" to used, "quota" to user.quotaBytes))
        }
    }

    private fun ownedUpload(ctx: Ctx, id: String): Upload {
        val u = server.uploads.get(id) ?: throw ApiError(404, "Sesi unggahan tidak ditemukan atau sudah selesai", "not_found")
        if (u.userId != ctx.user.id) throw ApiError(404, "Sesi unggahan tidak ditemukan", "not_found")
        return u
    }

    private fun uploadStatus(ctx: Ctx, id: String) = HttpResponse.ok(uploadJson(ctx.user, ownedUpload(ctx, id)))

    private fun uploadChunk(ctx: Ctx, id: String): HttpResponse {
        requireWrite(ctx.user)
        ownedUpload(ctx, id)
        val offset = ctx.req.query["offset"]?.toLongOrNull() ?: throw ApiError(400, "offset wajib")
        val length = ctx.req.contentLength ?: throw ApiError(411, "Content-Length wajib")
        if (length > MAX_CHUNK) throw ApiError(413, "Potongan maksimal $MAX_CHUNK byte")
        val committed = server.uploads.write(id, offset, ctx.req.body, length)
        return HttpResponse.ok(mapOf("committed" to committed))
    }

    private fun uploadComplete(ctx: Ctx, id: String): HttpResponse {
        requireWrite(ctx.user)
        val u = ownedUpload(ctx, id)
        val e = server.uploads.complete(id)
        server.stats.adjust(e.path, 1, 0, e.size)
        server.logs.activity.add(ctx.user.username, "upload", "Mengunggah ${clientPath(ctx.user, e.path)} (${u.size} byte)", ctx.req.remoteIp)
        return HttpResponse.ok(entryJson(ctx.user, e))
    }

    private fun uploadCancel(ctx: Ctx, id: String): HttpResponse {
        ownedUpload(ctx, id)
        server.uploads.cancel(id)
        return HttpResponse.ok(mapOf("ok" to true))
    }

    // ------------------------------------------------------------------ dashboard

    private fun dashboard(ctx: Ctx): HttpResponse {
        val s = server.settings.current
        val b = server.backendOrNull()
        val available = try { b?.isAvailable() == true } catch (_: Exception) { false }
        val cap = if (available) try { b!!.capacity() } catch (_: Exception) { null } else null
        val scope = ctx.user.home
        val stats = if (available) server.stats.get(scope) else null
        if (available && (stats == null || server.clock.now() - stats.computedAt > 10 * 60_000)) server.stats.refreshAsync(scope)
        val addresses = server.localAddresses()
        return HttpResponse.ok(linkedMapOf(
            "server" to linkedMapOf(
                "running" to true, "startedAt" to server.startedAt, "port" to server.httpPort,
                "httpsPort" to if (server.httpsPort > 0) server.httpsPort else null,
                "lanAccess" to s.lanAccess, "vpnAccess" to s.vpnAccess,
                "addresses" to addresses.map { mapOf("ip" to it.ip, "iface" to it.iface, "network" to it.network.name.lowercase()) },
                "connections" to server.activeConnections,
                "clientIp" to ctx.req.remoteIp, "clientNetwork" to NetworkPolicy.classify(ctx.req.remoteAddress).name.lowercase(),
            ),
            "storage" to linkedMapOf(
                "configured" to (b != null), "available" to available,
                "description" to if (ctx.user.isAdmin) b?.description else null,
                "totalBytes" to cap?.totalBytes, "freeBytes" to cap?.freeBytes, "usedBytes" to cap?.usedBytes,
                "lowSpace" to (cap != null && cap.freeBytes < s.lowSpaceWarnBytes),
            ),
            "stats" to (stats?.toJson() ?: mapOf("files" to null, "folders" to null, "bytes" to null, "computedAt" to null)),
            "scanning" to server.stats.isScanning(scope),
            "quota" to mapOf("quotaBytes" to ctx.user.quotaBytes, "usedBytes" to stats?.bytes),
            "webdav" to mapOf("enabled" to s.webdavEnabled, "path" to WebDav.PREFIX + "/"),
            "remote" to linkedMapOf(
                "vpnAccess" to s.vpnAccess, "vpnInterfaceDetected" to addresses.any { it.network == ClientNetwork.VPN },
                "lastVerifiedAt" to server.lastRemoteAccess?.time, "lastVerifiedIp" to server.lastRemoteAccess?.ip,
            ),
            "activity" to server.logs.activity.recent(15, if (ctx.user.isAdmin) null else ctx.user.username).map { it.toJson() },
        ))
    }

    // ------------------------------------------------------------------ berbagi

    private fun shareJson(s: Share, owner: User?, viewer: User): Map<String, Any?> = linkedMapOf(
        "id" to s.id, "url" to "/s/${s.token}", "name" to s.name,
        "path" to if (owner != null && owner.id == viewer.id) clientPath(viewer, VPath.parse(s.storagePath)) else "/" + s.storagePath,
        "owner" to owner?.username, "createdAt" to s.createdAt, "expiresAt" to s.expiresAt, "hasPassword" to (s.passwordHash != null),
        "allowDownload" to s.allowDownload, "revoked" to s.revoked, "active" to s.isActive(server.clock.now()), "accessCount" to s.accessCount,
    )

    private fun listShares(ctx: Ctx): HttpResponse {
        val list = server.shares.forOwner(if (ctx.user.isAdmin) null else ctx.user.id)
        return HttpResponse.ok(mapOf("shares" to list.map { shareJson(it, server.users.byId(it.ownerId), ctx.user) }))
    }

    private fun createShare(ctx: Ctx): HttpResponse {
        requireRead(ctx.user)
        val body = ctx.req.jsonBody()
        val path = storagePath(ctx.user, body.str("path"))
        val e = server.backend().stat(path) ?: throw NotFoundException()
        if (e.isDirectory) throw ApiError(400, "Saat ini hanya file yang dapat dibagikan")
        val pw = body.str("password")?.takeIf { it.isNotEmpty() }
        val s = server.shares.create(ctx.user.id, path.segments.joinToString("/"), e.name, body.long("expiresHours"), pw, body.bool("allowDownload") ?: true)
        server.logs.activity.add(ctx.user.username, "share", "Membuat tautan untuk ${clientPath(ctx.user, path)}", ctx.req.remoteIp)
        return HttpResponse.json(201, shareJson(s, ctx.user, ctx.user))
    }

    private fun revokeShare(ctx: Ctx, id: String): HttpResponse {
        val s = server.shares.byId(id) ?: throw ApiError(404, "Tautan tidak ditemukan")
        if (s.ownerId != ctx.user.id && !ctx.user.isAdmin) throw ApiError(404, "Tautan tidak ditemukan")
        server.shares.revoke(id)
        server.logs.activity.add(ctx.user.username, "share_revoke", "Mencabut tautan ${s.name}", ctx.req.remoteIp)
        return HttpResponse.ok(mapOf("ok" to true))
    }

    /** /api/public/share/{token}[/unlock|/content] */
    private fun publicShare(req: HttpRequest, seg: List<String>): HttpResponse {
        val token = seg[2]
        val action = seg.getOrNull(3)
        val now = server.clock.now()
        val share = server.shares.byToken(token)
        if (share == null || !share.isActive(now)) throw ApiError(404, "Tautan tidak ditemukan, sudah dicabut, atau kedaluwarsa", "share_gone")
        val owner = server.users.byId(share.ownerId)
        if (owner == null || owner.disabled) throw ApiError(404, "Tautan tidak tersedia", "share_gone")
        val grantCookie = "cssd_sh_${share.id}"
        val unlocked = share.passwordHash == null || req.cookies[grantCookie]?.let { g ->
            shareGrants[g]?.let { it.first == share.id && it.second > now }
        } == true

        when {
            action == null && req.method == "GET" -> {
                if (!unlocked) return HttpResponse.ok(mapOf("needsPassword" to true))
                val e = server.backend().stat(VPath.parse(share.storagePath)) ?: throw ApiError(404, "File sudah tidak ada", "share_gone")
                val mime = Mime.of(e.name)
                return HttpResponse.ok(mapOf("needsPassword" to false, "name" to e.name, "size" to e.size, "modified" to e.modified,
                    "mime" to mime, "kind" to Mime.kind(e.name, false), "previewable" to Mime.isSafeInline(mime),
                    "allowDownload" to share.allowDownload, "expiresAt" to share.expiresAt))
            }
            action == "unlock" && req.method == "POST" -> {
                checkOrigin(req)
                val keys = arrayOf("share:${share.id}:${req.remoteIp}")
                server.limiter.retryAfterSeconds(*keys).let { if (it > 0) throw ApiError(429, "Terlalu banyak percobaan. Coba lagi dalam $it detik", "rate_limited") }
                val pw = req.jsonBody().str("password") ?: ""
                if (!server.shares.checkPassword(share, pw)) {
                    server.limiter.recordFailure(*keys)
                    throw ApiError(403, "Kata sandi tautan salah", "bad_credentials")
                }
                server.limiter.recordSuccess(*keys)
                val grant = Tokens.urlToken(24)
                val exp = now + 12 * 3600_000L
                shareGrants[grant] = share.id to exp
                shareGrants.entries.removeIf { it.value.second < now }
                return HttpResponse.ok(mapOf("ok" to true)).header("Set-Cookie",
                    "$grantCookie=$grant; Path=/api/public/share/; HttpOnly; SameSite=Strict; Max-Age=43200" + if (req.secure) "; Secure" else "")
            }
            action == "content" && (req.method == "GET" || req.method == "HEAD") -> {
                if (!unlocked) throw ApiError(401, "Kata sandi diperlukan", "share_locked")
                val download = req.query["download"] == "1"
                if (download && !share.allowDownload) throw ApiError(403, "Pemilik hanya mengizinkan melihat file ini", "view_only")
                val path = VPath.parse(share.storagePath)
                if (!download && !Mime.isSafeInline(Mime.of(path.name))) throw ApiError(415, "Jenis file ini tidak dapat dipratinjau", "not_previewable")
                if (req.header("range") == null || req.header("range")!!.startsWith("bytes=0-")) server.shares.countAccess(share.id)
                return FileResponses.stream(server.backend(), path, req, inline = !download, allowAttachment = share.allowDownload)
            }
            else -> throw ApiError(404, "Endpoint tidak ditemukan")
        }
    }

    // ------------------------------------------------------------------ admin

    private fun admin(ctx: Ctx, seg: List<String>): HttpResponse {
        if (!ctx.user.isAdmin) throw ApiError(403, "Khusus administrator", "forbidden")
        val m = ctx.req.method
        return when {
            seg.size == 2 && seg[1] == "users" && m == "GET" -> HttpResponse.ok(mapOf("users" to server.users.all().map { u ->
                val st = server.stats.get(u.home)
                if (st == null && server.backendOrNull()?.isAvailable() == true) server.stats.refreshAsync(u.home)
                u.publicJson() + mapOf("usedBytes" to st?.bytes, "usageComputedAt" to st?.computedAt, "scanning" to server.stats.isScanning(u.home))
            }))
            seg.size == 2 && seg[1] == "users" && m == "POST" -> createUser(ctx)
            seg.size == 3 && seg[1] == "users" && m == "PATCH" -> updateUser(ctx, seg[2])
            seg.size == 4 && seg[1] == "users" && seg[3] == "usage" && m == "POST" -> {
                val u = server.users.byId(seg[2]) ?: throw ApiError(404, "Pengguna tidak ditemukan")
                val st = server.stats.scanNow(u.home)
                HttpResponse.ok(st.toJson())
            }
            seg.size == 2 && seg[1] == "settings" && m == "GET" -> HttpResponse.ok(adminSettings())
            seg.size == 2 && seg[1] == "settings" && m == "PATCH" -> {
                val body = ctx.req.jsonBody()
                server.settings.update { cur ->
                    cur.copy(
                        multiUser = body.bool("multiUser") ?: cur.multiUser,
                        webdavEnabled = body.bool("webdavEnabled") ?: cur.webdavEnabled,
                        lowSpaceWarnBytes = body.long("lowSpaceWarnBytes") ?: cur.lowSpaceWarnBytes,
                        extraHosts = body.list("extraHosts")?.filterIsInstance<String>()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: cur.extraHosts,
                    )
                }
                server.logs.activity.add(ctx.user.username, "settings", "Pengaturan server diubah", ctx.req.remoteIp)
                HttpResponse.ok(adminSettings())
            }
            seg.size == 2 && seg[1] == "logs" && m == "GET" -> HttpResponse.ok(mapOf(
                "errors" to server.logs.errors.recent(100).map { it.toJson() },
                "activity" to server.logs.activity.recent(200).map { it.toJson() },
            ))
            else -> throw ApiError(404, "Endpoint tidak ditemukan")
        }
    }

    private fun adminSettings(): Map<String, Any?> {
        val s = server.settings.current
        return linkedMapOf("multiUser" to s.multiUser, "webdavEnabled" to s.webdavEnabled, "lowSpaceWarnBytes" to s.lowSpaceWarnBytes, "extraHosts" to s.extraHosts,
            "lanAccess" to s.lanAccess, "vpnAccess" to s.vpnAccess, "port" to s.port, "httpsEnabled" to s.httpsEnabled,
            "note" to "Akses jaringan, port, dan HTTPS hanya dapat diubah dari aplikasi di tablet.")
    }

    private fun homeFor(username: String, raw: String?): String {
        val h = if (raw.isNullOrBlank()) VPath.of("users", username) else VPath.parse(raw)
        if (h.isRoot) throw ValidationException("Folder pengguna biasa tidak boleh seluruh SSD")
        if (h.segments.first() == NameRules.RESERVED_DIR) throw ValidationException("Folder sistem tidak dapat dipakai")
        return h.segments.joinToString("/")
    }

    private fun createUser(ctx: Ctx): HttpResponse {
        val body = ctx.req.jsonBody()
        if (!server.settings.current.multiUser) throw ApiError(409, "Aktifkan mode multi-pengguna terlebih dahulu", "personal_mode")
        val username = (body.str("username") ?: "").trim().lowercase()
        val u = server.users.create(
            username, body.str("password") ?: "", Role.USER, homeFor(username, body.str("homeDir")),
            canRead = body.bool("canRead") ?: true, canWrite = body.bool("canWrite") ?: true, quotaBytes = body.long("quotaBytes") ?: 0,
        )
        ensureHome(u)
        server.logs.activity.add(ctx.user.username, "user_create", "Membuat pengguna ${u.username}", ctx.req.remoteIp)
        return HttpResponse.json(201, u.publicJson())
    }

    private fun updateUser(ctx: Ctx, id: String): HttpResponse {
        val body = ctx.req.jsonBody()
        val target = server.users.byId(id) ?: throw ApiError(404, "Pengguna tidak ditemukan")
        val newHome = body.str("homeDir")?.let { if (target.isAdmin) throw ValidationException("Folder admin selalu seluruh SSD") else homeFor(target.username, it) }
        val quota = body.long("quotaBytes")
        if (quota != null && quota < 0) throw ValidationException("Kuota tidak valid")
        var u = server.users.update(id) { cur ->
            cur.copy(
                disabled = body.bool("disabled") ?: cur.disabled,
                canRead = if (cur.isAdmin) true else body.bool("canRead") ?: cur.canRead,
                canWrite = if (cur.isAdmin) true else body.bool("canWrite") ?: cur.canWrite,
                quotaBytes = if (cur.isAdmin) 0 else quota ?: cur.quotaBytes,
                homeDir = newHome ?: cur.homeDir,
            )
        }
        body.str("password")?.takeIf { it.isNotEmpty() }?.let { u = server.users.setPassword(id, it) }
        if (u.disabled || body.str("password") != null) server.sessions.revokeAllForUser(id)
        if (newHome != null) ensureHome(u)
        server.logs.activity.add(ctx.user.username, "user_update", "Memperbarui pengguna ${u.username}", ctx.req.remoteIp)
        return HttpResponse.ok(u.publicJson())
    }

    companion object {
        const val VERSION = "1.0.0"
        const val SESSION_COOKIE = "cssd_sid"
        const val CHUNK_SIZE = 8L * 1024 * 1024
        const val MAX_CHUNK = 64L * 1024 * 1024

        fun encodeRfc5987(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
