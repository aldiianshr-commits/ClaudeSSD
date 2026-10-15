package id.cloudssd.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import id.cloudssd.core.server.ClientNetwork
import id.cloudssd.core.server.LocalAddress
import id.cloudssd.core.server.NetworkPolicy
import java.net.Inet4Address

/** Alamat IP lokal tablet lewat API resmi ConnectivityManager (Wi-Fi, Ethernet USB, VPN Tailscale). */
object NetworkAddresses {
    fun list(context: Context): List<LocalAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val out = ArrayList<LocalAddress>()
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val lp = cm.getLinkProperties(n) ?: continue
            val caps = cm.getNetworkCapabilities(n)
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val name = when {
                isVpn -> "vpn"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                else -> lp.interfaceName ?: "net"
            }
            for (la in lp.linkAddresses) {
                val a = la.address as? Inet4Address ?: continue
                val net = NetworkPolicy.classify(a)
                if (net == ClientNetwork.LAN || net == ClientNetwork.VPN) out.add(LocalAddress(a.hostAddress ?: continue, name, net))
            }
        }
        return out
    }
}
