package id.cloudssd.core.server

import id.cloudssd.core.auth.Tokens
import id.cloudssd.core.auth.ValidationException
import id.cloudssd.core.storage.AlreadyExistsException
import id.cloudssd.core.storage.FileEntry
import id.cloudssd.core.storage.NameRules
import id.cloudssd.core.storage.NoSpaceException
import id.cloudssd.core.storage.NotFoundException
import id.cloudssd.core.storage.StorageBackend
import id.cloudssd.core.storage.StorageException
import id.cloudssd.core.storage.VPath
import id.cloudssd.core.storage.isNoSpace
import id.cloudssd.core.storage.uniqueName
import id.cloudssd.core.util.AtomicJsonFile
import id.cloudssd.core.util.Clock
import id.cloudssd.core.util.long
import id.cloudssd.core.util.str
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

enum class ConflictPolicy { ASK, RENAME, OVERWRITE;
    companion object { fun parse(s: String?) = when (s?.lowercase()) { "rename" -> RENAME; "overwrite" -> OVERWRITE; else -> ASK } }
}

data class Upload(
    val id: String,
    val userId: String,
    val dir: String,          // path folder tujuan relatif root SSD
    val name: String,
    val size: Long,
    var committed: Long,
    val conflict: ConflictPolicy,
    val createdAt: Long,
    var updatedAt: Long,
) {
    val partName get() = PART_PREFIX + id + ".part"
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "userId" to userId, "dir" to dir, "name" to name, "size" to size, "committed" to committed,
        "conflict" to conflict.name, "createdAt" to createdAt, "updatedAt" to updatedAt,
    )
    companion object {
        const val PART_PREFIX = ".cssd-upload-"
        fun fromJson(m: Map<String, Any?>) = Upload(
            m.str("id")!!, m.str("userId")!!, m.str("dir") ?: "", m.str("name")!!, m.long("size") ?: 0,
            m.long("committed") ?: 0, ConflictPolicy.valueOf(m.str("conflict") ?: "ASK"),
            m.long("createdAt") ?: 0, m.long("updatedAt") ?: 0,
        )
    }
}

class UploadConflictException(val existingName: String, val suggestedName: String) :
    StorageException("File \"$existingName\" sudah ada")

class OffsetMismatchException(val expected: Long) : StorageException("Offset tidak cocok, server mengharapkan $expected")

/**
 * Unggahan bertahap yang dapat dilanjutkan.
 *
 * Alur: init -> PUT potongan (offset berurutan) -> complete.
 * Data ditulis ke file sementara di SSD (.cloudssd/uploads) lalu dipindahkan saat selesai,
 * sehingga file tujuan tidak pernah muncul setengah jadi. Offset hanya dimajukan setelah
 * potongan selesai ditulis dan disinkronkan (fsync); potongan yang terputus akan diulang.
 */
