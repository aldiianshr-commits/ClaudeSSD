package id.cloudssd.core.server

import id.cloudssd.core.storage.NameRules
import id.cloudssd.core.storage.StorageBackend
import id.cloudssd.core.storage.VPath
import id.cloudssd.core.util.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class TreeStats(val files: Long, val folders: Long, val bytes: Long, val computedAt: Long) {
    fun toJson(): Map<String, Any?> = linkedMapOf("files" to files, "folders" to folders, "bytes" to bytes, "computedAt" to computedAt)
}

/**
 * Menghitung jumlah file/folder dan pemakaian byte dari data AKTUAL di SSD.
 * Memindai rekursif di thread latar (SAF bisa lambat untuk ribuan file), menyimpan hasil
 * dalam cache, dan menyesuaikan secara bertahap saat unggah/hapus.
 */
class StorageStats(private val backend: () -> StorageBackend, private val clock: Clock, private val onError: (String, Throwable) -> Unit) {
    private val cache = ConcurrentHashMap<String, TreeStats>()
    private val scanning = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cloudssd-stats").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    fun get(path: VPath): TreeStats? = cache[key(path)]
    fun isScanning(path: VPath) = key(path) in scanning

    /** Minta pemindaian ulang di latar belakang. */
    fun refreshAsync(path: VPath) {
        val k = key(path)
        if (!scanning.add(k)) return
        executor.execute {
            try {
                cache[k] = scanNow(path)
            } catch (e: Exception) {
                onError("Gagal menghitung statistik penyimpanan untuk /$k", e)
            } finally {
                scanning.remove(k)
            }
        }
    }

    /** Pemindaian sinkron (dipakai untuk kuota dan pengujian). */
    fun scanNow(path: VPath): TreeStats {
        val b = backend()
        var files = 0L; var folders = 0L; var bytes = 0L
        val stack = ArrayDeque<VPath>()
        stack.addLast(path)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (e in b.list(dir)) {
                if (dir.isRoot && e.name == NameRules.RESERVED_DIR) continue
                if (e.isDirectory) { folders++; stack.addLast(e.path) } else { files++; bytes += e.size }
            }
        }
        val s = TreeStats(files, folders, bytes, clock.now())
        cache[key(path)] = s
        return s
    }

    /** Usage dengan cache; memindai sinkron bila belum pernah dihitung. */
    fun usage(path: VPath): Long = (get(path) ?: scanNow(path)).bytes

    /** Penyesuaian bertahap untuk semua cache yang mencakup [changed]. */
    fun adjust(changed: VPath, deltaFiles: Long, deltaFolders: Long, deltaBytes: Long) {
        for ((k, v) in cache) {
            val p = VPath.parse(k)
            if (changed.startsWith(p) && changed != p) {
                cache[k] = v.copy(
                    files = (v.files + deltaFiles).coerceAtLeast(0), folders = (v.folders + deltaFolders).coerceAtLeast(0),
                    bytes = (v.bytes + deltaBytes).coerceAtLeast(0),
                )
            }
        }
    }

    fun invalidateAll() = cache.clear()

    fun shutdown() = executor.shutdownNow()

    private fun key(p: VPath) = p.segments.joinToString("/")
}
