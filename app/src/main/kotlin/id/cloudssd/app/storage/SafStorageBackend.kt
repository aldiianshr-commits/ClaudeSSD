package id.cloudssd.app.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import id.cloudssd.core.storage.AlreadyExistsException
import id.cloudssd.core.storage.Capacity
import id.cloudssd.core.storage.FileEntry
import id.cloudssd.core.storage.InvalidNameException
import id.cloudssd.core.storage.NameRules
import id.cloudssd.core.storage.NoSpaceException
import id.cloudssd.core.storage.NotFoundException
import id.cloudssd.core.storage.StorageBackend
import id.cloudssd.core.storage.StorageException
import id.cloudssd.core.storage.StorageUnavailableException
import id.cloudssd.core.storage.VPath
import id.cloudssd.core.storage.isNoSpace
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Backend penyimpanan berbasis Storage Access Framework (izin resmi Android, tanpa root).
 *
 * Untuk penyedia bawaan Android (com.android.externalstorage.documents) — yang menangani
 * flashdisk/SSD USB-C — ID dokumen berbentuk "UUID:path/relatif", sehingga URI file dapat
 * dibangun langsung dari path tanpa menelusuri folder satu per satu.
 * Untuk penyedia lain dipakai penelusuran nama per segmen (lebih lambat).
 */
class SafStorageBackend(private val context: Context, val treeUri: Uri) : StorageBackend {
    private val resolver: ContentResolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val pathMode: Boolean = treeUri.authority == EXTERNAL_STORAGE_AUTHORITY && rootDocId.contains(':')

    @Volatile private var availCache: Pair<Long, Boolean>? = null
    @Volatile private var capCache: Pair<Long, Capacity?>? = null

    override val description: String
        get() = VolumeInfo.describe(context, treeUri)

    // ------------------------------------------------------------- ketersediaan

    override fun isAvailable(): Boolean {
        val now = SystemClock.elapsedRealtime()
        availCache?.let { if (now - it.first < 1500) return it.second }
        val ok = checkAvailableNow()
        availCache = now to ok
        return ok
    }

    fun invalidateCache() { availCache = null; capCache = null }

    private fun checkAvailableNow(): Boolean {
        if (resolver.persistedUriPermissions.none { it.uri == treeUri && it.isReadPermission && it.isWritePermission }) return false
        return try {
            query(docUri(rootDocId), arrayOf(Document.COLUMN_MIME_TYPE))?.use { c ->
                c.moveToFirst() && c.getString(0) == Document.MIME_TYPE_DIR
            } ?: false
        } catch (_: Exception) { false }
    }

    // ------------------------------------------------------------- ID & URI

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun childDocId(parentDocId: String, name: String): String =
        if (parentDocId.endsWith(":")) parentDocId + name else "$parentDocId/$name"

    /** ID dokumen untuk path, atau null bila tidak ada (mode penelusuran). */
    private fun docIdFor(path: VPath): String? {
        if (pathMode) {
            var id = rootDocId
            for (s in path.segments) id = childDocId(id, s)
            return id
        }
        var id = rootDocId
        for (s in path.segments) id = findChild(id, s) ?: return null
        return id
    }

