package id.cloudssd.core.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocketFactory

/**
 * Server HTTP/1.1 minimal tanpa dependensi, aman untuk Android dan JVM.
 *
 * - Body request di-stream (tidak pernah dimuat utuh ke RAM).
 * - Body response bisa berupa stream dengan panjang diketahui (download/Range) atau chunked.
 * - Keep-alive, batas ukuran header, batas jumlah thread.
 */
class HttpServer(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    private val sslFactory: SSLServerSocketFactory? = null,
    private val maxThreads: Int = 64,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private var pool: ThreadPoolExecutor? = null
    private val openSockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    val activeConnections = AtomicInteger(0)

    val isRunning: Boolean get() = serverSocket?.let { !it.isClosed } ?: false
    val localPort: Int get() = serverSocket?.localPort ?: -1
    val isSecure: Boolean get() = sslFactory != null

    @Synchronized
    fun start() {
        check(serverSocket == null) { "Server sudah berjalan" }
        val ss = sslFactory?.createServerSocket() ?: ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindAddress, port), 64)
        } catch (e: IOException) {
            try { ss.close() } catch (_: IOException) {}
            throw e
        }
        serverSocket = ss
        val counter = AtomicInteger()
        // core = max: thread baru dibuat sampai batas sebelum antrean dipakai
        // (TPE standar hanya menambah thread ketika antrean penuh, yang membuat koneksi keep-alive saling memblokir).
        val p = ThreadPoolExecutor(
            maxThreads, maxThreads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(maxThreads)
        ) { r -> Thread(r, "cloudssd-http-" + counter.incrementAndGet()).apply { isDaemon = true } }
        p.allowCoreThreadTimeOut(true)
        pool = p
        acceptThread = Thread({ acceptLoop(ss, p) }, "cloudssd-accept").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        val ss = serverSocket ?: return
        serverSocket = null
        try { ss.close() } catch (_: IOException) {}
        synchronized(openSockets) {
            for (s in openSockets) try { s.close() } catch (_: IOException) {}
            openSockets.clear()
        }
        pool?.shutdownNow()
        pool?.awaitTermination(5, TimeUnit.SECONDS)
        pool = null
        acceptThread?.join(2000)
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket, p: ThreadPoolExecutor) {
        while (!ss.isClosed) {
            val socket = try { ss.accept() } catch (e: IOException) {
                if (!ss.isClosed) onError("Gagal menerima koneksi", e)
                continue
            }
            try {
                p.execute { serve(socket) }
            } catch (e: RejectedExecutionException) {
                // Server terlalu sibuk: jawab 503 lalu tutup.
                try {
                    socket.soTimeout = 2000
                    socket.getOutputStream().write(
                        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\nRetry-After: 2\r\n\r\n".toByteArray()
                    )
                } catch (_: IOException) {}
                try { socket.close() } catch (_: IOException) {}
            }
        }
    }

    private fun serve(socket: Socket) {
        openSockets.add(socket)
        activeConnections.incrementAndGet()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val remote = (socket.remoteSocketAddress as? InetSocketAddress)?.address
            var keepAlive = true
            var first = true
            while (keepAlive && !socket.isClosed) {
                // Koneksi idle (keep-alive) ditutup lebih cepat agar tidak menahan thread.
                socket.soTimeout = if (first) IDLE_TIMEOUT_MS else KEEPALIVE_IDLE_MS
                first = false
                val req = try {
                    readRequest(input, remote, sslFactory != null) { socket.soTimeout = IDLE_TIMEOUT_MS }
                } catch (e: BadRequest) {
                    writeSimple(output, e.status, e.message ?: "Bad Request")
                    break
                } ?: break
                keepAlive = handleOne(req, output)
                // Habiskan sisa body agar koneksi bisa dipakai ulang.
                if (keepAlive) keepAlive = req.drainBody(MAX_DRAIN)
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: SocketException) {
        } catch (_: EOFException) {
        } catch (e: IOException) {
            // Klien memutus koneksi di tengah transfer: normal.
        } catch (e: Throwable) {
            onError("Kesalahan tak terduga pada koneksi", e)
        } finally {
            try { socket.close() } catch (_: IOException) {}
            openSockets.remove(socket)
            activeConnections.decrementAndGet()
        }
    }

    /** @return true jika koneksi boleh dipertahankan (keep-alive). */
    private fun handleOne(req: HttpRequest, out: OutputStream): Boolean {
        if (req.header("expect")?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
        }
        val response = try {
            handler(req)
        } catch (e: Throwable) {
            onError("Handler gagal untuk ${req.method} ${req.path}", e)
            HttpResponse.text(500, "Kesalahan server internal")
        }
        val clientWantsClose = req.header("connection")?.equals("close", ignoreCase = true) == true ||
            req.version == "HTTP/1.0"
        val keepAlive = !clientWantsClose && !response.closeConnection
        writeResponse(out, req, response, keepAlive)
        return keepAlive && !response.bodyFailed
    }

    private fun writeResponse(out: OutputStream, req: HttpRequest, res: HttpResponse, keepAlive: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n")
        val body = res.body
        val isHead = req.method == "HEAD"
        val chunked = body is Body.Stream && body.length < 0
        for ((k, values) in res.headers) for (v in values) sb.append(k).append(": ").append(sanitizeHeader(v)).append("\r\n")
        when (body) {
            is Body.Bytes -> sb.append("Content-Length: ").append(body.data.size).append("\r\n")
            is Body.Stream -> if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
                else sb.append("Content-Length: ").append(body.length).append("\r\n")
            Body.Empty -> if (res.status != 304 && res.status != 204) sb.append("Content-Length: 0\r\n")
        }
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        if (!isHead) {
            when (body) {
                is Body.Bytes -> out.write(body.data)
                is Body.Stream -> {
                    val target = if (chunked) ChunkedOutputStream(out) else out
                    try {
                        body.writer(target)
                        if (chunked) (target as ChunkedOutputStream).finish()
                    } catch (e: IOException) {
                        // Header sudah terkirim; satu-satunya cara memberi tahu klien adalah memutus koneksi
                        // sehingga unduhan tidak dianggap selesai dengan data terpotong.
                        res.bodyFailed = true
                        throw e
                    }
                }
                Body.Empty -> {}
            }
        }
        out.flush()
    }

    private class BadRequest(val status: Int, msg: String) : IOException(msg)

    private fun readRequest(input: InputStream, remote: InetAddress?, secure: Boolean, onStart: () -> Unit): HttpRequest? {
        val requestLine = readLine(input, MAX_LINE, allowEof = true) ?: return null
        if (requestLine.isEmpty()) {
            // Toleransi CRLF ekstra antar request.
            return readRequest(input, remote, secure, onStart)
        }
        onStart()
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw BadRequest(400, "Baris request tidak valid")
        val (method, target, version) = parts
        if (!version.startsWith("HTTP/1.")) throw BadRequest(505, "Versi HTTP tidak didukung")
        val headers = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input, MAX_LINE, allowEof = false) ?: throw EOFException()
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADERS_BYTES || headers.size > MAX_HEADERS) throw BadRequest(431, "Header terlalu besar")
            val idx = line.indexOf(':')
            if (idx <= 0) throw BadRequest(400, "Header tidak valid")
            val name = line.substring(0, idx).trim().lowercase(Locale.ROOT)
            val value = line.substring(idx + 1).trim()
            headers[name] = headers[name]?.let { if (name == "cookie") "$it; $value" else "$it, $value" } ?: value
        }
        val qIdx = target.indexOf('?')
        val rawPath = if (qIdx >= 0) target.substring(0, qIdx) else target
        val rawQuery = if (qIdx >= 0) target.substring(qIdx + 1) else ""
        val path = try { decodePath(rawPath) } catch (e: IllegalArgumentException) { throw BadRequest(400, "Path tidak valid") }
        val query = parseQuery(rawQuery)

        val te = headers["transfer-encoding"]
        val body: InputStream = when {
            te != null && te.lowercase(Locale.ROOT).contains("chunked") -> ChunkedInputStream(input)
            headers["content-length"] != null -> {
                val len = headers["content-length"]!!.toLongOrNull() ?: throw BadRequest(400, "Content-Length tidak valid")
                if (len < 0) throw BadRequest(400, "Content-Length tidak valid")
                LimitedInputStream(input, len)
            }
            else -> LimitedInputStream(input, 0)
        }
        return HttpRequest(method.uppercase(Locale.ROOT), path, query, headers, body, remote, version, secure)
    }

    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            out.write((Integer.toHexString(len) + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            out.write(b, off, len)
            out.write(CRLF)
        }
        override fun flush() = out.flush()
        fun finish() { out.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
        override fun close() {}
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 60_000
        const val KEEPALIVE_IDLE_MS = 15_000
        private const val MAX_LINE = 16 * 1024
        private const val MAX_HEADERS_BYTES = 64 * 1024
        private const val MAX_HEADERS = 100
        private const val MAX_DRAIN = 4L * 1024 * 1024
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        internal fun readLine(input: InputStream, max: Int, allowEof: Boolean): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val c = input.read()
                if (c == -1) {
                    if (buf.size() == 0 && allowEof) return null
                    throw EOFException()
                }
                if (c == '\n'.code) break
                if (c != '\r'.code) buf.write(c)
                if (buf.size() > max) throw BadRequest(431, "Baris terlalu panjang")
            }
            return String(buf.toByteArray(), StandardCharsets.UTF_8)
        }

        fun decodePath(raw: String): String {
            // Jangan ubah '+' menjadi spasi pada path (hanya pada query).
            return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        }

        fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val i = pair.indexOf('=')
                try {
                    val k = URLDecoder.decode(if (i >= 0) pair.substring(0, i) else pair, "UTF-8")
                    val v = if (i >= 0) URLDecoder.decode(pair.substring(i + 1), "UTF-8") else ""
                    if (!map.containsKey(k)) map[k] = v
                } catch (_: IllegalArgumentException) {
                    throw BadRequest(400, "Query tidak valid")
                }
            }
            return map
        }

        private fun sanitizeHeader(v: String) = v.replace("\r", "").replace("\n", "")

        private fun writeSimple(out: OutputStream, status: Int, msg: String) {
            try {
                val b = msg.toByteArray(StandardCharsets.UTF_8)
                out.write("HTTP/1.1 $status ${reason(status)}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(b)
                out.flush()
            } catch (_: IOException) {}
        }

        fun reason(status: Int): String = when (status) {
            100 -> "Continue"
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
            301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 410 -> "Gone"; 411 -> "Length Required"
            413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 416 -> "Range Not Satisfiable"
            423 -> "Locked"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"; 503 -> "Service Unavailable"; 505 -> "HTTP Version Not Supported"
            507 -> "Insufficient Storage"
            else -> "Status"
        }
    }
}

