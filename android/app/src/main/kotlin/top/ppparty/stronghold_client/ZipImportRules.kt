package top.ppparty.stronghold_client

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ZipImportRules {
    fun validate(file: File) {
        require(file.length() in 22..2_000_000_000L) { "ZIP 大小无效或超过 2 GB" }
        RandomAccessFile(file, "r").use { input ->
            val tail = ByteArray(minOf(file.length(), 65557).toInt())
            input.seek(file.length() - tail.size); input.readFully(tail)
            val bytes = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
                bytes.getInt(offset) == 0x06054b50 &&
                    offset + 22 + (bytes.getShort(offset + 20).toInt() and 65535) == tail.size
            } ?: error("ZIP 目录不完整，请重新传输文件")
            val entries = bytes.getShort(end + 10).toInt() and 65535
            val directorySize = bytes.getInt(end + 12).toLong() and 0xffffffffL
            val directoryOffset = bytes.getInt(end + 16).toLong() and 0xffffffffL
            require(bytes.getInt(end + 4) == 0 && entries < 65535 && directorySize <= 32L * 1024 * 1024 &&
                directoryOffset + directorySize <= file.length()) { "ZIP 分卷、条目数或目录大小不受支持" }
        }
    }
}
