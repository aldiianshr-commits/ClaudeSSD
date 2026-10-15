package id.cloudssd.core.server

import id.cloudssd.core.auth.PasswordHasher
import id.cloudssd.core.auth.Tokens
import id.cloudssd.core.auth.ValidationException
import id.cloudssd.core.util.AtomicJsonFile
import id.cloudssd.core.util.Clock
import id.cloudssd.core.util.bool
import id.cloudssd.core.util.long
import id.cloudssd.core.util.str
import java.io.File

data class Share(
    val id: String,
    val token: String,
    val ownerId: String,
    /** Path absolut terhadap root SSD (bukan relatif home). */
    val storagePath: String,
    val name: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val passwordHash: String?,
    val allowDownload: Boolean,
    val revoked: Boolean = false,
    val accessCount: Long = 0,
) {
    fun isActive(now: Long) = !revoked && (expiresAt == null || now < expiresAt)

    fun toStore(): Map<String, Any?> = linkedMapOf(
        "id" to id, "token" to token, "ownerId" to ownerId, "storagePath" to storagePath, "name" to name,
        "createdAt" to createdAt, "expiresAt" to expiresAt, "passwordHash" to passwordHash,
        "allowDownload" to allowDownload, "revoked" to revoked, "accessCount" to accessCount,
    )

    companion object {
        fun fromStore(m: Map<String, Any?>) = Share(
            m.str("id")!!, m.str("token")!!, m.str("ownerId")!!, m.str("storagePath")!!, m.str("name") ?: "",
            m.long("createdAt") ?: 0, m.long("expiresAt"), m.str("passwordHash"), m.bool("allowDownload") ?: true,
            m.bool("revoked") ?: false, m.long("accessCount") ?: 0,
        )
    }
}

class ShareStore(dataDir: File, private val hasher: PasswordHasher, private val clock: Clock) {
    private val file = AtomicJsonFile(File(dataDir, "shares.json"))
    private val shares = LinkedHashMap<String, Share>()

    init {
        @Suppress("UNCHECKED_CAST")
        (file.read() as? List<Map<String, Any?>>)?.forEach { val s = Share.fromStore(it); shares[s.id] = s }
    }

    @Synchronized
    fun create(ownerId: String, storagePath: String, name: String, expiresInHours: Long?, password: String?, allowDownload: Boolean): Share {
        if (expiresInHours != null && (expiresInHours < 1 || expiresInHours > 24 * 365)) throw ValidationException("Masa berlaku 1 jam – 365 hari")
        if (password != null && password.length < 6) throw ValidationException("Kata sandi tautan minimal 6 karakter")
        val now = clock.now()
        val s = Share(
            id = Tokens.urlToken(9), token = Tokens.urlToken(18), ownerId = ownerId, storagePath = storagePath, name = name,
            createdAt = now, expiresAt = expiresInHours?.let { now + it * 3600_000 },
            passwordHash = password?.let { hasher.hash(it.toCharArray()) }, allowDownload = allowDownload,
        )
        shares[s.id] = s
        save()
        return s
    }

    @Synchronized fun byToken(token: String): Share? = shares.values.firstOrNull { Tokens.constantTimeEquals(it.token, token) }
    @Synchronized fun byId(id: String): Share? = shares[id]
    @Synchronized fun forOwner(ownerId: String?): List<Share> = shares.values.filter { ownerId == null || it.ownerId == ownerId }.sortedByDescending { it.createdAt }

    @Synchronized
    fun revoke(id: String) {
        val s = shares[id] ?: throw ValidationException("Tautan tidak ditemukan")
        shares[id] = s.copy(revoked = true)
        save()
    }

    @Synchronized
    fun countAccess(id: String) {
        val s = shares[id] ?: return
        shares[id] = s.copy(accessCount = s.accessCount + 1)
        save()
    }

    fun checkPassword(s: Share, password: String): Boolean = s.passwordHash == null || hasher.verify(password.toCharArray(), s.passwordHash)

    /** Hapus tautan yang sudah dicabut/kedaluwarsa lebih dari 30 hari. */
    @Synchronized
    fun purge() {
        val now = clock.now()
        if (shares.values.removeAll { !it.isActive(now) && now - (it.expiresAt ?: it.createdAt) > 30L * 24 * 3600_000 }) save()
    }

    private fun save() = file.write(shares.values.map { it.toStore() })
}
