package id.cloudssd.core.auth

import id.cloudssd.core.storage.VPath
import id.cloudssd.core.util.AtomicJsonFile
import id.cloudssd.core.util.Clock
import id.cloudssd.core.util.bool
import id.cloudssd.core.util.long
import id.cloudssd.core.util.str
import java.io.File

enum class Role { ADMIN, USER }

data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val role: Role,
    val disabled: Boolean = false,
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    /** Folder "home" relatif terhadap root SSD. Kosong = seluruh SSD (khusus admin). */
    val homeDir: String = "",
    /** 0 = tanpa batas (tetap dibatasi kapasitas SSD sebenarnya). */
    val quotaBytes: Long = 0,
    val createdAt: Long = 0,
    /** Dinaikkan saat kata sandi diganti; sesi lama otomatis tidak berlaku. */
    val credentialVersion: Long = 1,
) {
    val isAdmin get() = role == Role.ADMIN
    val home: VPath get() = VPath.parse(homeDir)

    fun publicJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "username" to username, "role" to role.name.lowercase(), "disabled" to disabled,
        "canRead" to canRead, "canWrite" to canWrite, "homeDir" to "/" + homeDir, "quotaBytes" to quotaBytes,
        "createdAt" to createdAt,
    )

    internal fun toStore(): Map<String, Any?> = linkedMapOf(
        "id" to id, "username" to username, "passwordHash" to passwordHash, "role" to role.name,
        "disabled" to disabled, "canRead" to canRead, "canWrite" to canWrite, "homeDir" to homeDir,
        "quotaBytes" to quotaBytes, "createdAt" to createdAt, "credentialVersion" to credentialVersion,
    )

    companion object {
        internal fun fromStore(m: Map<String, Any?>) = User(
            id = m.str("id")!!, username = m.str("username")!!, passwordHash = m.str("passwordHash")!!,
            role = Role.valueOf(m.str("role") ?: "USER"), disabled = m.bool("disabled") ?: false,
            canRead = m.bool("canRead") ?: true, canWrite = m.bool("canWrite") ?: true,
            homeDir = m.str("homeDir") ?: "", quotaBytes = m.long("quotaBytes") ?: 0,
            createdAt = m.long("createdAt") ?: 0, credentialVersion = m.long("credentialVersion") ?: 1,
        )
    }
}

class ValidationException(message: String) : RuntimeException(message)

class UserStore(dataDir: File, private val hasher: PasswordHasher, private val clock: Clock) {
    private val file = AtomicJsonFile(File(dataDir, "users.json"))
    private val users = LinkedHashMap<String, User>()

    init {
        @Suppress("UNCHECKED_CAST")
        (file.read() as? List<Map<String, Any?>>)?.forEach { val u = User.fromStore(it); users[u.id] = u }
    }

    @Synchronized fun all(): List<User> = users.values.toList()
    @Synchronized fun byId(id: String): User? = users[id]
    @Synchronized fun byUsername(username: String): User? =
        users.values.firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }
    @Synchronized fun hasAdmin(): Boolean = users.values.any { it.isAdmin }

    @Synchronized
    fun createAdmin(username: String, password: String): User {
        if (hasAdmin()) throw ValidationException("Administrator sudah dibuat")
        return create(username, password, Role.ADMIN, homeDir = "")
    }

    @Synchronized
    fun create(
        username: String, password: String, role: Role, homeDir: String,
        canRead: Boolean = true, canWrite: Boolean = true, quotaBytes: Long = 0,
    ): User {
        val name = username.trim().lowercase()
        validateUsername(name)
        validatePassword(password, name)
        if (byUsername(name) != null) throw ValidationException("Nama pengguna sudah dipakai")
        if (quotaBytes < 0) throw ValidationException("Kuota tidak valid")
        val home = VPath.parse(homeDir).segments.joinToString("/")
        val u = User(
            id = Tokens.urlToken(9), username = name, passwordHash = hasher.hash(password.toCharArray()),
            role = role, canRead = canRead, canWrite = canWrite, homeDir = home, quotaBytes = quotaBytes,
            createdAt = clock.now(),
        )
        users[u.id] = u
        save()
        return u
    }

    @Synchronized
    fun update(id: String, transform: (User) -> User): User {
        val old = users[id] ?: throw ValidationException("Pengguna tidak ditemukan")
        val new = transform(old)
        require(new.id == old.id)
        if (old.isAdmin && (new.disabled || !new.isAdmin) && users.values.count { it.isAdmin && !it.disabled } <= 1) {
            throw ValidationException("Administrator terakhir tidak dapat dinonaktifkan")
        }
        users[id] = new
        save()
        return new
    }

    @Synchronized
    fun setPassword(id: String, password: String): User {
        val u = users[id] ?: throw ValidationException("Pengguna tidak ditemukan")
        validatePassword(password, u.username)
        return update(id) { it.copy(passwordHash = hasher.hash(password.toCharArray()), credentialVersion = it.credentialVersion + 1) }
    }

    /** Verifikasi kredensial; selalu memakan waktu serupa walau user tidak ada. */
    fun verify(username: String, password: String): User? {
        val u = byUsername(username)
        if (u == null) {
            hasher.dummyVerify(password.toCharArray())
            return null
        }
        return if (hasher.verify(password.toCharArray(), u.passwordHash)) u else null
    }

    private fun save() = file.write(users.values.map { it.toStore() })

    companion object {
        private val USERNAME = Regex("^[a-z0-9][a-z0-9_.-]{2,31}$")
        const val MIN_PASSWORD = 10

        fun validateUsername(name: String) {
            if (!USERNAME.matches(name)) throw ValidationException("Nama pengguna 3–32 karakter: huruf kecil, angka, titik, garis bawah, atau tanda hubung")
        }

        fun validatePassword(pw: String, username: String) {
            if (pw.length < MIN_PASSWORD) throw ValidationException("Kata sandi minimal $MIN_PASSWORD karakter")
            if (pw.length > 256) throw ValidationException("Kata sandi terlalu panjang")
            if (pw.equals(username, ignoreCase = true)) throw ValidationException("Kata sandi tidak boleh sama dengan nama pengguna")
            if (pw.toSet().size < 4) throw ValidationException("Kata sandi terlalu mudah ditebak")
        }
    }
}