class UploadManager(
    dataDir: File,
    private val backend: () -> StorageBackend,
    private val clock: Clock,
    private val reserveBytes: Long = 32L * 1024 * 1024,
) {
    private val file = AtomicJsonFile(File(dataDir, "uploads.json"))
    private val uploads = LinkedHashMap<String, Upload>()
    private val locks = HashMap<String, Any>()

    init {
        try {
            @Suppress("UNCHECKED_CAST")
            (file.read() as? List<Map<String, Any?>>)?.forEach { val u = Upload.fromJson(it); uploads[u.id] = u }
        } catch (_: Exception) {}
    }

    @Synchronized fun get(id: String): Upload? = uploads[id]?.copy()
    @Synchronized fun forUser(userId: String): List<Upload> = uploads.values.filter { it.userId == userId }.map { it.copy() }

    @Synchronized
    private fun lockFor(id: String): Any = locks.getOrPut(id) { Any() }

    fun init(userId: String, dir: VPath, name: String, size: Long, conflict: ConflictPolicy): Upload {
        NameRules.validate(name)
        if (name.startsWith(Upload.PART_PREFIX)) throw ValidationException("Nama file tidak diizinkan")
        if (size < 0) throw ValidationException("Ukuran tidak valid")
        val b = backend()
        b.requireAvailable()
        val d = b.stat(dir) ?: throw NotFoundException("Folder tujuan tidak ditemukan")
        if (!d.isDirectory) throw NotFoundException("Tujuan bukan folder")
        if (conflict == ConflictPolicy.ASK && b.stat(dir.child(name)) != null) {
            throw UploadConflictException(name, b.uniqueName(dir, name))
        }
        checkSpace(b, size)
        ensureUploadDir(b)
        val now = clock.now()
        val u = Upload(Tokens.urlToken(12), userId, dir.segments.joinToString("/"), name, size, 0, conflict, now, now)
        b.createFile(UPLOAD_DIR, u.partName)
        synchronized(this) {
            uploads[u.id] = u
            save()
        }
        return u.copy()
    }

    /** Menulis satu potongan. @return offset baru yang sudah tersimpan aman. */
    fun write(id: String, offset: Long, input: InputStream, length: Long): Long {
        synchronized(lockFor(id)) {
            val u = synchronized(this) { uploads[id] } ?: throw NotFoundException("Sesi unggahan tidak ditemukan")
            if (offset != u.committed) throw OffsetMismatchException(u.committed)
            if (length < 0 || offset + length > u.size) throw ValidationException("Potongan melebihi ukuran file")
            val b = backend()
            b.requireAvailable()
            // Cek ruang untuk sisa potongan ini.
            b.capacity()?.let { if (it.freeBytes < length + reserveBytes) throw NoSpaceException() }
            val part = UPLOAD_DIR.child(u.partName)
            // openWriteAt memotong file ke offset tersimpan terakhir: sisa potongan yang gagal sebelumnya dibuang.
            val out = b.openWriteAt(part, u.committed)
            var written = 0L
            try {
                val buf = ByteArray(256 * 1024)
                while (written < length) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - written).toInt())
                    if (n == -1) throw EOFException("Koneksi terputus saat mengunggah")
                    out.write(buf, 0, n)
                    written += n
                }
                out.close() // fsync
            } catch (e: IOException) {
                try { out.close() } catch (_: IOException) {}
                if (e.isNoSpace()) throw NoSpaceException(cause = e)
                throw e
            }
            synchronized(this) {
                u.committed += written
                u.updatedAt = clock.now()
                save()
            }
            return u.committed
        }
    }

    fun complete(id: String): FileEntry {
        synchronized(lockFor(id)) {
            val u = synchronized(this) { uploads[id] } ?: throw NotFoundException("Sesi unggahan tidak ditemukan")
            if (u.committed != u.size) throw ValidationException("Unggahan belum lengkap (${u.committed}/${u.size} byte)")
            val b = backend()
            b.requireAvailable()
            val part = UPLOAD_DIR.child(u.partName)
            val st = b.stat(part) ?: throw StorageException("File sementara hilang; unggah ulang diperlukan")
            if (st.size != u.size) throw StorageException("Ukuran file sementara tidak sesuai (${st.size} ≠ ${u.size}); unggah ulang diperlukan")
            val dir = VPath.parse(u.dir)
            val exists = b.stat(dir.child(u.name)) != null
            if (exists && u.conflict == ConflictPolicy.ASK) throw UploadConflictException(u.name, b.uniqueName(dir, u.name))
            // Pindahkan file sementara ke folder tujuan, lalu beri nama akhir.
            val moved = b.move(part, dir)
            val result = try {
                when {
                    !exists -> try { b.rename(moved.path, u.name) } catch (_: AlreadyExistsException) { b.rename(moved.path, b.uniqueName(dir, u.name)) }
                    u.conflict == ConflictPolicy.OVERWRITE -> {
                        b.delete(dir.child(u.name))
                        b.rename(moved.path, u.name)
                    }
                    else -> b.rename(moved.path, b.uniqueName(dir, u.name))
                }
            } catch (e: IOException) {
                // Kembalikan ke folder sementara agar dapat dicoba lagi.
                try { b.move(moved.path, UPLOAD_DIR) } catch (_: IOException) {}
                throw e
            }
            synchronized(this) {
                uploads.remove(id)
                locks.remove(id)
                save()
            }
            return result
        }
    }

    fun cancel(id: String) {
        synchronized(lockFor(id)) {
            val u = synchronized(this) { uploads.remove(id) } ?: return
            synchronized(this) { save() }
            try {
                val b = backend()
                if (b.isAvailable()) b.stat(UPLOAD_DIR.child(u.partName))?.let { b.delete(it.path) }
            } catch (_: IOException) {}
        }
    }

    /** Total byte yang masih akan ditulis oleh unggahan yang sedang berjalan. */
    @Synchronized fun pendingBytes(): Long = uploads.values.sumOf { it.size - it.committed }

    /** Bytes yang masih akan ditulis per user (untuk kuota). */
    @Synchronized fun pendingBytesFor(userId: String): Long = uploads.values.filter { it.userId == userId }.sumOf { it.size - it.committed }

    /** Hapus sesi unggahan yang tidak disentuh lebih dari [maxAgeMs]. */
    fun cleanupStale(maxAgeMs: Long = 7L * 24 * 3600_000) {
        val now = clock.now()
        val stale = synchronized(this) { uploads.values.filter { now - it.updatedAt > maxAgeMs }.map { it.id } }
        stale.forEach { cancel(it) }
    }

    private fun checkSpace(b: StorageBackend, size: Long) {
        val cap = b.capacity() ?: return
        val needed = size + pendingBytes() + reserveBytes
        if (cap.freeBytes < needed) throw NoSpaceException("Ruang kosong SSD tidak cukup: perlu ${size} byte, tersedia ${cap.freeBytes - reserveBytes - pendingBytes()} byte")
    }

    private fun ensureUploadDir(b: StorageBackend) {
        if (b.stat(INTERNAL_DIR) == null) try { b.mkdir(VPath.ROOT, NameRules.RESERVED_DIR) } catch (_: AlreadyExistsException) {}
        if (b.stat(UPLOAD_DIR) == null) try { b.mkdir(INTERNAL_DIR, "uploads") } catch (_: AlreadyExistsException) {}
    }

    @Synchronized private fun save() = file.write(uploads.values.map { it.toJson() })

    companion object {
        val INTERNAL_DIR: VPath = VPath.of(NameRules.RESERVED_DIR)
        val UPLOAD_DIR: VPath = INTERNAL_DIR.child("uploads")
    }
}
