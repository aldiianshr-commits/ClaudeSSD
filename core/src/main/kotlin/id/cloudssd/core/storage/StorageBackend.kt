package id.cloudssd.core.storage

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Abstraksi penyimpanan. Implementasi:
 *  - [LocalFileBackend]  : java.io/nio (dipakai pengujian JVM & mode pengembangan PC)
 *  - SafStorageBackend   : Android Storage Access Framework (modul app)
 *
 * Semua path adalah [VPath] relatif terhadap root penyimpanan yang dipilih pengguna.
 * Semua operasi melempar [StorageUnavailableException] jika SSD tidak tersedia,
 * dan [StorageException] untuk kegagalan lain. Tidak ada operasi yang boleh "pura-pura berhasil".
 */
interface StorageBackend {
    /** Deskripsi singkat lokasi (untuk UI). */
    val description: String

    /** Cepat: apakah root penyimpanan dapat diakses saat ini. */
    fun isAvailable(): Boolean

    fun requireAvailable() {
        if (!isAvailable()) throw StorageUnavailableException()
    }

    /** null jika tidak ada. */
    fun stat(path: VPath): FileEntry?

    /** Isi folder. Melempar NotFound jika folder tidak ada. */
    fun list(dir: VPath): List<FileEntry>

    /** Membuka file untuk dibaca mulai [offset]. */
    fun openRead(path: VPath, offset: Long = 0): InputStream

    /** Membuat file kosong baru. Gagal (AlreadyExists) jika nama sudah ada. */
    fun createFile(dir: VPath, name: String): FileEntry

    /**
     * Membuka file yang sudah ada untuk ditulis: file dipotong (truncate) ke [offset]
     * lalu penulisan dimulai dari posisi itu. close() wajib menyinkronkan data ke disk (fsync).
     */
    fun openWriteAt(path: VPath, offset: Long): OutputStream

    fun mkdir(dir: VPath, name: String): FileEntry

    /** Ganti nama di folder yang sama. Gagal jika nama tujuan sudah ada. */
    fun rename(path: VPath, newName: String): FileEntry

    /** Pindah ke folder lain dengan nama tetap. Gagal jika nama sudah ada di tujuan. */
    fun move(path: VPath, newParent: VPath): FileEntry

    /** Hapus file atau folder (rekursif). */
    fun delete(path: VPath)

    /** Kapasitas aktual volume, null jika sistem tidak menyediakan informasinya. */
    fun capacity(): Capacity?
}

data class FileEntry(
    val name: String,
    val path: VPath,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

data class Capacity(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

open class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)
class StorageUnavailableException(message: String = "Penyimpanan (SSD) tidak tersedia atau terputus", cause: Throwable? = null) :
    StorageException(message, cause)
class NotFoundException(message: String = "File atau folder tidak ditemukan") : StorageException(message)
class AlreadyExistsException(val existingName: String) : StorageException("Nama \"$existingName\" sudah ada")
class InvalidNameException(message: String) : StorageException(message)
class NoSpaceException(message: String = "Ruang penyimpanan penuh", cause: Throwable? = null) : StorageException(message, cause)

/** Mendeteksi IOException yang berasal dari disk penuh (ENOSPC). */
fun Throwable.isNoSpace(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is NoSpaceException) return true
        val m = t.message?.lowercase() ?: ""
        if ("no space left" in m || "enospc" in m || "disk full" in m || "not enough space" in m) return true
        t = t.cause
    }
    return false
}
