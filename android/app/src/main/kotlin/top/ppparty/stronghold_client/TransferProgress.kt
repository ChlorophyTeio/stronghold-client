package top.ppparty.stronghold_client

import java.util.Locale

data class TransferProgress(val completedBytes: Long, val totalBytes: Long, val stage: String) {
    val fraction: Int get() = if (totalBytes > 0)
        (completedBytes.coerceIn(0, totalBytes).toDouble() / totalBytes * 1000).toInt() else 0
    val description: String get() = if (totalBytes > 0)
        String.format(Locale.ROOT, "%.1f%% · %.1f / %.1f MiB", fraction / 10.0,
            completedBytes.coerceIn(0, totalBytes) / 1048576.0, totalBytes / 1048576.0)
        else if (stage.contains("ZIP")) String.format(Locale.ROOT, "已读取 %.1f MiB", completedBytes / 1048576.0) else "无需下载"
}
