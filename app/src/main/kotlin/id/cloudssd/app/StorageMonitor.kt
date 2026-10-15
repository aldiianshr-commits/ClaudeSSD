package id.cloudssd.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class StorageState { NOT_CONFIGURED, AVAILABLE, UNAVAILABLE }

/**
 * Memantau SSD: siaran sistem (mount/unmount/eject), callback volume (Android 11+),
 * dan pemeriksaan langsung lewat SAF. Status selalu berasal dari pemeriksaan nyata.
 */
class StorageMonitor(private val app: CloudApp) {
    private val listeners = CopyOnWriteArrayList<(StorageState) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cloudssd-storage-check").apply { isDaemon = true } }
    @Volatile var state: StorageState = StorageState.NOT_CONFIGURED
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { checkAsync() }
    }

    fun register() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.getSystemService(StorageManager::class.java)?.registerStorageVolumeCallback(io, object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) { checkAsync() }
            })
        }
        checkAsync()
    }

    fun addListener(l: (StorageState) -> Unit) { listeners.add(l) }
    fun removeListener(l: (StorageState) -> Unit) { listeners.remove(l) }

    fun checkAsync() { io.execute { checkNow() } }

    /** Pemeriksaan sinkron (jangan dipanggil dari thread UI). */
    fun checkNow(): StorageState {
        val b = app.backend
        b?.invalidateCache()
        val s = when {
            b == null -> StorageState.NOT_CONFIGURED
            b.isAvailable() -> StorageState.AVAILABLE
            else -> StorageState.UNAVAILABLE
        }
        if (s != state) {
            val old = state
            state = s
            when {
                s == StorageState.UNAVAILABLE -> app.core.logs.error("SSD terputus atau izin akses hilang (${b?.description})")
                s == StorageState.AVAILABLE && old == StorageState.UNAVAILABLE -> {
                    app.core.logs.activity.add(null, "storage", "SSD tersambung kembali")
                    app.core.stats.invalidateAll()
                }
            }
            main.post { listeners.forEach { it(s) } }
        }
        return s
    }
}
