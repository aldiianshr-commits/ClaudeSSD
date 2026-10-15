package id.cloudssd.core.api

import id.cloudssd.core.storage.NameRules

object Mime {
    private val map = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "avif" to "image/avif", "heic" to "image/heic", "svg" to "image/svg+xml",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "wav" to "audio/wav", "flac" to "audio/flac", "opus" to "audio/opus",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/plain", "log" to "text/plain", "csv" to "text/plain",
        "json" to "text/plain", "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript",
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "woff2" to "font/woff2", "ico" to "image/x-icon",
    )

    fun of(name: String): String = map[NameRules.extension(name)] ?: "application/octet-stream"

    /**
     * Tipe yang aman ditampilkan inline. HTML/SVG/JS sengaja tidak diizinkan inline
     * karena dapat menjalankan skrip pada origin CloudSSD (XSS).
     */
    fun isSafeInline(mime: String): Boolean =
        (mime.startsWith("image/") && mime != "image/svg+xml") || mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime == "application/pdf" || mime == "text/plain"

    fun kind(name: String, isDir: Boolean): String {
        if (isDir) return "folder"
        val m = of(name)
        return when {
            m.startsWith("image/") -> "image"
            m.startsWith("video/") -> "video"
            m.startsWith("audio/") -> "audio"
            m == "application/pdf" -> "pdf"
            m.startsWith("text/") -> "text"
            m.contains("zip") || m.contains("rar") || m.contains("7z") -> "archive"
            else -> "file"
        }
    }
}
