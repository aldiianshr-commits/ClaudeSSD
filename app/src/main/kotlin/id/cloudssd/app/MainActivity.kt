package id.cloudssd.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import id.cloudssd.app.storage.VolumeInfo
import id.cloudssd.app.ui.C
import id.cloudssd.app.ui.Ui
import id.cloudssd.app.ui.formatBytes
import id.cloudssd.core.api.Api
import id.cloudssd.core.server.LocalAddress
import id.cloudssd.core.server.ServerState
import id.cloudssd.core.server.TreeStats
import id.cloudssd.core.storage.Capacity
import id.cloudssd.core.storage.VPath
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Dashboard native di Xiaomi Pad 6: mulai/hentikan server, pilih SSD, status, keamanan jaringan,
 * HTTPS, akun administrator, panduan baterai, dan log kesalahan.
 */
class MainActivity : Activity() {
    private lateinit var app: CloudApp
    private lateinit var ui: Ui
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var resumed = false

    // Tampilan yang diperbarui berkala
    private lateinit var banners: LinearLayout
    private lateinit var setupCard: LinearLayout
    private lateinit var serverDot: View
    private lateinit var serverStatus: TextView
    private lateinit var serverDetail: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var testResult: TextView
    private lateinit var addresses: LinearLayout
    private lateinit var storageDot: View
    private lateinit var storageStatus: TextView
    private lateinit var storageLocation: TextView
    private lateinit var storageMeter: ProgressBar
    private lateinit var storageCapacity: TextView
    private lateinit var storageWarn: TextView
    private lateinit var statsText: TextView
    private lateinit var activityText: TextView
    private lateinit var remoteText: TextView
    private lateinit var httpsText: TextView
    private lateinit var batteryText: TextView
    private lateinit var errorLog: TextView
    private lateinit var restartNote: TextView

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val storageListener: (StorageState) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = CloudApp.of(this)
        ui = Ui(this)
        ServerService.createChannel(this)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val f = IntentFilter(ServerService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(stateReceiver, f)
        app.storageMonitor.addListener(storageListener)
        app.storageMonitor.checkAsync()
        tick()
    }

    override fun onPause() {
        resumed = false
        unregisterReceiver(stateReceiver)
        app.storageMonitor.removeListener(storageListener)
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun tick() {
        if (!resumed) return
        refresh()
        main.postDelayed({ tick() }, 2000)
    }

    // ================================================================== layout

    private fun buildLayout(): View {
        val root = ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true }
        val page = ui.vertical(20, 16)
        root.addView(page)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        page.addView(ui.horizontal(12).apply {
            addView(ui.text("CloudSSD", 26f, bold = true))
            addView(ui.text("v${Api.VERSION} · server penyimpanan pribadi", 13f, C.MUTED))
        })
        banners = ui.vertical(0, 10)
        page.addView(banners)

        setupCard = buildSetupCard()
        page.addView(setupCard)

        val wide = resources.configuration.screenWidthDp >= 840
        val left = ui.vertical(0, 16)
        val right = if (wide) ui.vertical(0, 16) else left
        left.addView(buildServerCard())
        left.addView(buildAccessCard())
        left.addView(buildStorageCard())
        left.addView(buildStatsCard())
        right.addView(buildNetworkCard())
        right.addView(buildAccountCard())
        right.addView(buildBatteryCard())
        right.addView(buildLogCard())
        if (wide) {
            page.addView(ui.horizontal(16).apply {
                addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                gravity = android.view.Gravity.TOP
            })
        } else page.addView(left)
        return root
    }

    private fun statusRow(dot: View, text: TextView) = ui.horizontal(10).apply { addView(dot); addView(text) }

