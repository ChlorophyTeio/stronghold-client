package top.ppparty.stronghold_client

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AssetRulesTest {
    private val server = URI("https://spa.ppparty.top:8000/")

    @Test fun hashesArePreservedAndAudioPathsResolve() {
        val art = AssetRules.lookup("${server}assets/~0123456789abcdef/avatar.png", server)!!
        assertEquals("0123456789abcdef", art.hash)
        assertEquals(listOf("/assets/avatar.png"), art.paths)
        val audio = AssetRules.lookup("${server}media/~0123456789abcdef/bgm/theme", server)!!
        assertTrue(audio.paths.contains("/assets/audio/bgm/theme.mp3"))
        assertEquals("/assets/a+b.png", AssetRules.lookup("${server}assets/~0123456789abcdef/a+b.png", server)!!.paths[0])
    }

    @Test fun foreignOriginsAndUnsafeOrPlainPathsNeverUsePack() {
        for (url in listOf(
            "https://other.example:8000/assets/~0123456789abcdef/avatar.png",
            "https://test-spa.ppparty.top:8000/assets/~0123456789abcdef/avatar.png",
            "http://spa.ppparty.top:8000/assets/~0123456789abcdef/avatar.png",
            "https://spa.ppparty.top/assets/~0123456789abcdef/avatar.png",
            "${server}assets/avatar.png", "${server}api/auth/me",
            "${server}assets/~0123456789abcdef/avatar.png?q=1",
            "${server}assets/~0123456789abcdef/%2e%2e/avatar.png",
            "${server}assets/~0123456789abcdef/bad%00.png",
            "${server}assets/~0123456789abcdef/a%5cb.png")) {
            assertNull(url, AssetRules.lookup(url, server))
        }
    }

    @Test fun rangesSupportSuffixOpenAndBounded() {
        assertEquals(AssetRules.Range(2, 5), AssetRules.range("bytes=2-5", 10))
        assertEquals(AssetRules.Range(8, 9), AssetRules.range("bytes=8-", 10))
        assertEquals(AssetRules.Range(8, 9), AssetRules.range("bytes=8-100", 10))
        assertEquals(AssetRules.Range(7, 9), AssetRules.range("bytes=-3", 10))
        assertEquals(AssetRules.Range(0, 9), AssetRules.range("bytes=-30", 10))
        for (value in listOf("bytes=10-", "bytes=5-2", "bytes=-0", "bytes=-", "bytes=0-1,3-4", "bytes=999999999999999999999-")) {
            assertNull(value, AssetRules.range(value, 10))
        }
    }

    @Test fun releaseServerIsFixedToProduction() {
        assertEquals("https://spa.ppparty.top:8000/", ServerConfig.ORIGIN)
    }

    @Test fun onlyHttpsOriginsAreAccepted() {
        assertTrue(AssetRules.isHttpsOrigin(server.toString()))
        for (value in listOf("http://example.com", "file:///x", "https://u:p@example.com", "https://example.com/a", "https://example.com/?q=x", "https://example.com/#f")) {
            assertFalse(value, AssetRules.isHttpsOrigin(value))
        }
    }
}
