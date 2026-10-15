package id.cloudssd.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import id.cloudssd.app.storage.SafStorageBackend
import id.cloudssd.core.server.CloudServer
import java.io.File

/**
 * Objek aplikasi: memegang satu instance CloudServer untuk seluruh proses
 * (dipakai bersama oleh Activity dan ForegroundService).
 */
class CloudApp : Application() {
    lateinit var prefs: SharedPreferences
        private set
    lateinit var core: CloudServer
        private set
    lateinit var storageMonitor: StorageMonitor
        private set

    @Volatile var backend: SafStorageBackend? = null
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("cloudssd", Context.MODE_PRIVATE)
        // Data akun/sesi/log disimpan di penyimpanan internal aplikasi (bukan SSD), terenkripsi oleh Android (FBE).
        core = CloudServer(File(filesDir, "server"), { backend })
        core.addressProvider = { NetworkAddresses.list(this) }
        prefs.getString(KEY_TREE, null)?.let { s ->
            val uri = Uri.parse(s)
            if (contentResolver.persistedUriPermissions.any { it.uri == uri }) backend = SafStorageBackend(this, uri)
        }
        storageMonitor = StorageMonitor(this)
        storageMonitor.register()
    }

    /** Menyimpan lokasi SSD yang dipilih lewat pemilih folder Android. */
    fun setStorage(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        val old = prefs.getString(KEY_TREE, null)
        if (old != null && old != uri.toString()) {
            try { contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) } catch (_: Exception) {}
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        backend = SafStorageBackend(this, uri)
        core.stats.invalidateAll()
        core.logs.activity.add(null, "storage", "Lokasi penyimpanan dipilih: ${backend?.description}")
    }

    fun clearStorage() {
        prefs.getString(KEY_TREE, null)?.let {
            try { contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        }
        prefs.edit().remove(KEY_TREE).apply()
        backend = null
        core.stats.invalidateAll()
    }

    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(v) { prefs.edit().putBoolean(KEY_KEEP_AWAKE, v).apply() }

    /** Ditandai true saat server berjalan; jika proses mati mendadak, UI dapat memberi tahu pengguna. */
    var serverShouldRun: Boolean
        get() = prefs.getBoolean(KEY_SHOULD_RUN, false)
        set(v) { prefs.edit().putBoolean(KEY_SHOULD_RUN, v).apply() }

    companion object {
        const val KEY_TREE = "tree_uri"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_SHOULD_RUN = "should_run"

        fun of(ctx: Context): CloudApp = ctx.applicationContext as CloudApp
    }
}
