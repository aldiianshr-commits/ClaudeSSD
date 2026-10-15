package id.cloudssd.core.storage

import java.nio.charset.StandardCharsets

/**
 * Path virtual yang sudah divalidasi, relatif terhadap root penyimpanan.
 * Tidak pernah mengandung "..", ".", segmen kosong, backslash, atau karakter kontrol.
 * Inilah lapisan utama pencegahan path traversal: path mentah dari klien selalu
 * melewati [parse] sebelum menyentuh sistem file.
 */
class VPath private constructor(val segments: List<String>) {

    val isRoot: Boolean get() = segments.isEmpty()
    val name: String get() = segments.lastOrNull() ?: ""
    val parent: VPath? get() = if (isRoot) null else VPath(segments.dropLast(1))

    fun child(name: String): VPath {
        NameRules.validate(name)
        return VPath(segments + name)
    }

    fun resolve(other: VPath): VPath = VPath(segments + other.segments)

    fun startsWith(prefix: VPath): Boolean =
        prefix.segments.size <= segments.size && segments.subList(0, prefix.segments.size) == prefix.segments

    /** Path relatif terhadap [prefix]; prefix harus merupakan awalan. */
    fun relativeTo(prefix: VPath): VPath {
        require(startsWith(prefix)) { "Bukan awalan" }
        return VPath(segments.drop(prefix.segments.size))
    }

    override fun toString(): String = "/" + segments.joinToString("/")
    override fun equals(other: Any?) = other is VPath && other.segments == segments
    override fun hashCode() = segments.hashCode()

    companion object {
        val ROOT = VPath(emptyList())

        /** Parse path dari klien. Melempar [InvalidNameException] jika tidak aman. */
        fun parse(raw: String?): VPath {
            if (raw == null || raw.isEmpty() || raw == "/") return ROOT
            if (raw.length > 4096) throw InvalidNameException("Path terlalu panjang")
            if (raw.contains('\\')) throw InvalidNameException("Path tidak boleh berisi backslash")
            val segs = raw.split('/').filter { it.isNotEmpty() }
            for (s in segs) {
                if (s == "." || s == "..") throw InvalidNameException("Path tidak boleh berisi '.' atau '..'")
                NameRules.validate(s)
            }
            if (segs.size > 64) throw InvalidNameException("Folder terlalu dalam")
            return VPath(segs)
        }

        fun of(vararg segs: String): VPath = parse(segs.joinToString("/"))
    }
}

object NameRules {
    /** Karakter yang tidak didukung exFAT/FAT32/NTFS. SAF akan mengganti diam-diam, jadi ditolak di depan. */
    private const val FORBIDDEN = "\"*/:<>?\\|"
    const val MAX_BYTES = 255

    /** Nama internal yang tidak boleh dipakai pengguna. */
    const val RESERVED_DIR = ".cloudssd"

    fun validate(name: String) {
        if (name.isEmpty()) throw InvalidNameException("Nama tidak boleh kosong")
        if (name == "." || name == "..") throw InvalidNameException("Nama tidak valid")
        if (name.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) throw InvalidNameException("Nama terlalu panjang (maks 255 byte)")
        for (c in name) {
            if (c < ' ' || c == '\u007f') throw InvalidNameException("Nama berisi karakter kontrol")
            if (FORBIDDEN.indexOf(c) >= 0) throw InvalidNameException("Nama tidak boleh berisi karakter $FORBIDDEN")
        }
        if (name != name.trim()) throw InvalidNameException("Nama tidak boleh diawali/diakhiri spasi")
        if (name.endsWith('.')) throw InvalidNameException("Nama tidak boleh diakhiri titik")
    }

    fun isValid(name: String): Boolean = try { validate(name); true } catch (_: InvalidNameException) { false }

    /** "laporan.pdf" -> "laporan (1).pdf", "laporan (2).pdf", ... */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
    }
}

/** Mencari nama unik di folder: tidak pernah menimpa file yang ada. */
fun StorageBackend.uniqueName(dir: VPath, desired: String): String {
    if (stat(dir.child(desired)) == null) return desired
    for (i in 1..9999) {
        val candidate = NameRules.numbered(desired, i)
        if (stat(dir.child(candidate)) == null) return candidate
    }
    throw StorageException("Tidak dapat menemukan nama unik untuk $desired")
}
