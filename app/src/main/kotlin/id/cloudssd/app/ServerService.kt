package id.cloudssd.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import id.cloudssd.core.server.ServerState

/**
 * Foreground service yang menjalankan server HTTP CloudSSD.
 *
 * Android tetap dapat menghentikan proses ini (penghemat baterai MIUI/HyperOS, memori rendah,
 * restart tablet). Aplikasi tidak mencoba melewati pembatasan tersebut; status selalu
 * ditampilkan apa adanya dan server dapat dimulai ulang secara manual.
 */
class ServerService : Service() {
    private lateinit var app: CloudApp
    private lateinit var worker: Handler
    private var thread: HandlerThread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastStorage: StorageState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = CloudApp.of(this)
        createChannel(this)
        thread = HandlerThread("cloudssd-service").also { it.start(); worker = Handler(it.looper) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer(userInitiated = true)
            return START_NOT_STICKY
        }
        // Wajib memanggil startForeground secepatnya setelah startForegroundService.
        if (!enterForeground(buildNotification("Memulai server…", null))) {
            app.serverShouldRun = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) app.core.logs.activity.add(null, "server", "Layanan dijalankan ulang oleh sistem")
        worker.post { startServer() }
        return START_STICKY
    }

    private fun enterForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        // Mis. ForegroundServiceStartNotAllowedException saat sistem mencoba memulai dari latar belakang.
        app.core.logs.error("Android tidak mengizinkan layanan latar depan dimulai saat ini; buka aplikasi dan mulai server secara manual", e)
        false
    }

    private fun startServer() {
        val core = app.core
        if (core.state == ServerState.RUNNING) { updateNotification(); scheduleMonitor(); return }
        if (!core.users.hasAdmin()) {
            core.logs.error("Server tidak dimulai: akun administrator belum dibuat")
            stopServer(userInitiated = false); return
        }
        try {
            core.start()
            app.serverShouldRun = true
            acquireLocks()
            updateNotification()
            scheduleMonitor()
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        } catch (e: Exception) {
            // lastError sudah diisi oleh core; jangan berpura-pura berjalan.
            app.serverShouldRun = false
            stopServer(userInitiated = false)
        }
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (app.core.state != ServerState.RUNNING) return
            val s = app.storageMonitor.checkNow()
            if (s != lastStorage) { lastStorage = s; updateNotification() }
            worker.postDelayed(this, 5000)
        }
    }

    private fun scheduleMonitor() {
        worker.removeCallbacks(monitor)
        worker.postDelayed(monitor, 5000)
    }

    private fun stopServer(userInitiated: Boolean) {
        worker.removeCallbacks(monitor)
        worker.post {
            app.core.stop()
            if (userInitiated) app.serverShouldRun = false
            releaseLocks()
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        if (!app.keepAwake) return
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CloudSSD:server").apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm?.createWifiLock(mode, "CloudSSD:wifi")?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null; wifiLock = null
    }

    private fun updateNotification() {
        val core = app.core
        val text: String
        val sub: String?
        if (core.state == ServerState.RUNNING) {
            val addr = core.localAddresses().firstOrNull()
            text = if (app.core.settings.current.lanAccess && addr != null) "Berjalan di http://${addr.ip}:${core.httpPort}" else "Berjalan (hanya tablet ini) · port ${core.httpPort}"
            sub = when (app.storageMonitor.state) {
                StorageState.AVAILABLE -> "SSD terhubung"
                StorageState.UNAVAILABLE -> "⚠ SSD terputus — sambungkan kembali"
                StorageState.NOT_CONFIGURED -> "⚠ Lokasi SSD belum dipilih"
            }
        } else {
            text = "Server berhenti"; sub = core.lastError
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text, sub))
    }

    private fun buildNotification(text: String, sub: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cloud)
            .setContentTitle("CloudSSD")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(if (sub != null) "$text\n$sub" else text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hentikan", stop).build())
            .build()
    }

    override fun onDestroy() {
        worker.removeCallbacks(monitor)
        if (app.core.state == ServerState.RUNNING) app.core.stop()
        releaseLocks()
        thread?.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "server"
        const val NOTIF_ID = 1
        const val ACTION_START = "id.cloudssd.app.START"
        const val ACTION_STOP = "id.cloudssd.app.STOP"
        const val ACTION_STATE_CHANGED = "id.cloudssd.app.STATE_CHANGED"

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Status server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Notifikasi tetap selama server CloudSSD berjalan"
                    setShowBadge(false)
                })
            }
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
