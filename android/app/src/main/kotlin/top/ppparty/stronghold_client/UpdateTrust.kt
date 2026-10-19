package top.ppparty.stronghold_client

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object UpdateTrust {
    private val hash = Regex("[0-9a-f]{64}")
    fun validHash(value: String) = hash.matches(value)
    fun artPath(path: String): Boolean = (path.startsWith("/assets/") || path.startsWith("/fonts/")) &&
        path.split('/').none { it == "." || it == ".." } &&
        !path.contains('\\') && !path.contains('\u0000') &&
        (path in setOf("/assets/ui/statistics/fonts.css", "/fonts/fonts.css") || path.substringAfterLast('.', "").lowercase() in setOf("webp", "png", "jpg", "jpeg", "gif", "avif", "svg", "mp3", "ogg", "wav", "m4a", "aac", "flac", "ttf", "otf", "woff", "woff2", "skel", "atlas", "json", "bin", "ktx", "ktx2"))
    fun verify(payload: ByteArray, signature: ByteArray, publicDer: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicDer))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
    } catch (_: Exception) { false }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(131072)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
