package id.cloudssd.core.api

import id.cloudssd.core.auth.Tokens
import id.cloudssd.core.auth.User
import id.cloudssd.core.http.ApiError
import id.cloudssd.core.http.Body
import id.cloudssd.core.http.HttpRequest
import id.cloudssd.core.http.HttpResponse
import id.cloudssd.core.http.HttpServer
import id.cloudssd.core.server.CloudServer
import id.cloudssd.core.server.Upload
import id.cloudssd.core.storage.FileEntry
import id.cloudssd.core.storage.NameRules
import id.cloudssd.core.storage.NoSpaceException
import id.cloudssd.core.storage.NotFoundException
import id.cloudssd.core.storage.StorageBackend
import id.cloudssd.core.storage.VPath
import id.cloudssd.core.storage.uniqueName
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Drive jaringan WebDAV (RFC 4918, kelas 1 & 2) di /dav/.
 *
 * Membuat CloudSSD tampil sebagai "penyimpanan virtual" di File Explorer Windows (RaiDrive / Map network drive),
 * Finder macOS, Linux (GVFS/davfs2), dan aplikasi file Android (CX File Explorer, Solid Explorer, dll).
 * Kapasitas yang dilaporkan (quota-used/available-bytes) adalah kapasitas AKTUAL SSD, atau kuota pengguna
 * bila lebih kecil — tidak pernah angka karangan.
 *
 * Autentikasi: HTTP Basic dengan akun CloudSSD yang sama (dibatasi percobaannya), hanya bila diaktifkan admin.
 * Kunci (LOCK) bersifat advisory: diterima agar klien Windows/macOS dapat menulis, tetapi tidak
 * mencegah klien lain menulis file yang sama.
 */
class WebDav(private val server: CloudServer) {

    private data class AuthCache(val userId: String, val credentialVersion: Long, val expires: Long)
    private val authCache = ConcurrentHashMap<String, AuthCache>()

    private data class Lock(val token: String, val path: String, val owner: String, val expires: Long)
    private val locks = ConcurrentHashMap<String, Lock>()

    fun handle(req: HttpRequest): HttpResponse {
        val settings = server.settings.current
        val isDav = req.path == PREFIX || req.path.startsWith("$PREFIX/")
        if (req.method == "OPTIONS") {
            // Windows Mini-Redirector memeriksa OPTIONS di root server sebelum memetakan drive.
            if (!settings.webdavEnabled) return HttpResponse(if (isDav) 404 else 405).header("Allow", "GET, HEAD, POST")
            return HttpResponse(200).header("DAV", "1, 2").header("MS-Author-Via", "DAV")
                .header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK")
        }
        if (!settings.webdavEnabled) throw ApiError(404, "Drive jaringan (WebDAV) belum diaktifkan oleh administrator", "webdav_disabled")
        val user = authenticate(req) ?: return challenge()
        return when (req.method) {
            "PROPFIND" -> propfind(req, user)
            "GET", "HEAD" -> get(req, user)
            "PUT" -> put(req, user)
            "DELETE" -> delete(req, user)
            "MKCOL" -> mkcol(req, user)
            "MOVE" -> moveOrCopy(req, user, copy = false)
            "COPY" -> moveOrCopy(req, user, copy = true)
            "LOCK" -> lock(req, user)
            "UNLOCK" -> { locks.remove(req.header("lock-token")?.trim('<', '>', ' ') ?: ""); HttpResponse(204) }
            "PROPPATCH" -> proppatch(req, user)
            else -> HttpResponse(405).header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK")
        }
    }

    // ------------------------------------------------------------------ autentikasi

    private fun challenge(): HttpResponse =
        HttpResponse.text(401, "Login diperlukan").header("WWW-Authenticate", "Basic realm=\"CloudSSD\", charset=\"UTF-8\"")

