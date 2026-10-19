package top.ppparty.stronghold_client

import java.net.URI
import java.net.URLDecoder

/** Pure rules, shared by native streaming code and JVM regression tests. */
object AssetRules {
    data class Lookup(val hash: String, val paths: List<String>)
    data class Range(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    private val hashed = Regex("^/(assets|fonts|media)/~([0-9a-f]{16})/(.+)$")
    private val audio = listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav")

    fun isHttpsOrigin(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    } catch (_: Exception) { false }

    fun sameOrigin(a: URI, b: URI): Boolean = a.scheme == b.scheme &&
        a.host?.lowercase() == b.host?.lowercase() &&
        (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port)

    fun lookup(value: String, server: URI): Lookup? = try {
        val uri = URI(value)
        if (uri.scheme != "https" || !sameOrigin(uri, server) || uri.rawQuery != null ||
            uri.rawFragment != null || uri.rawUserInfo != null) null
        else {
            val match = hashed.matchEntire(uri.rawPath ?: "")
            if (match == null) null else {
                val root = match.groupValues[1]
                val rest = URLDecoder.decode(match.groupValues[3].replace("+", "%2B"), "UTF-8")
                if (rest.contains('\\') || rest.contains('\u0000') ||
                    rest.split('/').any { it.isEmpty() || it == "." || it == ".." }) null
                else Lookup(match.groupValues[2], if (root == "media")
                    audio.map { "/assets/audio/$rest$it" } else listOf("/$root/$rest"))
            }
        }
    } catch (_: Exception) { null }

    fun range(value: String, size: Long): Range? {
        if (size <= 0) return null
        val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(value) ?: return null
        val left = match.groupValues[1]
        val right = match.groupValues[2]
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            return Range(if (suffix >= size) 0 else size - suffix, size - 1)
        }
        val start = left.toLongOrNull() ?: return null
        val end = if (right.isEmpty()) size - 1 else right.toLongOrNull() ?: return null
        if (start >= size || start > end) return null
        return Range(start, minOf(end, size - 1))
    }

    fun mime(asset: String): String = when (asset.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "atlas" -> "text/plain"
        "css" -> "text/css"
        "json" -> "application/json"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }
}