/** Membatasi pembacaan tepat sejumlah byte (Content-Length). */
class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
    var consumed = 0L
        private set
    val remaining: Long get() = limit - consumed

    override fun read(): Int {
        if (consumed >= limit) return -1
        val c = input.read()
        if (c == -1) throw EOFException("Koneksi terputus sebelum body selesai")
        consumed++
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= limit) return -1
        val toRead = minOf(len.toLong(), limit - consumed).toInt()
        val n = input.read(b, off, toRead)
        if (n == -1) throw EOFException("Koneksi terputus sebelum body selesai")
        consumed += n
        return n
    }

    override fun available(): Int = minOf(input.available().toLong(), limit - consumed).toInt()
    override fun close() {}
}

/** Decoder Transfer-Encoding: chunked (untuk klien yang tidak mengirim Content-Length). */
class ChunkedInputStream(private val input: InputStream) : InputStream() {
    private var chunkRemaining = 0L
    private var finished = false

    private fun nextChunk(): Boolean {
        if (finished) return false
        val line = HttpServer.readLine(input, 1024, allowEof = false) ?: throw EOFException()
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("Chunk tidak valid")
        if (size == 0L) {
            // trailer
            while (true) {
                val t = HttpServer.readLine(input, 8192, allowEof = false) ?: break
                if (t.isEmpty()) break
            }
            finished = true
            return false
        }
        chunkRemaining = size
        return true
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (chunkRemaining == 0L) {
            if (!nextChunk()) return -1
        }
        val n = input.read(b, off, minOf(len.toLong(), chunkRemaining).toInt())
        if (n == -1) throw EOFException("Koneksi terputus")
        chunkRemaining -= n
        if (chunkRemaining == 0L) {
            // CRLF setelah data chunk
            HttpServer.readLine(input, 16, allowEof = false)
        }
        return n
    }

    override fun close() {}
}
