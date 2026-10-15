package id.cloudssd.core.auth

import id.cloudssd.core.util.AtomicJsonFile
import id.cloudssd.core.util.Clock
import id.cloudssd.core.util.long
import id.cloudssd.core.util.str
import java.io.File

data class Session(
    val id: String,          // id publik (untuk daftar sesi), bukan token
    val tokenHash: String,   // SHA-256 dari token cookie; token asli tidak disimpan
    val userId: String,
    val csrf: String,
    val createdAt: Long,
    var lastSeen: Long,
    val ip: String,
    val userAgent: String,
    val credentialVersion: Long,
)

/**
 * Sesi login. Token acak 256-bit dikirim sebagai cookie HttpOnly; server hanya menyimpan hash-nya.
 */
class SessionManager(
    dataDir: File,
    private val clock: Clock,
    val idleTimeoutMs: Long = 3L * 24 * 3600 * 1000,
    val absoluteTimeoutMs: Long = 14L * 24 * 3600 * 1000,
) {
    private val file = AtomicJsonFile(File(dataDir, "sessions.json"))
    private val sessions = HashMap<String, Session>() // key = tokenHash
    private var lastPersist = 0L

    init {
        @Suppress("UNCHECKED_CAST")
        (file.read() as? List<Map<String, Any?>>)?.forEach { m ->
            val s = Session(
                m.str("id") ?: return@forEach, m.str("tokenHash") ?: return@forEach, m.str("userId") ?: return@forEach,
                m.str("csrf") ?: return@forEach, m.long("createdAt") ?: 0, m.long("lastSeen") ?: 0,
                m.str("ip") ?: "", m.str("userAgent") ?: "", m.long("credentialVersion") ?: 1,
            )
            sessions[s.tokenHash] = s
        }
        purgeExpired()
    }

    /** @return pasangan (token mentah untuk cookie, sesi). */
    @Synchronized
    fun create(user: User, ip: String, userAgent: String): Pair<String, Session> {
        val token = Tokens.urlToken(32)
        val now = clock.now()
        val s = Session(Tokens.urlToken(9), Tokens.sha256Hex(token), user.id, Tokens.urlToken(24), now, now, ip,
            userAgent.take(200), user.credentialVersion)
        sessions[s.tokenHash] = s
        // Batasi jumlah sesi per pengguna.
        val mine = sessions.values.filter { it.userId == user.id }.sortedBy { it.lastSeen }
        if (mine.size > 20) mine.take(mine.size - 20).forEach { sessions.remove(it.tokenHash) }
        persist(force = true)
        return token to s
    }

    /** Validasi token cookie; memperbarui lastSeen. */
    @Synchronized
    fun validate(token: String?, user: (String) -> User?): Pair<Session, User>? {
        if (token.isNullOrEmpty() || token.length > 100) return null
        val hash = Tokens.sha256Hex(token)
        val s = sessions[hash] ?: return null
        val now = clock.now()
        val u = user(s.userId)
        if (u == null || u.disabled || u.credentialVersion != s.credentialVersion ||
            now - s.lastSeen > idleTimeoutMs || now - s.createdAt > absoluteTimeoutMs) {
            sessions.remove(hash)
            persist(force = true)
            return null
        }
        s.lastSeen = now
        persist(force = false)
        return s to u
    }

    @Synchronized
    fun revoke(tokenHashOrId: String) {
        val removed = sessions.remove(tokenHashOrId) ?: sessions.values.firstOrNull { it.id == tokenHashOrId }?.let { sessions.remove(it.tokenHash) }
        if (removed != null) persist(force = true)
    }

    @Synchronized
    fun revokeAllForUser(userId: String, except: String? = null) {
        sessions.values.removeAll { it.userId == userId && it.tokenHash != except }
        persist(force = true)
    }

    @Synchronized
    fun forUser(userId: String): List<Session> = sessions.values.filter { it.userId == userId }.sortedByDescending { it.lastSeen }

    @Synchronized
    fun purgeExpired() {
        val now = clock.now()
        val before = sessions.size
        sessions.values.removeAll { now - it.lastSeen > idleTimeoutMs || now - it.createdAt > absoluteTimeoutMs }
        if (sessions.size != before) persist(force = true)
    }

    private fun persist(force: Boolean) {
        val now = clock.now()
        if (!force && now - lastPersist < 5 * 60_000) return
        lastPersist = now
        file.write(sessions.values.map {
            linkedMapOf(
                "id" to it.id, "tokenHash" to it.tokenHash, "userId" to it.userId, "csrf" to it.csrf,
                "createdAt" to it.createdAt, "lastSeen" to it.lastSeen, "ip" to it.ip, "userAgent" to it.userAgent,
                "credentialVersion" to it.credentialVersion,
            )
        })
    }
}

/**
 * Pembatasan percobaan login per kunci (IP dan nama pengguna).
 * 5 kegagalan -> kunci 1 menit, berlipat dua tiap kegagalan berikutnya, maks 30 menit.
 */
class LoginLimiter(private val clock: Clock, private val freeAttempts: Int = 5) {
    private class State(var failures: Int = 0, var lockedUntil: Long = 0, var lastFailure: Long = 0)
    private val states = HashMap<String, State>()

    /** Sisa detik penguncian, 0 jika boleh mencoba. */
    @Synchronized
    fun retryAfterSeconds(vararg keys: String): Long {
        val now = clock.now()
        var max = 0L
        for (k in keys) {
            val s = states[k] ?: continue
            if (s.lockedUntil > now) max = maxOf(max, (s.lockedUntil - now + 999) / 1000)
        }
        return max
    }

    @Synchronized
    fun recordFailure(vararg keys: String) {
        val now = clock.now()
        for (k in keys) {
            val s = states.getOrPut(k) { State() }
            // Lupakan kegagalan lama (> 1 jam).
            if (now - s.lastFailure > 3600_000) s.failures = 0
            s.failures++
            s.lastFailure = now
            if (s.failures >= freeAttempts) {
                val exp = (s.failures - freeAttempts).coerceAtMost(5)
                s.lockedUntil = now + minOf(60_000L shl exp, 30 * 60_000L)
            }
        }
        if (states.size > 10_000) states.entries.removeAll { now - it.value.lastFailure > 3600_000 }
    }

    @Synchronized
    fun recordSuccess(vararg keys: String) {
        for (k in keys) states.remove(k)
    }
}