    private fun findChild(parentDocId: String, name: String): String? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME))?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun query(uri: Uri, projection: Array<String>): Cursor? = resolver.query(uri, projection, null, null, null)

    private val STAT_COLS = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    )

    private fun entryFrom(c: Cursor, path: VPath): FileEntry {
        val isDir = c.getString(2) == Document.MIME_TYPE_DIR
        val size = if (isDir || c.isNull(3)) 0 else c.getLong(3)
        val mod = if (c.isNull(4)) 0 else c.getLong(4)
        return FileEntry(path.name, path, isDir, size, mod)
    }

    // ------------------------------------------------------------- operasi

    override fun stat(path: VPath): FileEntry? = guard {
        requireAvailable()
        val id = docIdFor(path) ?: return@guard null
        statById(id, path)
    }

    private fun statById(id: String, path: VPath): FileEntry? = try {
        query(docUri(id), STAT_COLS)?.use { c -> if (c.moveToFirst()) entryFrom(c, path) else null }
    } catch (_: FileNotFoundException) { null } catch (_: IllegalArgumentException) { null }

    override fun list(dir: VPath): List<FileEntry> = guard {
        requireAvailable()
        val id = docIdFor(dir) ?: throw NotFoundException("Folder tidak ditemukan")
        val st = statById(id, dir) ?: throw NotFoundException("Folder tidak ditemukan")
        if (!st.isDirectory) throw NotFoundException("Bukan folder")
        val out = ArrayList<FileEntry>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
        val c = query(uri, STAT_COLS) ?: throw StorageUnavailableException()
        c.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                if (!NameRules.isValid(name)) continue // nama yang tidak bisa direpresentasikan dengan aman
                if (pathMode && it.getString(0) != childDocId(id, name)) continue
                out.add(entryFrom(it, dir.child(name)))
            }
        }
        out
    }

    override fun openRead(path: VPath, offset: Long): InputStream = guard {
        requireAvailable()
        val e = stat(path) ?: throw NotFoundException()
        if (e.isDirectory) throw NotFoundException("Bukan file")
        val pfd = resolver.openFileDescriptor(docUri(docIdFor(path)!!), "r") ?: throw NotFoundException()
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) input.channel.position(offset)
        GuardedInputStream(input)
    }

    override fun createFile(dir: VPath, name: String): FileEntry = guard {
        requireAvailable()
        NameRules.validate(name)
        val parentId = docIdFor(dir) ?: throw NotFoundException("Folder tujuan tidak ditemukan")
        if (stat(dir.child(name)) != null) throw AlreadyExistsException(name)
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), "application/octet-stream", name)
            ?: throw StorageException("Gagal membuat file")
        verifyName(uri, name)
        stat(dir.child(name)) ?: throw StorageException("File dibuat tetapi tidak dapat ditemukan")
    }

    override fun openWriteAt(path: VPath, offset: Long): OutputStream = guard {
        requireAvailable()
        val id = docIdFor(path) ?: throw NotFoundException()
        val pfd = resolver.openFileDescriptor(docUri(id), "rw") ?: throw NotFoundException()
        val fos = FileOutputStream(pfd.fileDescriptor)
        try {
            fos.channel.truncate(offset)
            fos.channel.position(offset)
        } catch (e: IOException) {
            pfd.close(); throw e
        }
        GuardedOutputStream(fos, pfd)
    }

    override fun mkdir(dir: VPath, name: String): FileEntry = guard {
        requireAvailable()
        NameRules.validate(name)
        val parentId = docIdFor(dir) ?: throw NotFoundException("Folder induk tidak ditemukan")
        if (stat(dir.child(name)) != null) throw AlreadyExistsException(name)
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), Document.MIME_TYPE_DIR, name)
            ?: throw StorageException("Gagal membuat folder")
        verifyName(uri, name)
        stat(dir.child(name)) ?: throw StorageException("Folder dibuat tetapi tidak dapat ditemukan")
    }

    override fun rename(path: VPath, newName: String): FileEntry = guard {
        requireAvailable()
        NameRules.validate(newName)
        val parent = path.parent ?: throw InvalidNameException("Root tidak dapat diganti nama")
        val id = docIdFor(path) ?: throw NotFoundException()
        statById(id, path) ?: throw NotFoundException()
        if (!path.name.equals(newName, ignoreCase = true) && stat(parent.child(newName)) != null) throw AlreadyExistsException(newName)
        val newUri = DocumentsContract.renameDocument(resolver, docUri(id), newName) ?: docUri(id)
        verifyName(newUri, newName)
        stat(parent.child(newName)) ?: throw StorageException("Ganti nama gagal diverifikasi")
    }

    override fun move(path: VPath, newParent: VPath): FileEntry = guard {
        requireAvailable()
        val parent = path.parent ?: throw InvalidNameException("Root tidak dapat dipindah")
        val srcId = docIdFor(path) ?: throw NotFoundException()
        val srcParentId = docIdFor(parent) ?: throw NotFoundException()
        val dstId = docIdFor(newParent) ?: throw NotFoundException("Folder tujuan tidak ditemukan")
        if (stat(newParent.child(path.name)) != null) throw AlreadyExistsException(path.name)
        DocumentsContract.moveDocument(resolver, docUri(srcId), docUri(srcParentId), docUri(dstId))
            ?: throw StorageException("Pemindahan tidak didukung oleh penyedia penyimpanan ini")
        stat(newParent.child(path.name)) ?: throw StorageException("Pemindahan gagal diverifikasi")
    }

    override fun delete(path: VPath) {
        guard {
            requireAvailable()
            if (path.isRoot) throw InvalidNameException("Root tidak dapat dihapus")
            val id = docIdFor(path) ?: throw NotFoundException()
            statById(id, path) ?: throw NotFoundException()
            if (!DocumentsContract.deleteDocument(resolver, docUri(id))) throw StorageException("Gagal menghapus ${path.name}")
            if (statById(id, path) != null) throw StorageException("File masih ada setelah dihapus")
        }
    }

    /**
     * Kapasitas aktual: fstatvfs pada file penanda di SSD. Bila gagal, dicoba lewat
     * StorageManager (volume yang cocok). Mengembalikan null jika tidak tersedia —
     * aplikasi tidak pernah mengarang angka 1 TB.
     */
    override fun capacity(): Capacity? {
        val now = SystemClock.elapsedRealtime()
        capCache?.let { if (now - it.first < 5000) return it.second }
        var cap: Capacity? = null
        if (isAvailable()) {
            cap = try { capacityViaFd() } catch (_: Exception) { null }
            if (cap == null) cap = VolumeInfo.capacityFromVolume(context, treeUri)
        }
        capCache = now to cap
        return cap
    }

    private fun capacityViaFd(): Capacity? {
        val internal = VPath.of(NameRules.RESERVED_DIR)
        if (stat(internal) == null) try { mkdir(VPath.ROOT, NameRules.RESERVED_DIR) } catch (_: AlreadyExistsException) {}
        val marker = internal.child("volume.txt")
        if (stat(marker) == null) {
            try { createFile(internal, "volume.txt") } catch (_: AlreadyExistsException) {}
            openWriteAt(marker, 0).use { it.write("CloudSSD — jangan dihapus.\n".toByteArray()) }
        }
        val pfd = resolver.openFileDescriptor(docUri(docIdFor(marker)!!), "r") ?: return null
        pfd.use {
            val st = Os.fstatvfs(it.fileDescriptor)
            val total = st.f_blocks * st.f_frsize
            val free = st.f_bavail * st.f_frsize
            return if (total > 0) Capacity(total, free) else null
        }
    }

    // ------------------------------------------------------------- penanganan error

    private fun verifyName(uri: Uri, expected: String) {
        val actual = query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME))?.use { if (it.moveToFirst()) it.getString(0) else null }
        if (actual != expected) {
            // Penyedia mengubah nama diam-diam (mis. menambah ekstensi): batalkan agar tidak menyesatkan.
            try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) {}
            throw StorageException("Penyimpanan mengubah nama menjadi \"$actual\"; operasi dibatalkan")
        }
    }

    private inline fun <T> guard(block: () -> T): T {
        try {
            return block()
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw translate(e)
        }
    }

    internal fun translate(e: Exception): IOException {
        invalidateCache()
        if (e is StorageException) return e
        if (e.isNoSpace()) return NoSpaceException(cause = e)
        val msg = (e.message ?: "").lowercase()
        if ("efbig" in msg || "file too large" in msg) {
            return StorageException("File melebihi batas ukuran sistem file SSD (FAT32 maksimal 4 GB per file). Format SSD sebagai exFAT.", e)
        }
        if (!checkAvailableNow()) return StorageUnavailableException(cause = e)
        if (e is FileNotFoundException) return NotFoundException()
        return StorageException(e.message ?: e.javaClass.simpleName, e)
    }

    private inner class GuardedInputStream(private val inner: InputStream) : InputStream() {
        override fun read(): Int = try { inner.read() } catch (e: IOException) { throw translate(e) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = try { inner.read(b, off, len) } catch (e: IOException) { throw translate(e) }
        override fun skip(n: Long): Long = inner.skip(n)
        override fun available(): Int = inner.available()
        override fun close() = inner.close()
    }

    private inner class GuardedOutputStream(private val fos: FileOutputStream, private val pfd: ParcelFileDescriptor) : OutputStream() {
        override fun write(b: Int) = try { fos.write(b) } catch (e: IOException) { throw translate(e) }
        override fun write(b: ByteArray, off: Int, len: Int) = try { fos.write(b, off, len) } catch (e: IOException) { throw translate(e) }
        override fun flush() = try { fos.flush() } catch (e: IOException) { throw translate(e) }
        override fun close() {
            try {
                fos.flush()
                fos.fd.sync() // pastikan data benar-benar tertulis ke SSD sebelum melapor sukses
            } catch (e: IOException) {
                throw translate(e)
            } finally {
                try { pfd.close() } catch (_: IOException) {}
            }
        }
    }

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
