package id.cloudssd.core.http

import id.cloudssd.core.util.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    val body: InputStream,
    val remoteAddress: InetAddress?,
    val version: String,
    val secure: Boolean,
) {
    /** Atribut bebas yang diisi router (mis. user yang sedang login). */
    val attributes = HashMap<String, Any?>()

    fun header(name: String): String? = headers[name.lowercase()]
    val allHeaders: Map<String, String> get() = headers

    val contentLength: Long? get() = header("content-length")?.toLongOrNull()

    val cookies: Map<String, String> by lazy {
        val raw = header("cookie") ?: return@lazy emptyMap<String, String>()
        raw.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()
    }

    val remoteIp: String get() = remoteAddress?.hostAddress ?: "?"

    /** Membaca body kecil (JSON). Menolak body yang lebih besar dari [max]. */
    fun readSmallBody(max: Int = 64 * 1024): ByteArray {
        val declared = contentLength
        if (declared != null && declared > max) throw PayloadTooLarge()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = body.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
            if (out.size() > max) throw PayloadTooLarge()
        }
        return out.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    fun jsonBody(): Map<String, Any?> {
        val ct = header("content-type") ?: ""
        if (!ct.lowercase().startsWith("application/json")) throw ApiError(415, "Content-Type harus application/json")
        val text = String(readSmallBody(), StandardCharsets.UTF_8)
        val parsed = try { Json.parse(text) } catch (e: IllegalArgumentException) { throw ApiError(400, "JSON tidak valid") }
        return parsed as? Map<String, Any?> ?: throw ApiError(400, "Body JSON harus berupa objek")
    }

    /** Membuang sisa body; false jika terlalu besar (koneksi akan ditutup). */
    fun drainBody(max: Long): Boolean {
        return try {
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = body.read(buf)
                if (n == -1) return true
                total += n
                if (total > max) return false
            }
            @Suppress("UNREACHABLE_CODE") true
        } catch (_: IOException) {
            false
        }
    }
}

class PayloadTooLarge : IOException("Body terlalu besar")

/** Kesalahan API yang dikirim ke klien sebagai JSON {error, code}. */
class ApiError(val status: Int, override val message: String, val code: String? = null, val extra: Map<String, Any?> = emptyMap()) :
    RuntimeException(message)

sealed class Body {
    object Empty : Body()
    class Bytes(val data: ByteArray) : Body()
    /** length < 0 berarti chunked. */
    class Stream(val length: Long, val writer: (OutputStream) -> Unit) : Body()
}

class HttpResponse(val status: Int, val body: Body = Body.Empty) {
    val headers = LinkedHashMap<String, MutableList<String>>()
    var closeConnection = false
    @Volatile var bodyFailed = false

    fun header(name: String, value: String): HttpResponse {
        headers.getOrPut(name) { mutableListOf() }.add(value)
        return this
    }

    fun setHeader(name: String, value: String): HttpResponse {
        headers[name] = mutableListOf(value)
        return this
    }

    companion object {
        fun json(status: Int, value: Any?): HttpResponse =
            HttpResponse(status, Body.Bytes(Json.stringify(value).toByteArray(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store")

        fun ok(value: Any?) = json(200, value)

        fun text(status: Int, text: String): HttpResponse =
            HttpResponse(status, Body.Bytes(text.toByteArray(StandardCharsets.UTF_8)))
                .header("Content-Type", "text/plain; charset=utf-8")

        fun error(status: Int, message: String, code: String? = null, extra: Map<String, Any?> = emptyMap()): HttpResponse {
            val m = LinkedHashMap<String, Any?>()
            m["error"] = message
            if (code != null) m["code"] = code
            m.putAll(extra)
            return json(status, m)
        }
    }
}
