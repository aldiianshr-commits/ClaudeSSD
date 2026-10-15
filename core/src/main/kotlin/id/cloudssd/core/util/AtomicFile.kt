package id.cloudssd.core.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Penyimpanan JSON atomik di penyimpanan internal aplikasi (bukan di SSD),
 * sehingga data akun tetap aman walau SSD dicabut.
 */
class AtomicJsonFile(private val file: File) {
    @Synchronized
    fun read(): Any? {
        if (!file.exists()) {
            val bak = File(file.path + ".bak")
            if (bak.exists()) return Json.parse(bak.readText(StandardCharsets.UTF_8))
            return null
        }
        return Json.parse(file.readText(StandardCharsets.UTF_8))
    }

    @Synchronized
    fun write(value: Any?) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(Json.stringify(value).toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
        if (file.exists()) {
            val bak = File(file.path + ".bak")
            bak.delete()
            file.renameTo(bak)
        }
        if (!tmp.renameTo(file)) throw IOException("Gagal menyimpan ${file.name}")
        File(file.path + ".bak").delete()
    }
}

interface Clock {
    fun now(): Long
    companion object {
        val SYSTEM = object : Clock { override fun now() = System.currentTimeMillis() }
    }
}

class ManualClock(var time: Long = 1_700_000_000_000L) : Clock {
    override fun now() = time
    fun advance(ms: Long) { time += ms }
}