    private fun buildSetupCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.text("Pengaturan pertama: buat akun administrator", 18f, bold = true))
        card.addView(ui.text("Tidak ada akun bawaan. Buat nama pengguna dan kata sandi Anda sendiri (minimal 10 karakter). Kata sandi disimpan sebagai hash PBKDF2, bukan teks biasa.", 14f, C.TEXT2))
        val user = ui.input("Nama pengguna (mis. aldian)")
        val pw = ui.input("Kata sandi", password = true)
        val pw2 = ui.input("Ulangi kata sandi", password = true)
        val err = ui.text("", 13.5f, C.BAD).apply { visibility = View.GONE }
        card.addView(user); card.addView(pw); card.addView(pw2); card.addView(err)
        card.addView(ui.button("Buat administrator", primary = true) {
            err.visibility = View.GONE
            if (pw.text.toString() != pw2.text.toString()) { err.text = "Konfirmasi kata sandi tidak sama."; err.visibility = View.VISIBLE; return@button }
            val u = user.text.toString().trim().lowercase(); val p = pw.text.toString()
            io.execute {
                val e = try { app.core.users.createAdmin(u, p); null } catch (x: Exception) { x.message }
                main.post {
                    if (e != null) { err.text = e; err.visibility = View.VISIBLE }
                    else {
                        pw.setText(""); pw2.setText("")
                        app.core.logs.activity.add(u, "setup", "Akun administrator dibuat dari tablet")
                        toast("Administrator dibuat. Sekarang pilih folder SSD lalu mulai server.")
                        refresh()
                    }
                }
            }
        })
        return card
    }

    private fun buildServerCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Server"))
        serverDot = ui.dot(C.MUTED); serverStatus = ui.text("—", 20f, bold = true)
        card.addView(statusRow(serverDot, serverStatus))
        serverDetail = ui.text("", 13.5f, C.TEXT2); card.addView(serverDetail)
        btnStart = ui.button("Mulai server", primary = true) { startServer() }
        btnStop = ui.button("Hentikan", danger = true) { ServerService.stop(this); main.postDelayed({ refresh() }, 600) }
        card.addView(ui.horizontal().apply {
            addView(btnStart); addView(btnStop)
            addView(ui.button("Uji koneksi") { testConnection() })
        })
        testResult = ui.text("", 13f, C.TEXT2, mono = true).apply { visibility = View.GONE }
        card.addView(testResult)
        return card
    }

    private fun buildAccessCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Alamat akses"))
        addresses = ui.vertical(0, 8)
        card.addView(addresses)
        remoteText = ui.text("", 13.5f, C.TEXT2)
        card.addView(remoteText)
        return card
    }

    private fun buildStorageCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("SSD / Penyimpanan"))
        storageDot = ui.dot(C.MUTED); storageStatus = ui.text("—", 20f, bold = true)
        card.addView(statusRow(storageDot, storageStatus))
        storageLocation = ui.text("", 13.5f, C.TEXT2); card.addView(storageLocation)
        storageMeter = ui.meter(); card.addView(storageMeter)
        storageCapacity = ui.text("", 14f); card.addView(storageCapacity)
        storageWarn = ui.text("", 13.5f, C.WARN).apply { visibility = View.GONE }; card.addView(storageWarn)
        card.addView(ui.horizontal().apply {
            addView(ui.button("Pilih folder SSD", primary = true) { pickFolder() })
            addView(ui.button("Periksa ulang") { app.storageMonitor.checkAsync(); toast("Memeriksa SSD…") })
        })
        card.addView(ui.text("Pilih folder di SSD (mis. buat folder \"CloudSSD\") melalui pemilih folder Android. Aplikasi tidak pernah memformat atau menghapus SSD secara otomatis.", 12.5f, C.MUTED))
        return card
    }

    private fun buildStatsCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Isi & aktivitas"))
        statsText = ui.text("—", 15f); card.addView(statsText)
        activityText = ui.text("", 13f, C.TEXT2); card.addView(activityText)
        card.addView(ui.button("Hitung ulang jumlah file") {
            io.execute {
                val ok = app.backend?.isAvailableSafe() == true
                if (ok) app.core.stats.refreshAsync(VPath.ROOT)
                main.post { toast(if (ok) "Menghitung di latar belakang…" else "SSD tidak tersedia") }
            }
        })
        return card
    }

    private fun buildNetworkCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Jaringan & keamanan"))
        val s = app.core.settings.current
        val lan = ui.switch("Akses dari Wi-Fi lokal", "HP/laptop di jaringan Wi-Fi yang sama dapat membuka CloudSSD. Nonaktif = hanya tablet ini.", s.lanAccess) { on -> setLan(on) }
        card.addView(lan)
        card.addView(ui.switch("Izinkan VPN Tailscale", "Klien dari jaringan Tailscale (100.64.0.0/10) untuk akses dari luar rumah. Alamat internet publik selalu ditolak.", s.vpnAccess) { on ->
            app.core.settings.update { it.copy(vpnAccess = on) }; showRestartNote()
        })
        card.addView(ui.switch("Drive jaringan (WebDAV)", "CloudSSD tampil sebagai penyimpanan/drive di aplikasi file HP, File Explorer Windows, atau Finder Mac (alamat …/dav/). Login memakai akun CloudSSD. Langsung berlaku.", s.webdavEnabled) { on ->
            app.core.settings.update { it.copy(webdavEnabled = on) }
            app.core.logs.activity.add(null, "settings", if (on) "Drive jaringan (WebDAV) diaktifkan" else "Drive jaringan (WebDAV) dinonaktifkan")
            refresh()
        })
        card.addView(ui.switch("Jaga CPU & Wi-Fi tetap aktif", "Memakai wake lock selama server berjalan agar transfer tidak terputus saat layar mati. Menambah pemakaian baterai.", app.keepAwake) { on ->
            app.keepAwake = on; showRestartNote()
        })
        val port = ui.input("Port", number = true).apply { setText(s.port.toString()) }
        card.addView(ui.horizontal().apply {
            addView(ui.text("Port HTTP", 15f))
            addView(port, LinearLayout.LayoutParams(ui.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ui.button("Simpan port") {
                val p = port.text.toString().toIntOrNull()
                try { app.core.settings.update { it.copy(port = p ?: -1) }; showRestartNote(); toast("Port disimpan") }
                catch (e: Exception) { toast(e.message ?: "Port tidak valid") }
            })
        })
        httpsText = ui.text("", 13.5f, C.TEXT2)
        card.addView(ui.text("HTTPS (opsional)", 15f, bold = true))
        card.addView(httpsText)
        card.addView(ui.horizontal().apply {
            addView(ui.button("Impor sertifikat .p12") { pickCertificate() })
            addView(ui.button("Nonaktifkan HTTPS", danger = true) {
                app.core.settings.update { it.copy(httpsEnabled = false) }; showRestartNote(); refresh()
            })
        })
        restartNote = ui.text("Perubahan berlaku setelah server dimulai ulang.", 13.5f, C.WARN).apply { visibility = View.GONE }
        card.addView(restartNote)
        card.addView(ui.button("Mulai ulang server") {
            if (app.core.state == ServerState.RUNNING) {
                ServerService.stop(this)
                main.postDelayed({ startServer(); restartNote.visibility = View.GONE }, 1200)
            } else toast("Server sedang tidak berjalan")
        })
        return card
    }

    private fun buildAccountCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Akun"))
        card.addView(ui.text("Lupa kata sandi? Setel ulang dari tablet ini (butuh akses fisik). Semua sesi pengguna tersebut akan dikeluarkan.", 13.5f, C.TEXT2))
        val user = ui.input("Nama pengguna")
        val pw = ui.input("Kata sandi baru (min. 10 karakter)", password = true)
        card.addView(user); card.addView(pw)
        card.addView(ui.button("Setel ulang kata sandi") {
            val u = app.core.users.byUsername(user.text.toString())
            if (u == null) { toast("Pengguna tidak ditemukan"); return@button }
            io.execute {
                val err = try { app.core.users.setPassword(u.id, pw.text.toString()); app.core.sessions.revokeAllForUser(u.id); null } catch (e: Exception) { e.message }
                main.post { if (err == null) { pw.setText(""); toast("Kata sandi ${u.username} diganti") } else toast(err) }
            }
        })
        return card
    }

    private fun buildBatteryCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Stabilitas & baterai"))
        batteryText = ui.text("", 14f); card.addView(batteryText)
        card.addView(ui.text(
            "Agar server lebih stabil di Xiaomi Pad 6 (MIUI/HyperOS):\n" +
                "1. Ketuk \"Izinkan berjalan di latar\" di bawah dan pilih Izinkan.\n" +
                "2. Info aplikasi › Penghemat baterai › pilih \"Tidak ada batasan\".\n" +
                "3. Aktifkan \"Mulai otomatis\" (Autostart) bila tersedia.\n" +
                "4. Di layar Aplikasi terbaru, kunci CloudSSD (tekan lama › ikon gembok).\n" +
                "5. Sambungkan tablet ke charger bila dipakai sebagai server terus-menerus.\n" +
                "Android tetap dapat menghentikan aplikasi (memori rendah, pembaruan sistem, restart). " +
                "Setelah tablet dinyalakan ulang, buka CloudSSD dan tekan Mulai server.",
            13f, C.TEXT2))
        card.addView(ui.horizontal().apply {
            addView(ui.button("Izinkan berjalan di latar") { requestIgnoreBattery() })
            addView(ui.button("Info aplikasi") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        })
        return card
    }

    private fun buildLogCard(): LinearLayout {
        val card = ui.card()
        card.addView(ui.label("Log kesalahan"))
        errorLog = ui.text("", 12f, C.TEXT2, mono = true).apply { setTextIsSelectable(true) }
        card.addView(errorLog)
        card.addView(ui.horizontal().apply {
            addView(ui.button("Salin log") {
                val text = app.core.logs.errors.recent(100).joinToString("\n\n") { fmtTime(it.time) + "  " + it.detail }
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CloudSSD log", text))
                toast("Log disalin")
            })
            addView(ui.button("Buka di browser") {
                val port = if (app.core.httpPort > 0) app.core.httpPort else app.core.settings.current.port
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/")))
            })
        })
        return card
    }

    // ================================================================== aksi

    private fun startServer() {
        if (!app.core.users.hasAdmin()) { toast("Buat akun administrator terlebih dahulu"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        ServerService.start(this)
        main.postDelayed({ refresh() }, 800)
    }

    private fun setLan(on: Boolean) {
        if (!on) { app.core.settings.update { it.copy(lanAccess = false) }; showRestartNote(); return }
        AlertDialog.Builder(this)
            .setTitle("Aktifkan akses jaringan lokal?")
            .setMessage(
                "Semua perangkat yang terhubung ke Wi-Fi yang sama akan dapat menjangkau halaman login CloudSSD.\n\n" +
                    "• Gunakan hanya di jaringan tepercaya (Wi-Fi rumah), jangan di Wi-Fi publik.\n" +
                    "• Koneksi HTTP di jaringan lokal tidak terenkripsi; untuk akses dari luar rumah gunakan VPN (Tailscale) atau HTTPS.\n" +
                    "• File hanya dapat diakses setelah login. Server tidak pernah dibuka ke internet publik.\n" +
                    "• Pakai kata sandi yang kuat.")
            .setPositiveButton("Saya mengerti, aktifkan") { _, _ ->
                app.core.settings.update { it.copy(lanAccess = true, networkWarningAccepted = true) }
                showRestartNote(); refresh()
            }
            .setNegativeButton("Batal") { _, _ -> recreateSwitchState() }
            .setOnCancelListener { recreateSwitchState() }
            .show()
    }

    private fun recreateSwitchState() { recreate() }

    private fun showRestartNote() { if (app.core.state == ServerState.RUNNING) restartNote.visibility = View.VISIBLE }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            app.backend?.treeUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        try { startActivityForResult(i, REQ_TREE) } catch (e: Exception) { toast("Pemilih folder tidak tersedia: ${e.message}") }
    }

    private fun pickCertificate() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, REQ_CERT)
    }

    @Deprecated("Activity framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_TREE -> onTreePicked(uri)
            REQ_CERT -> onCertPicked(uri)
        }
    }

    private fun onTreePicked(uri: Uri) {
        try { app.setStorage(uri) } catch (e: Exception) { toast("Izin tidak dapat disimpan: ${e.message}"); return }
        val info = VolumeInfo.info(this, uri)
        if (info.isPrimaryInternal) {
            AlertDialog.Builder(this).setTitle("Ini penyimpanan internal tablet")
                .setMessage("Folder yang dipilih berada di penyimpanan internal, bukan SSD eksternal. CloudSSD tetap bisa berjalan, tetapi kapasitasnya terbatas pada ruang internal tablet. Sambungkan SSD lalu pilih folder di SSD bila ingin memakai SSD.")
                .setPositiveButton("Mengerti", null).show()
        }
        io.execute {
            val st = app.storageMonitor.checkNow()
            main.post {
                toast(if (st == StorageState.AVAILABLE) "Lokasi penyimpanan siap: ${VolumeInfo.describe(this, uri)}" else "Lokasi dipilih, tetapi belum dapat diakses")
                if (st == StorageState.AVAILABLE) app.core.stats.refreshAsync(VPath.ROOT)
                refresh()
            }
        }
    }

    private fun onCertPicked(uri: Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { s ->
                val b = s.readBytes(); if (b.size > 1_000_000) throw IllegalArgumentException("File terlalu besar untuk sertifikat"); b
            }
        } catch (e: Exception) { toast("Gagal membaca file: ${e.message}"); return } ?: return
        val pw = ui.input("Kata sandi file .p12", password = true)
        val box = ui.vertical(20).apply { addView(pw) }
        AlertDialog.Builder(this).setTitle("Kata sandi sertifikat").setView(box)
            .setPositiveButton("Impor") { _, _ ->
                try {
                    app.core.importTlsCertificate(bytes, pw.text.toString())
                    app.core.settings.update { it.copy(httpsEnabled = true) }
                    toast("Sertifikat diimpor. HTTPS aktif di port ${app.core.settings.current.httpsPort} setelah server dimulai ulang.")
                    showRestartNote(); refresh()
                } catch (e: Exception) { toast("Sertifikat tidak valid: ${e.message}") }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) { toast("Sudah diizinkan"); return }
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun testConnection() {
        val core = app.core
        if (core.state != ServerState.RUNNING) { toast("Server belum berjalan"); return }
        testResult.visibility = View.VISIBLE
        testResult.text = "Menguji…"
        io.execute {
            val targets = mutableListOf("127.0.0.1")
            if (core.settings.current.lanAccess || core.settings.current.vpnAccess) core.localAddresses().forEach { targets.add(it.ip) }
            val lines = targets.map { ip ->
                val url = "http://$ip:${core.httpPort}/api/ping"
                val t0 = System.currentTimeMillis()
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 3000
                    val code = c.responseCode
                    c.disconnect()
                    if (code == 200) "✓ $url  (${System.currentTimeMillis() - t0} ms)" else "✗ $url  HTTP $code"
                } catch (e: Exception) { "✗ $url  ${e.javaClass.simpleName}: ${e.message}" }
            }
            val note = "\nCatatan: uji ini dari tablet sendiri. Untuk memastikan perangkat lain dapat terhubung, buka alamat di atas dari HP/laptop."
            main.post { testResult.text = lines.joinToString("\n") + note }
        }
    }

    // ================================================================== refresh

    private data class Snapshot(
        val state: ServerState, val lastError: String?, val port: Int, val httpsPort: Int, val startedAt: Long, val conns: Int,
        val hasAdmin: Boolean, val setupCode: String?, val addresses: List<LocalAddress>,
        val storage: StorageState, val storageDesc: String?, val volume: VolumeInfo.Info?, val capacity: Capacity?, val stats: TreeStats?, val scanning: Boolean,
        val activity: List<String>, val errors: List<String>, val lan: Boolean, val vpn: Boolean, val webdav: Boolean, val lowWarn: Long,
        val httpsEnabled: Boolean, val certInfo: String?, val remoteVerified: String?, val batteryOk: Boolean, val unexpectedStop: Boolean,
        val notifOk: Boolean,
    )

    private fun refresh() {
        io.execute {
            val core = app.core
            val b = app.backend
            val st = app.storageMonitor.state
            val cap = if (st == StorageState.AVAILABLE) try { b?.capacity() } catch (_: Exception) { null } else null
            val stats = core.stats.get(VPath.ROOT)
            if (st == StorageState.AVAILABLE && stats == null && core.state == ServerState.RUNNING) core.stats.refreshAsync(VPath.ROOT)
            val s = core.settings.current
            val snap = Snapshot(
                core.state, core.lastError, core.httpPort, core.httpsPort, core.startedAt, core.activeConnections,
                core.users.hasAdmin(), core.setupCode, core.localAddresses(),
                st, b?.description, b?.let { VolumeInfo.info(this, it.treeUri) }, cap, stats, core.stats.isScanning(VPath.ROOT),
                core.logs.activity.recent(6).map { fmtTime(it.time) + "  " + (it.user?.let { u -> "$u · " } ?: "") + it.detail },
                core.logs.errors.recent(12).map { fmtTime(it.time) + "  " + it.detail },
                s.lanAccess, s.vpnAccess, s.webdavEnabled, s.lowSpaceWarnBytes, s.httpsEnabled, core.tlsCertificateInfo(),
                core.lastRemoteAccess?.let { "${it.ip}, ${fmtTime(it.time)}" },
                getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true,
                app.serverShouldRun && core.state != ServerState.RUNNING,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
            main.post { if (!isDestroyed) render(snap) }
        }
    }

    private fun render(s: Snapshot) {
        // ---- banner
        banners.removeAllViews()
        if (s.unexpectedStop) banners.addView(ui.banner("Server berhenti tanpa diminta",
            "Kemungkinan dihentikan oleh sistem/penghemat baterai, atau tablet dimulai ulang. Tekan \"Mulai server\" untuk memulihkan.", C.BAD))
        if (s.state == ServerState.ERROR && s.lastError != null) banners.addView(ui.banner("Server gagal dimulai", s.lastError, C.BAD))
        when (s.storage) {
            StorageState.NOT_CONFIGURED -> banners.addView(ui.banner("Lokasi SSD belum dipilih", "Sambungkan SSD ke port USB-C, lalu tekan \"Pilih folder SSD\".", C.WARN))
            StorageState.UNAVAILABLE -> banners.addView(ui.banner("SSD terputus atau izin hilang",
                "File tidak dapat dibaca/disimpan. Sambungkan kembali SSD. Bila sudah tersambung, pilih ulang folder SSD.", C.BAD))
            else -> {}
        }
        if (s.capacity != null && s.capacity.freeBytes < s.lowWarn) banners.addView(ui.banner("Ruang SSD hampir penuh", "Tersisa ${formatBytes(s.capacity.freeBytes)}.", C.WARN))
        if (!s.notifOk && s.state == ServerState.RUNNING) banners.addView(ui.banner("Izin notifikasi ditolak", "Server tetap berjalan, tetapi notifikasi status tidak terlihat. Izinkan notifikasi di Info aplikasi.", C.WARN))

        setupCard.visibility = if (s.hasAdmin) View.GONE else View.VISIBLE

        // ---- server
        val running = s.state == ServerState.RUNNING
        (serverDot.background as android.graphics.drawable.GradientDrawable).setColor(if (running) C.OK else if (s.state == ServerState.ERROR) C.BAD else C.MUTED)
        serverStatus.text = when (s.state) { ServerState.RUNNING -> "Berjalan"; ServerState.ERROR -> "Gagal"; ServerState.STOPPED -> "Berhenti" }
        serverDetail.text = if (running) {
            val up = (System.currentTimeMillis() - s.startedAt) / 60000
            "Port ${s.port}" + (if (s.httpsPort > 0) " · HTTPS ${s.httpsPort}" else "") + " · aktif ${up / 60} j ${up % 60} mnt · ${s.conns} koneksi\n" +
                if (s.lan) "Dapat diakses dari Wi-Fi lokal" else "Hanya dapat diakses dari tablet ini"
        } else "Server tidak menerima koneksi."
        btnStart.isEnabled = !running && s.hasAdmin; btnStart.alpha = if (btnStart.isEnabled) 1f else .5f
        btnStop.isEnabled = running; btnStop.alpha = if (running) 1f else .5f

        // ---- alamat
        addresses.removeAllViews()
        if (!s.hasAdmin && s.setupCode != null) addresses.addView(ui.text("Kode pengaturan untuk browser: ${s.setupCode}", 14f, C.WARN, mono = true))
        val port = if (running) s.port else app.core.settings.current.port
        val urls = mutableListOf("http://127.0.0.1:$port/" to "tablet ini")
        if (s.lan) s.addresses.filter { it.network.name == "LAN" }.forEach { urls.add("http://${it.ip}:$port/" to "Wi-Fi (${it.iface})") }
        if (s.vpn) s.addresses.filter { it.network.name == "VPN" }.forEach { urls.add("http://${it.ip}:$port/" to "Tailscale") }
        if (s.lan && s.addresses.none { it.network.name == "LAN" }) addresses.addView(ui.text("Tidak ada alamat Wi-Fi terdeteksi. Pastikan tablet terhubung ke Wi-Fi.", 13.5f, C.WARN))
        if (s.webdav) urls.addAll(urls.map { (u, l) -> "${u}dav/" to "drive · $l" })
        for ((u, label) in urls) {
            addresses.addView(ui.horizontal().apply {
                addView(ui.text(u, 15f, if (running) C.TEXT else C.MUTED, mono = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text(label, 12f, C.MUTED))
                addView(ui.button("Salin") {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CloudSSD", u)); toast("Disalin")
                })
            })
        }
        remoteText.text = when {
            !s.vpn -> "Akses jarak jauh: nonaktif. Lihat panduan Tailscale di dokumentasi untuk akses aman dari luar rumah."
            s.remoteVerified != null -> "Akses jarak jauh: terverifikasi — perangkat VPN terakhir terhubung dari ${s.remoteVerified}."
            s.addresses.any { it.network.name == "VPN" } -> "Akses jarak jauh: antarmuka Tailscale terdeteksi, belum teruji. Buka alamat Tailscale dari HP/laptop untuk memverifikasi."
            else -> "Akses jarak jauh: VPN diizinkan, tetapi Tailscale tidak terdeteksi di tablet. Pasang & aktifkan aplikasi Tailscale."
        }

        // ---- penyimpanan
        val avail = s.storage == StorageState.AVAILABLE
        (storageDot.background as android.graphics.drawable.GradientDrawable).setColor(if (avail) C.OK else if (s.storage == StorageState.UNAVAILABLE) C.BAD else C.MUTED)
        storageStatus.text = when (s.storage) { StorageState.AVAILABLE -> "Terhubung"; StorageState.UNAVAILABLE -> "Terputus"; StorageState.NOT_CONFIGURED -> "Belum dipilih" }
        storageLocation.text = s.storageDesc ?: "Belum ada lokasi penyimpanan."
        if (s.capacity != null) {
            storageMeter.visibility = View.VISIBLE
            storageMeter.progress = ((s.capacity.usedBytes.toDouble() / s.capacity.totalBytes) * 1000).toInt()
            storageCapacity.text = "${formatBytes(s.capacity.usedBytes)} terpakai dari ${formatBytes(s.capacity.totalBytes)} · tersisa ${formatBytes(s.capacity.freeBytes)}"
        } else {
            storageMeter.visibility = View.GONE
            storageCapacity.text = if (avail) "Kapasitas tidak dilaporkan oleh sistem untuk lokasi ini." else "Kapasitas tidak tersedia."
        }
        val warn = when {
            s.volume?.isPrimaryInternal == true -> "Lokasi ini adalah penyimpanan internal tablet, bukan SSD eksternal."
            s.volume != null && s.volume.mounted == false -> "Volume tidak dalam status terpasang (mounted)."
            else -> null
        }
        storageWarn.visibility = if (warn != null) View.VISIBLE else View.GONE
        storageWarn.text = warn ?: ""

        // ---- statistik
        statsText.text = when {
            s.stats != null -> "${s.stats.files} file · ${s.stats.folders} folder · ${formatBytes(s.stats.bytes)}\nDihitung ${fmtTime(s.stats.computedAt)}"
            s.scanning -> "Sedang menghitung…"
            else -> "Belum dihitung."
        }
        activityText.text = if (s.activity.isEmpty()) "Belum ada aktivitas." else s.activity.joinToString("\n")

        // ---- HTTPS, baterai, log
        httpsText.text = (if (s.httpsEnabled) "Aktif (port ${app.core.settings.current.httpsPort}). " else "Nonaktif. ") +
            (s.certInfo?.let { "Sertifikat: $it" } ?: "Belum ada sertifikat. Lihat panduan HTTPS di dokumentasi.")
        batteryText.text = if (s.batteryOk) "✓ Optimasi baterai Android dinonaktifkan untuk CloudSSD." else "⚠ Optimasi baterai masih aktif: Android dapat menghentikan server saat layar mati."
        batteryText.setTextColor(if (s.batteryOk) C.OK else C.WARN)
        errorLog.text = if (s.errors.isEmpty()) "Tidak ada kesalahan tercatat." else s.errors.joinToString("\n\n")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun fmtTime(ms: Long) = SimpleDateFormat("dd MMM HH:mm:ss", Locale("id", "ID")).format(Date(ms))

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_TREE = 10
        private const val REQ_CERT = 11
        private const val REQ_NOTIF = 12
    }
}

private fun id.cloudssd.app.storage.SafStorageBackend.isAvailableSafe(): Boolean = try { isAvailable() } catch (_: Exception) { false }
