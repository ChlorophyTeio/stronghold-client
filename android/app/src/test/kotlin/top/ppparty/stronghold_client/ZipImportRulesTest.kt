package top.ppparty.stronghold_client

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipImportRulesTest {
    @Test fun acceptsNormalOfflineBundleAndRejectsTruncatedOrHugeDirectory() {
        val file = File.createTempFile("offline-art-", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("update-preview.json")); zip.write("fixture".toByteArray()); zip.closeEntry()
            }
            ZipImportRules.validate(file)
            val valid = file.readBytes()
            file.writeBytes(valid.copyOf(valid.size - 1))
            try { ZipImportRules.validate(file); fail("Truncated ZIP accepted") } catch (_: IllegalStateException) {}
            val bad = valid.copyOf()
            val end = bad.size - 22
            repeat(4) { bad[end + 12 + it] = 0x7f }
            file.writeBytes(bad)
            try { ZipImportRules.validate(file); fail("Oversized central directory accepted") } catch (_: IllegalArgumentException) {}
        } finally { file.delete() }
    }
}
