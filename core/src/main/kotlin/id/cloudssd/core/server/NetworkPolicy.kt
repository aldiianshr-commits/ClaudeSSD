package id.cloudssd.core.server

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

enum class ClientNetwork { LOOPBACK, LAN, VPN, PUBLIC }

/**
 * Menentukan siapa yang boleh terhubung. Alamat internet publik SELALU ditolak:
 * server tidak pernah diekspos ke internet secara langsung.
 */
object NetworkPolicy {

    fun classify(addr: InetAddress?): ClientNetwork {
        if (addr == null) return ClientNetwork.PUBLIC
        val a = unmap(addr)
        if (a.isLoopbackAddress) return ClientNetwork.LOOPBACK
        if (a is Inet4Address) {
            val b = a.address.map { it.toInt() and 0xff }
            // Tailscale / CGNAT 100.64.0.0/10
            if (b[0] == 100 && b[1] in 64..127) return ClientNetwork.VPN
            if (a.isSiteLocalAddress || a.isLinkLocalAddress) return ClientNetwork.LAN
            return ClientNetwork.PUBLIC
        }
        if (a is Inet6Address) {
            val b = a.address.map { it.toInt() and 0xff }
            // Tailscale fd7a:115c:a1e0::/48
            if (b[0] == 0xfd && b[1] == 0x7a && b[2] == 0x11 && b[3] == 0x5c && b[4] == 0xa1 && b[5] == 0xe0) return ClientNetwork.VPN
            if (a.isLinkLocalAddress || a.isSiteLocalAddress) return ClientNetwork.LAN
            if ((b[0] and 0xfe) == 0xfc) return ClientNetwork.LAN // ULA fc00::/7
        }
        return ClientNetwork.PUBLIC
    }

    fun isAllowed(addr: InetAddress?, s: ServerSettings): Boolean = when (classify(addr)) {
        ClientNetwork.LOOPBACK -> true
        ClientNetwork.LAN -> s.lanAccess
        ClientNetwork.VPN -> s.vpnAccess
        ClientNetwork.PUBLIC -> false
    }

    /**
     * Perlindungan DNS rebinding: header Host harus berupa alamat IP, localhost,
     * nama *.ts.net (Tailscale MagicDNS, bila VPN diizinkan), atau nama host yang didaftarkan admin.
     */
    fun isHostAllowed(hostHeader: String?, s: ServerSettings): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        var host = hostHeader.trim().lowercase(Locale.ROOT)
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end < 0) return false
            return isIpLiteral(host.substring(1, end))
        }
        val colon = host.lastIndexOf(':')
        if (colon >= 0) host = host.substring(0, colon)
        if (host.endsWith(".")) host = host.dropLast(1)
        if (host == "localhost") return true
        if (isIpLiteral(host)) return true
        if (s.vpnAccess && host.endsWith(".ts.net")) return true
        return s.extraHosts.any { it.equals(host, ignoreCase = true) }
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private fun isIpLiteral(h: String): Boolean = IPV4.matches(h) || (h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' })

    private fun unmap(a: InetAddress): InetAddress {
        if (a is Inet6Address) {
            val b = a.address
            val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
            if (mapped) return InetAddress.getByAddress(b.copyOfRange(12, 16))
        }
        return a
    }
}
