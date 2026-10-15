package id.cloudssd.core.server

import id.cloudssd.core.util.AtomicJsonFile
import id.cloudssd.core.util.bool
import id.cloudssd.core.util.list
import id.cloudssd.core.util.long
import id.cloudssd.core.util.str
import java.io.File

data class ServerSettings(
    val port: Int = 8080,
    /** false = hanya perangkat ini (127.0.0.1). true = perangkat lain di Wi-Fi yang sama. */
    val lanAccess: Boolean = false,
    /** Izinkan klien dari jaringan VPN Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48). */
    val vpnAccess: Boolean = false,
    /** Mode pribadi (false) = hanya administrator. */
    val multiUser: Boolean = false,
    /** Nama host tambahan yang diizinkan pada header Host (mis. pad6.tailnet.ts.net). */
    val extraHosts: List<String> = emptyList(),
    val httpsEnabled: Boolean = false,
    val httpsPort: Int = 8443,
    /** Pengguna sudah membaca & menyetujui peringatan keamanan akses jaringan. */
    val networkWarningAccepted: Boolean = false,
    /** Peringatan bila ruang kosong di bawah nilai ini. */
    val lowSpaceWarnBytes: Long = 10L * 1024 * 1024 * 1024,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "port" to port, "lanAccess" to lanAccess, "vpnAccess" to vpnAccess, "multiUser" to multiUser,
        "extraHosts" to extraHosts, "httpsEnabled" to httpsEnabled, "httpsPort" to httpsPort,
        "networkWarningAccepted" to networkWarningAccepted, "lowSpaceWarnBytes" to lowSpaceWarnBytes,
    )

    fun validate() {
        require(port in 1024..65535) { "Port harus 1024–65535" }
        require(httpsPort in 1024..65535) { "Port HTTPS harus 1024–65535" }
        require(!httpsEnabled || httpsPort != port) { "Port HTTPS tidak boleh sama dengan port HTTP" }
        require(lowSpaceWarnBytes >= 0) { "Nilai batas peringatan tidak valid" }
        extraHosts.forEach { require(Regex("^[a-zA-Z0-9.-]{1,253}$").matches(it)) { "Nama host tidak valid: $it" } }
    }

    companion object {
        fun fromMap(m: Map<String, Any?>): ServerSettings {
            val d = ServerSettings()
            return ServerSettings(
                port = (m.long("port") ?: d.port.toLong()).toInt(),
                lanAccess = m.bool("lanAccess") ?: d.lanAccess,
                vpnAccess = m.bool("vpnAccess") ?: d.vpnAccess,
                multiUser = m.bool("multiUser") ?: d.multiUser,
                extraHosts = m.list("extraHosts")?.filterIsInstance<String>() ?: d.extraHosts,
                httpsEnabled = m.bool("httpsEnabled") ?: d.httpsEnabled,
                httpsPort = (m.long("httpsPort") ?: d.httpsPort.toLong()).toInt(),
                networkWarningAccepted = m.bool("networkWarningAccepted") ?: d.networkWarningAccepted,
                lowSpaceWarnBytes = m.long("lowSpaceWarnBytes") ?: d.lowSpaceWarnBytes,
            )
        }
    }
}

class SettingsStore(dataDir: File) {
    private val file = AtomicJsonFile(File(dataDir, "settings.json"))
    @Volatile var current: ServerSettings = load()
        private set

    private fun load(): ServerSettings {
        @Suppress("UNCHECKED_CAST")
        val m = file.read() as? Map<String, Any?> ?: return ServerSettings()
        return try { ServerSettings.fromMap(m).also { it.validate() } } catch (_: Exception) { ServerSettings() }
    }

    @Synchronized
    fun update(transform: (ServerSettings) -> ServerSettings): ServerSettings {
        val n = transform(current)
        n.validate()
        file.write(n.toMap())
        current = n
        return n
    }
}

/** Pengaturan internal tambahan (mis. kata sandi PKCS12) yang tidak dikirim ke klien. */
object SettingsKeys {
    const val TLS_KEYSTORE = "tls.p12"
    const val TLS_PASSWORD_FILE = "tls.pass"
}

@Suppress("unused")
private fun Map<String, Any?>.strOrNull(k: String) = str(k)
