package id.cloudssd.core.server

import id.cloudssd.core.util.AtomicJsonFile
import id.cloudssd.core.util.Clock
import id.cloudssd.core.util.long
import id.cloudssd.core.util.str
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque

data class LogEntry(val time: Long, val user: String?, val action: String, val detail: String, val ip: String?) {
    fun toJson(): Map<String, Any?> = linkedMapOf("time" to time, "user" to user, "action" to action, "detail" to detail, "ip" to ip)
}

/** Log berbentuk ring buffer yang disimpan ke penyimpanan internal. */
class RingLog(private val file: File?, private val clock: Clock, private val capacity: Int) {
    private val store = file?.let { AtomicJsonFile(it) }
    private val entries = ArrayDeque<LogEntry>()
    private var dirty = false
    private var lastSave = 0L

    init {
        try {
            @Suppress("UNCHECKED_CAST")
            (store?.read() as? List<Map<String, Any?>>)?.forEach {
                entries.addLast(LogEntry(it.long("time") ?: 0, it.str("user"), it.str("action") ?: "", it.str("detail") ?: "", it.str("ip")))
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun add(user: String?, action: String, detail: String, ip: String? = null) {
        entries.addFirst(LogEntry(clock.now(), user, action, detail.take(500), ip))
        while (entries.size > capacity) entries.removeLast()
        dirty = true
        if (clock.now() - lastSave > 10_000) flush()
    }

    @Synchronized
    fun recent(limit: Int, filterUser: String? = null): List<LogEntry> =
        entries.asSequence().filter { filterUser == null || it.user == filterUser }.take(limit).toList()

    @Synchronized
    fun flush() {
        if (!dirty || store == null) return
        try { store.write(entries.map { it.toJson() }) } catch (_: Exception) {}
        dirty = false
        lastSave = clock.now()
    }
}

class ServerLogs(dataDir: File?, clock: Clock) {
    val activity = RingLog(dataDir?.let { File(it, "activity.json") }, clock, 300)
    val errors = RingLog(dataDir?.let { File(it, "errors.json") }, clock, 200)

    fun error(message: String, t: Throwable? = null) {
        val detail = if (t == null) message else {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "$message: ${t.javaClass.simpleName}: ${t.message}\n" + sw.toString().lines().take(6).joinToString("\n")
        }
        errors.add(null, "error", detail)
    }

    fun flush() { activity.flush(); errors.flush() }
}
