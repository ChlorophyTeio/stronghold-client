package top.ppparty.stronghold_client

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class UpdateTrustTest {
    @Test fun signaturesRejectTamperingAndWrongKeys() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val data = "signed release".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(data); sign() }
        assertTrue(UpdateTrust.verify(data, sig, keys.public.encoded))
        assertFalse(UpdateTrust.verify("altered".toByteArray(), sig, keys.public.encoded))
        assertFalse(UpdateTrust.verify(data, sig.copyOf(8), keys.public.encoded))
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertFalse(UpdateTrust.verify(data, sig, other.public.encoded))
    }
    @Test fun onlyArtCanBeActivated() {
        for (path in listOf("/assets/a.webp", "/assets/model.json", "/assets/ui/statistics/fonts.css", "/fonts/a.woff2")) assertTrue(path, UpdateTrust.artPath(path))
        for (path in listOf("/assets/../a.png", "/assets/a\\b.png", "/assets/a\u0000.png", "/api/me.json", "/assets/game.js", "/assets/game.html", "/assets/game.css")) assertFalse(path, UpdateTrust.artPath(path))
    }
    @Test fun hashesRequireExactSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateTrust.digest("abc".toByteArray()))
        assertTrue(UpdateTrust.validHash("a".repeat(64)))
        assertFalse(UpdateTrust.validHash("../" + "a".repeat(64)))
        assertFalse(UpdateTrust.validHash("A".repeat(64)))
    }
    @Test fun updatesAlwaysRequireHttpsAndNoEmbeddedCredentials() {
        for (url in listOf("https://github.com/a/b", "https://example.com:8443/file")) UpdateTransport.requireHttps(url)
        for (url in listOf("http://example.com/file", "https://user:password@example.com/file", "file:///tmp/a", "https://example.com/a#x")) {
            try { UpdateTransport.requireHttps(url); fail(url) } catch (_: IllegalArgumentException) {}
        }
    }
}
