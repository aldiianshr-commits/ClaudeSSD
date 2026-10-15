package id.cloudssd.core

import id.cloudssd.core.server.CloudServer
import id.cloudssd.core.storage.LocalFileBackend
import java.io.File

/**
 * Menjalankan CloudSSD di PC (Windows/macOS/Linux) untuk mencoba antarmuka web
 * atau sebagai server cadangan. Bukan bagian dari APK.
 *
 *   java -jar cloudssd-core-dev.jar --root /path/ke/folder --data ./cloudssd-data [--port 8080] [--lan]
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val root = File(arg("--root") ?: error("--root wajib diisi"))
    val data = File(arg("--data") ?: "cloudssd-data")
    if (!root.isDirectory) error("Folder root tidak ditemukan: $root")
    val backend = LocalFileBackend(root)
    val server = CloudServer(data, { backend })
    server.settings.update { it.copy(port = arg("--port")?.toInt() ?: it.port, lanAccess = "--lan" in args, networkWarningAccepted = true) }
    server.start()
    println("CloudSSD berjalan: http://127.0.0.1:${server.httpPort}/  (root: ${backend.description})")
    if ("--lan" in args) server.localAddresses().forEach { println("  LAN: http://${it.ip}:${server.httpPort}/  (${it.iface})") }
    server.setupCode?.let { println("Kode pengaturan admin: $it") }
    Runtime.getRuntime().addShutdownHook(Thread { server.shutdown() })
    Thread.currentThread().join()
}