    private fun authenticate(req: HttpRequest): User? {
        val header = req.header("authorization") ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val now = server.clock.now()
        val key = Tokens.sha256Hex(header)
        authCache[key]?.let { c ->
            val u = server.users.byId(c.userId)
            if (c.expires > now && u != null && u.credentialVersion == c.credentialVersion && allowed(u)) return u
            authCache.remove(key)
        }
        val decoded = try { String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8) } catch (_: IllegalArgumentException) { return null }
        val idx = decoded.indexOf(':')
        if (idx <= 0) return null
        val username = decoded.substring(0, idx).trim().lowercase()
        val password = decoded.substring(idx + 1)
        val keys = arrayOf("ip:${req.remoteIp}", "user:$username")
        val wait = server.limiter.retryAfterSeconds(*keys)
        if (wait > 0) throw ApiError(429, "Terlalu banyak percobaan login. Coba lagi dalam $wait detik", "rate_limited")
        val u = server.users.verify(username, password)
        if (u == null) {
            server.limiter.recordFailure(*keys)
            server.logs.activity.add(username.take(32), "login_failed", "Login WebDAV gagal", req.remoteIp)
            return null
        }
        server.limiter.recordSuccess(*keys)
        if (!allowed(u)) throw ApiError(403, if (u.disabled) "Akun dinonaktifkan" else "Mode pribadi aktif: hanya administrator", "forbidden")
        if (authCache.size > 1000) authCache.clear()
        authCache[key] = AuthCache(u.id, u.credentialVersion, now + 10 * 60_000)
        return u
    }

    private fun allowed(u: User) = !u.disabled && (u.isAdmin || server.settings.current.multiUser)

    private fun requireWrite(u: User) { if (!u.isAdmin && !u.canWrite) throw ApiError(403, "Tidak ada izin tulis", "forbidden") }
    private fun requireRead(u: User) { if (!u.isAdmin && !u.canRead) throw ApiError(403, "Tidak ada izin baca", "forbidden") }

    // ------------------------------------------------------------------ path

    /** Path WebDAV (sudah didekode) -> path relatif pengguna. */
    private fun relPath(decoded: String): VPath {
        val rest = decoded.removePrefix(PREFIX)
        return VPath.parse(rest)
    }

    private fun storage(user: User, rel: VPath): VPath {
        val full = user.home.resolve(rel)
        if (full.segments.firstOrNull() == NameRules.RESERVED_DIR || full.segments.any { it.startsWith(Upload.PART_PREFIX) }) {
            throw ApiError(403, "Folder sistem CloudSSD tidak dapat diakses", "forbidden")
        }
        return full
    }

    private fun href(rel: VPath, dir: Boolean): String {
        val sb = StringBuilder(PREFIX)
        for (s in rel.segments) sb.append('/').append(URLEncoder.encode(s, "UTF-8").replace("+", "%20"))
        if (dir || rel.isRoot) sb.append('/')
        return sb.toString()
    }

    private fun visible(e: FileEntry) = !(e.path.parent?.isRoot == true && e.name == NameRules.RESERVED_DIR) && !e.name.startsWith(Upload.PART_PREFIX)

    private fun destination(req: HttpRequest): VPath {
        val raw = req.header("destination") ?: throw ApiError(400, "Header Destination wajib")
        val rawPath = try { URI(raw).rawPath ?: raw } catch (_: Exception) { raw }
        val decoded = try { HttpServer.decodePath(rawPath) } catch (_: IllegalArgumentException) { throw ApiError(400, "Destination tidak valid") }
        if (decoded != PREFIX && !decoded.startsWith("$PREFIX/")) throw ApiError(502, "Tujuan di luar drive CloudSSD")
        return relPath(decoded)
    }

    // ------------------------------------------------------------------ PROPFIND

    private fun propfind(req: HttpRequest, user: User): HttpResponse {
        requireRead(user)
        val body = String(req.readSmallBody(256 * 1024), StandardCharsets.UTF_8)
        val namesOnly = body.contains("propname")
        val rel = relPath(req.path)
        val path = storage(user, rel)
        val b = server.backend()
        val e = b.stat(path) ?: throw NotFoundException()
        val depth = req.header("depth")?.trim() ?: "1"
        val quota = quota(user, b)
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">")
        appendResponse(sb, rel, e, quota, namesOnly)
        if (e.isDirectory && depth != "0") {
            for (c in b.list(path)) {
                if (!visible(c)) continue
                appendResponse(sb, rel.resolve(VPath.of(c.name)), c, quota, namesOnly)
            }
        }
        sb.append("</D:multistatus>")
        return xml(207, sb.toString())
    }

    private data class Quota(val used: Long, val available: Long)

    /** Kapasitas aktual SSD; bila pengguna punya kuota yang lebih kecil, kuota itulah yang dilaporkan. */
    private fun quota(user: User, b: StorageBackend): Quota? {
        val cap = b.capacity()
        if (user.quotaBytes > 0) {
            val used = server.stats.usage(user.home)
            var avail = (user.quotaBytes - used).coerceAtLeast(0)
            if (cap != null) avail = minOf(avail, cap.freeBytes)
            return Quota(used, avail)
        }
        return cap?.let { Quota(it.usedBytes, it.freeBytes) }
    }

    private fun appendResponse(sb: StringBuilder, rel: VPath, e: FileEntry, quota: Quota?, namesOnly: Boolean) {
        sb.append("<D:response><D:href>").append(esc(href(rel, e.isDirectory))).append("</D:href><D:propstat><D:prop>")
        val name = if (rel.isRoot) "CloudSSD" else e.name
        if (namesOnly) {
            sb.append("<D:displayname/><D:resourcetype/><D:getlastmodified/><D:creationdate/><D:supportedlock/><D:lockdiscovery/>")
            if (!e.isDirectory) sb.append("<D:getcontentlength/><D:getcontenttype/><D:getetag/>")
            if (e.isDirectory && quota != null) sb.append("<D:quota-available-bytes/><D:quota-used-bytes/>")
        } else {
            sb.append("<D:displayname>").append(esc(name)).append("</D:displayname>")
            sb.append(if (e.isDirectory) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>")
            sb.append("<D:getlastmodified>").append(httpDate(e.modified)).append("</D:getlastmodified>")
            sb.append("<D:creationdate>").append(isoDate(e.modified)).append("</D:creationdate>")
            if (!e.isDirectory) {
                sb.append("<D:getcontentlength>").append(e.size).append("</D:getcontentlength>")
                sb.append("<D:getcontenttype>").append(esc(Mime.of(e.name))).append("</D:getcontenttype>")
                sb.append("<D:getetag>\"").append(java.lang.Long.toHexString(e.size)).append('-').append(java.lang.Long.toHexString(e.modified)).append("\"</D:getetag>")
            } else if (quota != null) {
                sb.append("<D:quota-available-bytes>").append(quota.available).append("</D:quota-available-bytes>")
                sb.append("<D:quota-used-bytes>").append(quota.used).append("</D:quota-used-bytes>")
            }
            sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock><D:lockdiscovery/>")
        }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    /**
     * Properti tambahan (mis. cap waktu/atribut Win32 dari Windows) diterima tetapi TIDAK disimpan;
     * Windows menolak menyelesaikan penyalinan bila PROPPATCH gagal. Isi file tidak terpengaruh.
     */
    private fun proppatch(req: HttpRequest, user: User): HttpResponse {
        requireWrite(user)
        val rel = relPath(req.path)
        val path = storage(user, rel)
        val e = server.backend().stat(path) ?: throw NotFoundException()
        val body = String(req.readSmallBody(256 * 1024), StandardCharsets.UTF_8)
        val props = Regex("<(?:[A-Za-z0-9_]+:)?prop>(.*?)</(?:[A-Za-z0-9_]+:)?prop>", RegexOption.DOT_MATCHES_ALL).findAll(body)
            .flatMap { m -> Regex("<([A-Za-z0-9_]+:)?([A-Za-z0-9_-]+)[\\s/>]").findAll(m.groupValues[1]).map { it.groupValues[2] } }
            .distinct().toList()
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>")
            .append(esc(href(rel, e.isDirectory))).append("</D:href><D:propstat><D:prop>")
        // Klien Windows hanya mengirim cap waktu/atribut; kegagalan menyimpannya tidak menghalangi penulisan file.
        props.forEach { sb.append("<D:").append(it).append("/>") }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>")
        return xml(207, sb.toString())
    }

    // ------------------------------------------------------------------ GET / PUT / DELETE / MKCOL

    private fun get(req: HttpRequest, user: User): HttpResponse {
        requireRead(user)
        val path = storage(user, relPath(req.path))
        val e = server.backend().stat(path) ?: throw NotFoundException()
        if (e.isDirectory) {
            return HttpResponse.text(200, "Ini adalah folder drive jaringan CloudSSD. Buka lewat aplikasi file yang mendukung WebDAV.")
        }
        return FileResponses.stream(server.backend(), path, req, inline = false, allowAttachment = true)
    }

    private fun put(req: HttpRequest, user: User): HttpResponse {
        requireWrite(user)
        val rel = relPath(req.path)
        if (rel.isRoot) throw ApiError(405, "Tidak dapat menulis ke root")
        val path = storage(user, rel)
        val size = req.contentLength ?: req.header("x-expected-entity-length")?.toLongOrNull()
        if (req.header("content-range") != null) throw ApiError(501, "PUT parsial tidak didukung")
        checkQuota(user, size ?: 0)
        val old = server.backend().stat(path)
        val (entry, created) = server.uploads.putStream(path.parent!!, path.name, req.body, if (req.contentLength != null) size else null)
        server.stats.adjust(entry.path, if (created) 1 else 0, 0, entry.size - (old?.size ?: 0))
        if (entry.size > 0) server.logs.activity.add(user.username, "upload", "Mengunggah ${clientPath(user, entry.path)} (${entry.size} byte, drive jaringan)", req.remoteIp)
        return HttpResponse(if (created) 201 else 204).header("ETag", "\"${java.lang.Long.toHexString(entry.size)}-${java.lang.Long.toHexString(entry.modified)}\"")
    }

    private fun delete(req: HttpRequest, user: User): HttpResponse {
        requireWrite(user)
        val rel = relPath(req.path)
        if (rel.isRoot) throw ApiError(403, "Folder utama tidak dapat dihapus")
        val path = storage(user, rel)
        val b = server.backend()
        val e = b.stat(path) ?: throw NotFoundException()
        b.delete(path)
        if (e.isDirectory) server.stats.invalidateAll() else server.stats.adjust(path, -1, 0, -e.size)
        locks.values.removeIf { it.path == path.toString() }
        server.logs.activity.add(user.username, "delete", "Menghapus ${clientPath(user, path)} (drive jaringan)", req.remoteIp)
        return HttpResponse(204)
    }

    private fun mkcol(req: HttpRequest, user: User): HttpResponse {
        requireWrite(user)
        if ((req.contentLength ?: 0) > 0) throw ApiError(415, "MKCOL dengan body tidak didukung")
        val rel = relPath(req.path)
        if (rel.isRoot) throw ApiError(405, "Sudah ada")
        val path = storage(user, rel)
        val b = server.backend()
        if (b.stat(path) != null) throw ApiError(405, "Sudah ada")
        val parent = b.stat(path.parent!!)
        if (parent == null || !parent.isDirectory) throw ApiError(409, "Folder induk tidak ada")
        val e = b.mkdir(path.parent!!, path.name)
        server.stats.adjust(e.path, 0, 1, 0)
        server.logs.activity.add(user.username, "mkdir", "Membuat folder ${clientPath(user, e.path)} (drive jaringan)", req.remoteIp)
        return HttpResponse(201)
    }

    // ------------------------------------------------------------------ MOVE / COPY

    private fun moveOrCopy(req: HttpRequest, user: User, copy: Boolean): HttpResponse {
        if (copy) requireRead(user)
        requireWrite(user)
        val srcRel = relPath(req.path)
        if (srcRel.isRoot) throw ApiError(403, "Folder utama tidak dapat dipindah")
        val dstRel = destination(req)
        if (dstRel.isRoot) throw ApiError(403, "Tujuan tidak valid")
        val src = storage(user, srcRel)
        val dst = storage(user, dstRel)
        if (src == dst) throw ApiError(403, "Sumber dan tujuan sama")
        if (dst.startsWith(src)) throw ApiError(409, "Tidak dapat menyalin/memindah folder ke dalam dirinya sendiri")
        val overwrite = !req.header("overwrite").equals("F", ignoreCase = true)
        val b = server.backend()
        val se = b.stat(src) ?: throw NotFoundException()
        val dstParent = b.stat(dst.parent!!)
        if (dstParent == null || !dstParent.isDirectory) throw ApiError(409, "Folder tujuan tidak ada")
        val existing = b.stat(dst)
        if (existing != null && !overwrite) throw ApiError(412, "Tujuan sudah ada")
        if (existing != null) b.delete(dst)
        if (copy) {
            checkQuota(user, if (se.isDirectory) 0 else se.size)
            copyTree(b, src, dst)
        } else {
            moveEntry(b, src, dst)
            locks.values.removeIf { it.path == src.toString() }
        }
        server.stats.invalidateAll()
        server.logs.activity.add(user.username, if (copy) "copy" else "move",
            "${if (copy) "Menyalin" else "Memindah"} ${clientPath(user, src)} → ${clientPath(user, dst)} (drive jaringan)", req.remoteIp)
        return HttpResponse(if (existing != null) 204 else 201)
    }

    private fun moveEntry(b: StorageBackend, src: VPath, dst: VPath) {
        val srcParent = src.parent!!
        val dstParent = dst.parent!!
        when {
            srcParent == dstParent -> b.rename(src, dst.name)
            src.name == dst.name && b.stat(dstParent.child(src.name)) == null -> b.move(src, dstParent)
            else -> {
                // Ganti nama sementara di tempat asal agar tidak bentrok, pindah, lalu beri nama akhir.
                val tmp = b.uniqueName(srcParent, ".cssd-mv-" + Tokens.urlToken(6))
                val renamed = b.rename(src, tmp)
                val moved = b.move(renamed.path, dstParent)
                b.rename(moved.path, dst.name)
            }
        }
    }

    private fun copyTree(b: StorageBackend, src: VPath, dst: VPath) {
        val e = b.stat(src) ?: throw NotFoundException()
        if (e.isDirectory) {
            b.mkdir(dst.parent!!, dst.name)
            for (c in b.list(src)) {
                if (!visible(c)) continue
                copyTree(b, c.path, dst.child(c.name))
            }
        } else {
            b.openRead(src, 0).use { input -> server.uploads.putStream(dst.parent!!, dst.name, input, e.size) }
        }
    }

    // ------------------------------------------------------------------ LOCK

    private fun lock(req: HttpRequest, user: User): HttpResponse {
        requireWrite(user)
        val rel = relPath(req.path)
        val path = storage(user, rel)
        val body = String(req.readSmallBody(64 * 1024), StandardCharsets.UTF_8)
        val now = server.clock.now()
        locks.values.removeIf { it.expires < now }
        val b = server.backend()
        // Penyegaran kunci: tanpa body, token ada di header If.
        val refreshToken = Regex("opaquelocktoken:[0-9a-fA-F-]+").find(req.header("if") ?: "")?.value
        if (body.isBlank() && refreshToken != null) {
            val l = locks[refreshToken] ?: throw ApiError(412, "Kunci tidak ditemukan")
            val n = l.copy(expires = now + LOCK_SECONDS * 1000)
            locks[n.token] = n
            return lockResponse(200, n, rel, b.stat(path)?.isDirectory == true)
        }
        var created = false
        if (b.stat(path) == null) {
            if (rel.isRoot) throw NotFoundException()
            val parent = b.stat(path.parent!!)
            if (parent == null || !parent.isDirectory) throw ApiError(409, "Folder induk tidak ada")
            b.createFile(path.parent!!, path.name)
            created = true
        }
        val owner = Regex("<(?:[A-Za-z0-9_]+:)?owner>(.*?)</(?:[A-Za-z0-9_]+:)?owner>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.take(500) ?: ""
        val l = Lock("opaquelocktoken:" + UUID.randomUUID(), path.toString(), owner, now + LOCK_SECONDS * 1000)
        locks[l.token] = l
        return lockResponse(if (created) 201 else 200, l, rel, b.stat(path)?.isDirectory == true).header("Lock-Token", "<${l.token}>")
    }

    private fun lockResponse(status: Int, l: Lock, rel: VPath, dir: Boolean): HttpResponse {
        val x = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>0</D:depth>" +
            "<D:owner>${l.owner}</D:owner><D:timeout>Second-$LOCK_SECONDS</D:timeout>" +
            "<D:locktoken><D:href>${l.token}</D:href></D:locktoken><D:lockroot><D:href>${esc(href(rel, dir))}</D:href></D:lockroot>" +
            "</D:activelock></D:lockdiscovery></D:prop>"
        return xml(status, x)
    }

    // ------------------------------------------------------------------ util

    private fun checkQuota(user: User, additional: Long) {
        if (user.quotaBytes <= 0) return
        val used = server.stats.usage(user.home) + server.uploads.pendingBytesFor(user.id)
        if (used + additional > user.quotaBytes) throw NoSpaceException("Kuota penyimpanan Anda tidak cukup")
    }

    private fun clientPath(user: User, p: VPath) = if (p.startsWith(user.home)) p.relativeTo(user.home).toString() else "/"

    private fun xml(status: Int, s: String): HttpResponse =
        HttpResponse(status, Body.Bytes(s.toByteArray(StandardCharsets.UTF_8))).header("Content-Type", "application/xml; charset=utf-8")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun httpDate(ms: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))
    private fun isoDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    companion object {
        const val PREFIX = "/dav"
        private const val LOCK_SECONDS = 3600L
    }
}
