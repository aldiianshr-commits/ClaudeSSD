package id.cloudssd.core.api

import id.cloudssd.core.http.ApiError
import id.cloudssd.core.http.Body
import id.cloudssd.core.http.HttpRequest
import id.cloudssd.core.http.HttpResponse
import id.cloudssd.core.storage.NotFoundException
import id.cloudssd.core.storage.StorageBackend
import id.cloudssd.core.storage.VPath
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Streaming file dengan dukungan HTTP Range (melanjutkan unduhan, memutar video). */
object FileResponses {

    fun stream(backend: StorageBackend, path: VPath, req: HttpRequest, inline: Boolean, allowAttachment: Boolean): HttpResponse {
        val e = backend.stat(path) ?: throw NotFoundException()
        if (e.isDirectory) throw ApiError(400, "Folder tidak dapat diunduh langsung; unduh file di dalamnya")
        val size = e.size
        val mime = Mime.of(e.name)
        val showInline = inline && Mime.isSafeInline(mime)
        val etag = "\"${java.lang.Long.toHexString(size)}-${java.lang.Long.toHexString(e.modified)}\""

        if (req.header("if-none-match") == etag) {
            return HttpResponse(304).header("ETag", etag)
        }

        var start = 0L
        var end = size - 1
        var partial = false
        val range = req.header("range")
        val ifRange = req.header("if-range")
        if (range != null && (ifRange == null || ifRange == etag)) {
            val parsed = parseRange(range, size)
            if (parsed == null) {
                return HttpResponse(416).header("Content-Range", "bytes */$size")
            }
            if (parsed.first != -1L) {
                start = parsed.first; end = parsed.second; partial = true
            }
        }
        val length = if (size == 0L) 0L else end - start + 1
        val res = HttpResponse(if (partial) 206 else 200, Body.Stream(length) { out ->
            if (length == 0L) return@Stream
            backend.openRead(path, start).use { input ->
                val buf = ByteArray(256 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n == -1) throw IOException("File berubah/terputus saat dibaca")
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        })
        res.header("Content-Type", if (showInline) mime else if (inline) "application/octet-stream" else mime)
        res.header("Accept-Ranges", "bytes")
        res.header("ETag", etag)
        res.header("Last-Modified", httpDate(e.modified))
        res.header("Cache-Control", "private, no-cache")
        // Konten pengguna tidak boleh menjalankan skrip di origin CloudSSD.
        // (PDF dikecualikan karena penampil PDF bawaan browser menolak dokumen yang di-sandbox.)
        if (!(showInline && mime == "application/pdf")) {
            res.header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'")
        }
        if (partial) res.header("Content-Range", "bytes $start-$end/$size")
        val disposition = if (showInline || !allowAttachment) "inline" else "attachment"
        val ascii = e.name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
        res.header("Content-Disposition", "$disposition; filename=\"$ascii\"; filename*=UTF-8''${Api.encodeRfc5987(e.name)}")
        return res
    }

    /**
     * @return (-1,-1) jika header diabaikan (multi-range), null jika tidak dapat dipenuhi, atau (start,end) inklusif.
     */
    fun parseRange(header: String, size: Long): Pair<Long, Long>? {
        if (!header.startsWith("bytes=")) return -1L to -1L
        val spec = header.removePrefix("bytes=").trim()
        if (spec.contains(',')) return -1L to -1L
        val dash = spec.indexOf('-')
        if (dash < 0) return -1L to -1L
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        if (size == 0L) return null
        return if (a.isEmpty()) {
            val suffix = b.toLongOrNull() ?: return -1L to -1L
            if (suffix <= 0) return null
            maxOf(0, size - suffix) to size - 1
        } else {
            val s = a.toLongOrNull() ?: return -1L to -1L
            val e = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return -1L to -1L).coerceAtMost(size - 1)
            if (s >= size || s > e) null else s to e
        }
    }

    private fun httpDate(ms: Long): String {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(Date(ms))
    }
}

/** Antarmuka web statis dari resource classpath "web/". */
object StaticFiles {
    private val types = mapOf("html" to "text/html; charset=utf-8", "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8", "svg" to "image/svg+xml", "png" to "image/png", "ico" to "image/x-icon",
        "webmanifest" to "application/manifest+json")
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun serve(path: String): HttpResponse {
        val name = when {
            path == "/" || path == "/index.html" -> "index.html"
            path.startsWith("/s/") -> "share.html"
            path.startsWith("/assets/") -> path.removePrefix("/assets/")
            else -> "index.html" // SPA fallback
        }
        if (name.contains("..") || name.contains('\\') || name.startsWith("/")) throw ApiError(404, "Tidak ditemukan")
        val bytes = load(name) ?: return HttpResponse.text(404, "Tidak ditemukan")
        val ext = name.substringAfterLast('.', "")
        return HttpResponse(200, Body.Bytes(bytes))
            .header("Content-Type", types[ext] ?: "application/octet-stream")
            .header("Cache-Control", if (ext == "html") "no-cache" else "public, max-age=300")
    }

    private fun load(name: String): ByteArray? = cache[name] ?: StaticFiles::class.java.classLoader
        ?.getResourceAsStream("web/$name")?.use { it.readBytes() }?.also { cache[name] = it }
}
