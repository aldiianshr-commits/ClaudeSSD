package id.cloudssd.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Backend berbasis path sistem file biasa. Dipakai untuk pengujian, mode PC,
 * dan dapat dipakai di Android bila path dapat diakses langsung.
 *
 * Perlindungan tambahan: path hasil resolve harus tetap berada di dalam root
 * (mencegah lolos lewat symlink), dan symlink tidak diikuti.
 */
open class LocalFileBackend(rootDir: File) : StorageBackend {
    private val root: Path = rootDir.toPath().toAbsolutePath().normalize()

    override val description: String get() = root.toString()

    override fun isAvailable(): Boolean = Files.isDirectory(root) && Files.isWritable(root)

    protected fun resolve(path: VPath): Path {
        var p = root
        for (s in path.segments) p = p.resolve(s)
        val n = p.normalize()
        if (!n.startsWith(root)) throw InvalidNameException("Akses di luar direktori penyimpanan ditolak")
        // Tolak symlink di sepanjang path.
        var cur = root
        for (s in path.segments) {
            cur = cur.resolve(s)
            if (Files.isSymbolicLink(cur)) throw InvalidNameException("Symlink tidak diizinkan")
        }
        return n
    }

    private fun entry(path: VPath, p: Path): FileEntry {
        val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return FileEntry(path.name, path, a.isDirectory, if (a.isDirectory) 0 else a.size(), a.lastModifiedTime().toMillis())
    }

    override fun stat(path: VPath): FileEntry? {
        requireAvailable()
        val p = resolve(path)
        return try { entry(path, p) } catch (_: NoSuchFileException) { null } catch (e: IOException) { throw wrap(e) }
    }

    override fun list(dir: VPath): List<FileEntry> {
        requireAvailable()
        val p = resolve(dir)
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw NotFoundException("Folder tidak ditemukan")
        val result = ArrayList<FileEntry>()
        try {
            Files.newDirectoryStream(p).use { ds ->
                for (child in ds) {
                    val name = child.fileName.toString()
                    if (!NameRules.isValid(name) || Files.isSymbolicLink(child)) continue
                    try { result.add(entry(dir.child(name), child)) } catch (_: IOException) {}
                }
            }
        } catch (e: IOException) { throw wrap(e) }
        return result
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        requireAvailable()
        val p = resolve(path)
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw NotFoundException()
        return try {
            val raf = RandomAccessFile(p.toFile(), "r")
            if (offset > 0) raf.seek(offset)
            Channels.newInputStream(raf.channel)
        } catch (e: IOException) { throw wrap(e) }
    }

    override fun createFile(dir: VPath, name: String): FileEntry {
        requireAvailable()
        val target = dir.child(name)
        val p = resolve(target)
        if (!Files.isDirectory(resolve(dir))) throw NotFoundException("Folder tujuan tidak ditemukan")
        try { Files.createFile(p) } catch (_: FileAlreadyExistsException) { throw AlreadyExistsException(name) } catch (e: IOException) { throw wrap(e) }
        return entry(target, p)
    }

    override fun openWriteAt(path: VPath, offset: Long): OutputStream {
        requireAvailable()
        val p = resolve(path)
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw NotFoundException()
        return try {
            val raf = RandomAccessFile(p.toFile(), "rw")
            raf.setLength(offset)
            raf.seek(offset)
            val fos = FileOutputStream(raf.fd)
            object : OutputStream() {
                override fun write(b: Int) = guard { fos.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) = guard { fos.write(b, off, len) }
                override fun flush() = guard { fos.flush() }
                override fun close() {
                    try {
                        guard { raf.fd.sync() }
                    } finally {
                        try { raf.close() } catch (_: IOException) {}
                    }
                }
                private inline fun guard(block: () -> Unit) {
                    try { block() } catch (e: IOException) { throw wrap(e) }
                }
            }
        } catch (e: IOException) { throw wrap(e) }
    }

    override fun mkdir(dir: VPath, name: String): FileEntry {
        requireAvailable()
        val target = dir.child(name)
        val p = resolve(target)
        if (!Files.isDirectory(resolve(dir))) throw NotFoundException("Folder induk tidak ditemukan")
        try { Files.createDirectory(p) } catch (_: FileAlreadyExistsException) { throw AlreadyExistsException(name) } catch (e: IOException) { throw wrap(e) }
        return entry(target, p)
    }

    override fun rename(path: VPath, newName: String): FileEntry {
        requireAvailable()
        val parent = path.parent ?: throw InvalidNameException("Root tidak dapat diganti nama")
        val target = parent.child(newName)
        val src = resolve(path)
        val dst = resolve(target)
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) throw NotFoundException()
        // Perbandingan tanpa membedakan huruf: exFAT tidak case-sensitive.
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS) && !path.name.equals(newName, ignoreCase = true)) throw AlreadyExistsException(newName)
        try { Files.move(src, dst) } catch (_: FileAlreadyExistsException) { throw AlreadyExistsException(newName) } catch (e: IOException) { throw wrap(e) }
        return entry(target, dst)
    }

    override fun move(path: VPath, newParent: VPath): FileEntry {
        requireAvailable()
        val target = newParent.child(path.name)
        val src = resolve(path)
        val dst = resolve(target)
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) throw NotFoundException()
        if (!Files.isDirectory(resolve(newParent))) throw NotFoundException("Folder tujuan tidak ditemukan")
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw AlreadyExistsException(path.name)
        try { Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE) } catch (_: FileAlreadyExistsException) { throw AlreadyExistsException(path.name) } catch (e: IOException) {
            try { Files.move(src, dst) } catch (e2: IOException) { throw wrap(e2) }
        }
        return entry(target, dst)
    }

    override fun delete(path: VPath) {
        requireAvailable()
        if (path.isRoot) throw InvalidNameException("Root tidak dapat dihapus")
        val p = resolve(path)
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw NotFoundException()
        try { deleteRecursive(p.toFile()) } catch (e: IOException) { throw wrap(e) }
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
            f.listFiles()?.forEach { deleteRecursive(it) }
        }
        if (!f.delete() && f.exists()) throw IOException("Gagal menghapus ${f.name}")
    }

    override fun capacity(): Capacity? {
        if (!isAvailable()) return null
        val f = root.toFile()
        val total = f.totalSpace
        if (total <= 0) return null
        return Capacity(total, f.usableSpace)
    }

    protected fun wrap(e: IOException): IOException = when {
        e is StorageException -> e
        e.isNoSpace() -> NoSpaceException(cause = e)
        !isAvailable() -> StorageUnavailableException(cause = e)
        else -> StorageException(e.message ?: "Kesalahan I/O", e)
    }
}
