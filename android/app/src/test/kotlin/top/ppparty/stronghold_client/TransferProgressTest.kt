package top.ppparty.stronghold_client

import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    @Test fun reportsAggregateBytesAndPercentIncludingResumedBytes() {
        val state = TransferProgress(3 * 1048576L, 4 * 1048576L, "素材分包 2/3 · 下载中")
        assertEquals(750, state.fraction)
        assertEquals("75.0% · 3.0 / 4.0 MiB", state.description)
    }
    @Test fun supportsEmptyDownloadAndLargeBundlesWithoutOverflow() {
        assertEquals("无需下载", TransferProgress(0, 0, "就绪").description)
        assertEquals(500, TransferProgress(3_000_000_000, 6_000_000_000, "下载中").fraction)
        assertEquals(1000, TransferProgress(20, 10, "校验").fraction)
    }
}
