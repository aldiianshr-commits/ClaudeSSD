package id.cloudssd.core.server

import id.cloudssd.core.api.Api
import id.cloudssd.core.auth.LoginLimiter
import id.cloudssd.core.auth.PasswordHasher
import id.cloudssd.core.auth.SessionManager
import id.cloudssd.core.auth.Tokens
import id.cloudssd.core.auth.UserStore
import id.cloudssd.core.http.HttpServer
import id.cloudssd.core.storage.StorageBackend
import id.cloudssd.core.storage.StorageUnavailableException
import id.cloudssd.core.storage.VPath
import id.cloudssd.core.util.Clock
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

enum class ServerState { STOPPED, RUNNING, ERROR }

data class LocalAddress(val ip: String, val iface: String, val network: ClientNetwork)

data class RemoteAccessCheck(val time: Long, val ip: String)

/**
 * Inti CloudSSD: menggabungkan penyimpanan, akun, sesi, unggahan, berbagi, log, dan server HTTP.
 * Kelas ini bebas dari API Android sehingga dapat diuji di JVM dan dijalankan di PC.
 */
class CloudServer(
    val dataDir: File,
    private val storageProvider: () -> StorageBackend?,
    val clock: Clock = Clock.SYSTEM,
    hashIterations: Int = PasswordHasher.DEFAULT_ITERATIONS,
) {
    init { dataDir.mkdirs() }

    val hasher = PasswordHasher(hashIterations)
    val settings = SettingsStore(dataDir)
    val users = UserStore(dataDir, hasher, clock)
    val sessions = SessionManager(dataDir, clock)
    val limiter = LoginLimiter(clock)
    val shares = ShareStore(dataDir, hasher, clock)
    val logs = ServerLogs(dataDir, clock)
    val uploads = UploadManager(dataDir, { backend() }, clock)
    val stats = StorageStats({ backend() }, clock) { msg, t -> logs.error(msg, t) }
    private val api = Api(this)

    @Volatile var state: ServerState = ServerState.STOPPED
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var startedAt: Long = 0
        private set
    @Volatile var lastRemoteAccess: RemoteAccessCheck? = null
        internal set

    private var http: HttpServer? = null
    private var https: HttpServer? = null
    private var scheduler: ScheduledExecutorService? = null

    /** Kode sekali pakai untuk membuat admin lewat browser (ditampilkan di aplikasi tablet). */
    @Volatile var setupCode: String? = if (users.hasAdmin()) null else Tokens.numericCode(8)
        private set

    internal fun consumeSetupCode() { setupCode = null }

    /** Backend aktif; melempar bila belum dipilih/terputus. */
    fun backend(): StorageBackend = storageProvider() ?: throw StorageUnavailableException("Lokasi penyimpanan belum dipilih")

    fun backendOrNull(): StorageBackend? = storageProvider()

    val httpPort: Int get() = http?.localPort ?: -1
    val httpsPort: Int get() = https?.localPort ?: -1
    val activeConnections: Int get() = (http?.activeConnections?.get() ?: 0) + (https?.activeConnections?.get() ?: 0)

    @Synchronized
    fun start() {
        if (state == ServerState.RUNNING) return
        val s = settings.current
        lastError = null
        try {
            if (!users.hasAdmin() && setupCode == null) setupCode = Tokens.numericCode(8)
            val bind = if (s.lanAccess || s.vpnAccess) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
            val h = HttpServer(bind, s.port, api::handle, onError = { m, t -> logs.error(m, t) })
            h.start()
            http = h
            if (s.httpsEnabled) {
                val factory = tlsFactory() ?: throw IllegalStateException("HTTPS aktif tetapi sertifikat belum diimpor")
                val hs = HttpServer(bind, s.httpsPort, api::handle, sslFactory = factory, onError = { m, t -> logs.error(m, t) })
                try { hs.start() } catch (e: Exception) { h.stop(); http = null; throw e }
                https = hs
            }
            startedAt = clock.now()
            state = ServerState.RUNNING
            scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cloudssd-maint").apply { isDaemon = true } }.also {
                it.scheduleWithFixedDelay({ maintenance() }, 1, 30, TimeUnit.MINUTES)
            }
            logs.activity.add(null, "server", "Server dimulai di port ${s.port}" + if (s.httpsEnabled) " (HTTPS ${s.httpsPort})" else "")
            if (backendOrNull()?.isAvailable() == true) stats.refreshAsync(VPath.ROOT)
        } catch (e: Exception) {
            state = ServerState.ERROR
            lastError = when {
                e is java.net.BindException -> "Port ${s.port} sedang dipakai aplikasi lain. Ubah port di pengaturan."
                else -> e.message ?: e.javaClass.simpleName
            }
            logs.error("Gagal memulai server", e)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        http?.stop(); http = null
        https?.stop(); https = null
        scheduler?.shutdownNow(); scheduler = null
        if (state == ServerState.RUNNING) logs.activity.add(null, "server", "Server dihentikan")
        state = ServerState.STOPPED
        logs.flush()
    }

    /** Hentikan total (aplikasi ditutup). */
    fun shutdown() {
        stop()
        stats.shutdown()
    }

    private fun maintenance() {
        try {
            sessions.purgeExpired()
            shares.purge()
            if (backendOrNull()?.isAvailable() == true) uploads.cleanupStale()
            logs.flush()
        } catch (e: Exception) {
            logs.error("Pemeliharaan berkala gagal", e)
        }
    }

    // ---------- TLS ----------

    private fun tlsFiles() = File(dataDir, SettingsKeys.TLS_KEYSTORE) to File(dataDir, SettingsKeys.TLS_PASSWORD_FILE)

    fun hasTlsCertificate(): Boolean = tlsFiles().first.exists()

    /** Impor sertifikat PKCS#12 (.p12/.pfx) berisi kunci privat + rantai sertifikat. */
    fun importTlsCertificate(p12: ByteArray, password: String) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(p12), password.toCharArray())
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IllegalArgumentException("File tidak berisi kunci privat")
        ks.getCertificate(alias) ?: throw IllegalArgumentException("File tidak berisi sertifikat")
        val (kf, pf) = tlsFiles()
        kf.writeBytes(p12)
        pf.writeText(password)
        logs.activity.add(null, "settings", "Sertifikat HTTPS diimpor")
    }

    fun removeTlsCertificate() {
        val (kf, pf) = tlsFiles()
        kf.delete(); pf.delete()
    }

    fun tlsCertificateInfo(): String? {
        val (kf, pf) = tlsFiles()
        if (!kf.exists()) return null
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            kf.inputStream().use { ks.load(it, pf.readText().toCharArray()) }
            val alias = ks.aliases().toList().first { ks.isKeyEntry(it) }
            val cert = ks.getCertificate(alias) as java.security.cert.X509Certificate
            "${cert.subjectX500Principal.name} (berlaku s.d. ${java.text.SimpleDateFormat("yyyy-MM-dd").format(cert.notAfter)})"
        } catch (e: Exception) { "Sertifikat tidak dapat dibaca: ${e.message}" }
    }

    private fun tlsFactory(): SSLServerSocketFactory? {
        val (kf, pf) = tlsFiles()
        if (!kf.exists()) return null
        val pass = pf.readText().toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        kf.inputStream().use { ks.load(it, pass) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx.serverSocketFactory
    }

    // ---------- Info jaringan ----------

    /** Sumber alamat tambahan dari platform (Android: ConnectivityManager/LinkProperties). */
    @Volatile var addressProvider: (() -> List<LocalAddress>)? = null

    fun localAddresses(): List<LocalAddress> {
        val result = ArrayList<LocalAddress>()
        try { addressProvider?.invoke()?.let { result.addAll(it) } } catch (_: Exception) {}
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in ifaces.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a !is Inet4Address) continue
                    val net = NetworkPolicy.classify(a)
                    val ip = a.hostAddress ?: continue
                    if ((net == ClientNetwork.LAN || net == ClientNetwork.VPN) && result.none { it.ip == ip }) result.add(LocalAddress(ip, ni.name, net))
                }
            }
        } catch (_: Exception) {}
        return result.distinctBy { it.ip }.sortedBy { it.network.ordinal }
    }
}
