package id.cloudssd.app.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import id.cloudssd.core.storage.Capacity

/** Informasi volume (SSD/flashdisk/internal) untuk lokasi SAF yang dipilih. */
object VolumeInfo {

    data class Info(
        val label: String,
        val isPrimaryInternal: Boolean,
        val isRemovable: Boolean,
        val mounted: Boolean?,
        val relativePath: String,
    )

    /** "UUID" dari ID dokumen tree, mis. "1A2B-3C4D:CloudSSD" -> "1A2B-3C4D", "primary:..." -> "primary". */
    fun volumeId(treeUri: Uri): String? = try {
        if (treeUri.authority != SafStorageBackend.EXTERNAL_STORAGE_AUTHORITY) null
        else DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
    } catch (_: Exception) { null }

    private fun findVolume(context: Context, treeUri: Uri): StorageVolume? {
        val id = volumeId(treeUri) ?: return null
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        return sm.storageVolumes.firstOrNull { v ->
            if (id == "primary") v.isPrimary else v.uuid.equals(id, ignoreCase = true)
        }
    }

    fun info(context: Context, treeUri: Uri): Info {
        val id = volumeId(treeUri)
        val rel = try { DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':', "") } catch (_: Exception) { "" }
        val v = findVolume(context, treeUri)
        val label = v?.getDescription(context) ?: when {
            id == null -> treeUri.authority ?: "Penyimpanan"
            id == "primary" -> "Penyimpanan internal"
            else -> "Penyimpanan USB ($id)"
        }
        return Info(
            label = label,
            isPrimaryInternal = id == "primary" || v?.isPrimary == true,
            isRemovable = v?.isRemovable ?: (id != null && id != "primary"),
            mounted = v?.state?.let { it == Environment.MEDIA_MOUNTED },
            relativePath = rel,
        )
    }

    fun describe(context: Context, treeUri: Uri): String {
        val i = info(context, treeUri)
        return i.label + if (i.relativePath.isNotEmpty()) " › ${i.relativePath}" else " (seluruh volume)"
    }

    /** Cadangan bila fstatvfs gagal: StatFs pada direktori volume (Android 11+). */
    fun capacityFromVolume(context: Context, treeUri: Uri): Capacity? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val dir = findVolume(context, treeUri)?.directory ?: return null
            val st = StatFs(dir.path)
            if (st.totalBytes > 0) Capacity(st.totalBytes, st.availableBytes) else null
        } catch (_: Exception) { null }
    }
}
