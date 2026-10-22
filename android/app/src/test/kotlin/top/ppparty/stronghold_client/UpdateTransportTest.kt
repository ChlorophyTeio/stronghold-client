package top.ppparty.stronghold_client

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ProxySelector

class UpdateTransportTest {
    @Test fun cancellationStopsAllFourConcurrentRequests() {
        server().use { origin ->
            repeat(4) { origin.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)) }
            val net = UpdateTransport(UpdateNetwork(), builder())
            val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val futures = (0 until 4).map { number -> workers.submit<Boolean> {
                    try { net.bytes(origin.url("/blocked-$number").toString(), 100); false }
                    catch (_: java.io.IOException) { true }
                } }
                repeat(4) { assertNotNull(origin.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
                net.cancel()
                futures.forEach { assertTrue(it.get(3, java.util.concurrent.TimeUnit.SECONDS)) }
            } finally { net.cancel(); workers.shutdownNow() }
        }
    }
    @Test fun systemHttpProxyIsUsedOnlyByOptedInUpdaterWithoutSavedCredentials() {
        val original = ProxySelector.getDefault()
        MockWebServer().use { proxy ->
            val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            proxy.useHttps(tls.sslSocketFactory(), true)
            proxy.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.UPGRADE_TO_SSL_AT_END))
            proxy.enqueue(MockResponse().setBody("system-proxy"))
            proxy.start()
            var selections = 0
            val selector = object : ProxySelector() {
                override fun select(uri: java.net.URI): List<java.net.Proxy> {
                    // The JVM may also ask for socket:// routes while opening a
                    // direct TCP socket; Android's Wi-Fi HTTP proxy is URL based.
                    if (uri.scheme != "https") return listOf(java.net.Proxy.NO_PROXY)
                    selections++
                    return listOf(java.net.Proxy(java.net.Proxy.Type.HTTP,
                        java.net.InetSocketAddress(proxy.hostName, proxy.port)))
                }
                override fun connectFailed(uri: java.net.URI, address: java.net.SocketAddress, error: java.io.IOException) {}
            }
            try {
                ProxySelector.setDefault(selector) // Test fixture only; production never sets it.
                val net = UpdateTransport(UpdateNetwork(mode="system", user="saved-manual-user", password="saved-manual-secret"), builder())
                assertEquals("system-proxy", String(net.bytes("https://localhost/file", 100)))
                assertTrue(selections > 0)
                val connect = proxy.takeRequest()
                assertEquals("CONNECT", connect.method)
                assertNull(connect.getHeader("Proxy-Authorization"))
                val request = proxy.takeRequest()
                assertNull(request.getHeader("Proxy-Authorization"))
                assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
                assertSame(selector, ProxySelector.getDefault())
                server().use { origin ->
                    origin.enqueue(MockResponse().setBody("direct"))
                    val before = selections
                    assertEquals("direct", String(UpdateTransport(UpdateNetwork(), builder()).bytes(origin.url("/file").toString(), 100)))
                    assertEquals(before, selections)
                }
            } finally { ProxySelector.setDefault(original) }
        }
    }
    @Test fun systemModeUsesDirectWhenSystemHasNoProxy() {
        val original = ProxySelector.getDefault()
        try {
            ProxySelector.setDefault(null)
            server().use { origin ->
                origin.enqueue(MockResponse().setBody("no-system-proxy"))
                assertEquals("no-system-proxy", String(UpdateTransport(UpdateNetwork(mode="system"), builder()).bytes(origin.url("/file").toString(), 100)))
                assertNull(ProxySelector.getDefault())
            }
        } finally { ProxySelector.setDefault(original) }
    }
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName(java.net.InetAddress.getLoopbackAddress().hostName).addSubjectAlternativeName(java.net.InetAddress.getLoopbackAddress().hostAddress).addSubjectAlternativeName(java.net.InetAddress.getByName("localhost").canonicalHostName).build()
    private fun builder(): OkHttpClient.Builder {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        return OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
    }
    private fun server() = MockWebServer().apply {
        val server = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        useHttps(server.sslSocketFactory(), false); start()
    }
    @Test fun redirectKeepsRangeAndNeverSendsGameCredentials() {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/blob")))
            server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 3-5/6").setBody("def"))
            val file = File.createTempFile("update-", ".part")
            try {
                file.writeText("abc")
                val selector = ProxySelector.getDefault()
                val authenticator = java.net.Authenticator::class.java.getMethod("getDefault").invoke(null)
                UpdateTransport(UpdateNetwork(), builder()).download(server.url("/redirect").toString(), file, 6) {}
                assertEquals("abcdef", file.readText())
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals("bytes=3-", request.getHeader("Range"))
                    assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Proxy-Authorization"))
                }
                assertSame(selector, ProxySelector.getDefault())
                assertSame(authenticator, java.net.Authenticator::class.java.getMethod("getDefault").invoke(null))
            } finally { file.delete() }
        }
    }
    @Test fun ignoredRangeRestartsFileInsteadOfAppending() {
        server().use { server ->
            server.enqueue(MockResponse().setBody("abcdef"))
            val file = File.createTempFile("update-", ".part")
            try {
                file.writeText("abc")
                UpdateTransport(UpdateNetwork(), builder()).download(server.url("/file").toString(), file, 6) {}
                assertEquals("abcdef", file.readText())
            } finally { file.delete() }
        }
    }
    @Test fun refusesWrongRangeOversizeTlsDowngradeAndCancellation() {
        server().use { server ->
            val file = File.createTempFile("update-", ".part")
            try {
                file.writeText("abc")
                server.enqueue(MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-2/6").setBody("def"))
                try { UpdateTransport(UpdateNetwork(), builder()).download(server.url("/x").toString(), file, 6) {}; fail() } catch (_: IllegalArgumentException) {}
                server.enqueue(MockResponse().setBody("too big"))
                try { UpdateTransport(UpdateNetwork(), builder()).bytes(server.url("/x").toString(), 2); fail() } catch (_: IllegalArgumentException) {}
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost/x"))
                try { UpdateTransport(UpdateNetwork(), builder()).bytes(server.url("/x").toString(), 100); fail() } catch (_: IllegalArgumentException) {}
                val net = UpdateTransport(UpdateNetwork(), builder()); net.cancel()
                try { net.bytes(server.url("/x").toString(), 100); fail() } catch (_: java.io.IOException) {}
            } finally { file.delete() }
        }
    }
    @Test fun defaultTlsRejectsUntrustedCertificates() {
        server().use { server ->
            try { UpdateTransport(UpdateNetwork()).bytes(server.url("/x").toString(), 100); fail() }
            catch (_: javax.net.ssl.SSLHandshakeException) {}
        }
    }
    @Test fun authenticatedHttpProxyKeepsCredentialsInsideConnectTunnel() {
        MockWebServer().use { proxy ->
            val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            proxy.useHttps(tls.sslSocketFactory(), true)
            proxy.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.UPGRADE_TO_SSL_AT_END))
            proxy.enqueue(MockResponse().setBody("proxied"))
            proxy.start()
            val net = UpdateTransport(UpdateNetwork(mode="http", host=proxy.hostName, port=proxy.port, user="alice", password="secret"), builder())
            assertEquals("proxied", String(net.bytes("https://localhost/file", 100)))
            val tunnel = proxy.takeRequest()
            assertEquals("CONNECT", tunnel.method)
            assertEquals(okhttp3.Credentials.basic("alice", "secret"), tunnel.getHeader("Proxy-Authorization"))
            val origin = proxy.takeRequest()
            assertNull(origin.getHeader("Proxy-Authorization"))
            assertNull(origin.getHeader("Authorization")); assertNull(origin.getHeader("Cookie"))
        }
    }
    @Test fun socks5ConnectionProxyWorksWithoutGlobalAuthenticator() {
        server().use { origin ->
            val proxy = java.net.ServerSocket(0)
            val accepted = java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
            val upstream = java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
            val pool = java.util.concurrent.Executors.newCachedThreadPool { job -> Thread(job).apply { isDaemon = true } }
            val handshake = pool.submit {
                val socket = proxy.accept(); accepted.set(socket)
                val input = java.io.DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                check(input.readUnsignedByte() == 5)
                val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                check(methods.contains(0.toByte()))
                output.write(byteArrayOf(5,0)); output.flush()
                check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1); input.readUnsignedByte()
                val host = when(input.readUnsignedByte()) {
                    3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                    1 -> java.net.InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                    4 -> java.net.InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                    else -> error("Invalid SOCKS target")
                }
                val remote = java.net.Socket(host, input.readUnsignedShort()); upstream.set(remote)
                output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); output.flush()
                pool.submit { try { remote.getInputStream().copyTo(output) } catch (_: Exception) {} }
                try { input.copyTo(remote.getOutputStream()) } catch (_: Exception) {}
            }
            try {
                origin.enqueue(MockResponse().setBody("socks"))
                val net = UpdateTransport(UpdateNetwork(mode="socks", host="127.0.0.1", port=proxy.localPort), builder())
                assertEquals("socks", String(net.bytes(origin.url("/file").toString(), 100)))
                assertNull(origin.takeRequest().getHeader("Proxy-Authorization"))
            } finally {
                accepted.get()?.close(); upstream.get()?.close(); proxy.close(); handshake.cancel(true); pool.shutdownNow()
            }
        }
    }
    @Test fun acceleratorOnlyRoutesGithubAndKeepsCredentialHeadersEmpty() {
        server().use { server ->
            server.enqueue(MockResponse().setBody("manifest"))
            val net = UpdateTransport(UpdateNetwork(mode="accelerator", prefix=server.url("/proxy").toString()), builder())
            assertEquals("manifest", String(net.bytes("https://github.com/owner/repo/file", 100)))
            val request = server.takeRequest()
            assertEquals("/proxy/https://github.com/owner/repo/file", request.path)
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            try { net.bytes("https://private.example.com/token", 100); fail() } catch (_: IllegalArgumentException) {}
        }
    }
}
