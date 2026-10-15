package id.cloudssd.core

import id.cloudssd.core.server.CloudServer
import id.cloudssd.core.storage.Capacity
import id.cloudssd.core.storage.LocalFileBackend
import id.cloudssd.core.storage.StorageBackend
import id.cloudssd.core.storage.VPath
import id.cloudssd.core.util.Clock
import id.cloudssd.core.util.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

const val ADMIN = "admin"
const val ADMIN_PW = "rahasia-super-123"

fun freePort(): Int = ServerSocket(0).use { it.localPort }

fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

/**
 * Backend uji: membungkus LocalFileBackend dengan kemampuan mensimulasikan
 * SSD terputus, kapasitas palsu, dan disk penuh saat menulis.
 */
class FaultyBackend(root: File) : LocalFileBackend(root) {
    @Volatile var connected = true
    @Volatile var fakeCapacity: Capacity? = null
    /** Putuskan SSD setelah sejumlah byte ditulis (null = tidak pernah). */
    @Volatile var disconnectAfterBytes: Long? = null
    /** Lempar ENOSPC setelah sejumlah byte ditulis (null = tidak pernah). */
    @Volatile var noSpaceAfterBytes: Long? = null

    override fun isAvailable(): Boolean = connected && super.isAvailable()
    override fun capacity(): Capacity? = if (!isAvailable()) null else fakeCapacity ?: super.capacity()

    override fun openWriteAt(path: VPath, offset: Long): OutputStream {
        val real = super.openWriteAt(path, offset)
        return object : OutputStream() {
            var written = 0L
            fun check(n: Int) {
                disconnectAfterBytes?.let { if (written + n > it) { connected = false; throw wrap(IOException("I/O error (EIO)")) } }
                noSpaceAfterBytes?.let { if (written + n > it) throw wrap(IOException("write failed: ENOSPC (No space left on device)")) }
                written += n
            }
            override fun write(b: Int) { check(1); real.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { check(len); real.write(b, off, len) }
            override fun flush() = real.flush()
            override fun close() = real.close()
        }
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        val real = super.openRead(path, offset)
        return object : InputStream() {
            fun check() { if (!connected) throw wrap(IOException("I/O error (EIO)")) }
            override fun read(): Int { check(); return real.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return real.read(b, off, len) }
            override fun close() = real.close()
        }
    }
}

class Res(val status: Int, val body: String, val headers: Map<String, List<String>>) {
    @Suppress("UNCHECKED_CAST")
    val json: Map<String, Any?> get() = Json.parse(body) as Map<String, Any?>
    fun header(n: String) = headers.entries.firstOrNull { it.key.equals(n, true) }?.value?.firstOrNull()
    override fun toString() = "HTTP $status $body"
}

/** Klien HTTP uji yang menyimpan cookie sesi dan token CSRF seperti browser. */
class Client(val port: Int, var host: String = "127.0.0.1:$port") {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    val cookies = LinkedHashMap<String, String>()
    var csrf: String? = null

    fun url(path: String) = URI("http://127.0.0.1:$port$path")

    fun send(method: String, path: String, json: Any? = null, raw: ByteArray? = null, headers: Map<String, String> = emptyMap(),
             csrfHeader: Boolean = true): Res {
        val b = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(120))
        if (cookies.isNotEmpty()) b.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (csrfHeader && csrf != null && method != "GET") b.header("X-CSRF-Token", csrf)
        headers.forEach { (k, v) -> b.header(k, v) }
        val pub = when {
            json != null -> { b.header("Content-Type", "application/json"); HttpRequest.BodyPublishers.ofString(Json.stringify(json)) }
            raw != null -> HttpRequest.BodyPublishers.ofByteArray(raw)
            else -> HttpRequest.BodyPublishers.noBody()
        }
        b.method(method, pub)
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        r.headers().allValues("set-cookie").forEach { c ->
            val kv = c.substringBefore(';')
            val k = kv.substringBefore('='); val v = kv.substringAfter('=')
            if (v.isEmpty() || c.contains("Max-Age=0")) cookies.remove(k) else cookies[k] = v
        }
        return Res(r.statusCode(), r.body(), r.headers().map())
    }

    fun get(path: String, headers: Map<String, String> = emptyMap()) = send("GET", path, headers = headers)
    fun post(path: String, json: Any? = emptyMap<String, Any>()) = send("POST", path, json = json)

    fun getBytes(path: String, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val b = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(120))
        if (cookies.isNotEmpty()) b.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        return r.statusCode() to r.body()
    }

    /** Unduh dengan streaming dan kembalikan SHA-256 (tidak memuat file ke RAM). */
    fun downloadSha256(path: String): Pair<Int, String> {
        val b = HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(300))
        if (cookies.isNotEmpty()) b.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val r = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        val md = java.security.MessageDigest.getInstance("SHA-256")
        r.body().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return r.statusCode() to md.digest().joinToString("") { "%02x".format(it) }
    }

    fun login(user: String = ADMIN, pw: String = ADMIN_PW): Res {
        val r = post("/api/login", mapOf("username" to user, "password" to pw))
        if (r.status == 200) csrf = r.json["csrf"] as String
        return r
    }

    fun q(path: String) = URLEncoder.encode(path, "UTF-8")

    /** Unggah lewat API bertahap; mengembalikan respons complete. */
    fun upload(dir: String, name: String, data: ByteArray, conflict: String = "ask", chunk: Int = 1 shl 20): Res {
        val init = post("/api/uploads", mapOf("path" to dir, "name" to name, "size" to data.size, "conflict" to conflict))
        if (init.status != 201) return init
        val id = init.json["id"] as String
        var off = 0
        while (off < data.size) {
            val end = minOf(off + chunk, data.size)
            val r = send("PUT", "/api/uploads/$id?offset=$off", raw = data.copyOfRange(off, end))
            if (r.status != 200) return r
            off = end
        }
        return post("/api/uploads/$id/complete")
    }
}

class TestEnv(
    val clock: Clock = Clock.SYSTEM,
    configure: (id.cloudssd.core.server.ServerSettings) -> id.cloudssd.core.server.ServerSettings = { it },
) : AutoCloseable {
    val root = tempDir("cssd-root")
    val data = tempDir("cssd-data")
    val backend = FaultyBackend(root)
    @Volatile var storageSelected = true
    val server = CloudServer(data, { if (storageSelected) backend else null }, clock, hashIterations = 1000)
    val port = freePort()

    init {
        server.settings.update { configure(it.copy(port = port)) }
        server.users.createAdmin(ADMIN, ADMIN_PW)
        server.start()
    }

    fun client() = Client(port)
    fun admin() = client().also { val r = it.login(); check(r.status == 200) { r.toString() } }

    override fun close() {
        server.shutdown()
        root.deleteRecursively(); data.deleteRecursively()
    }
}
