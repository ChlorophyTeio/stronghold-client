package top.ppparty.stronghold_client

import android.content.Context
import okhttp3.*
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

interface UpdateDownload {
    fun checkCancelled()
    fun download(url: String, target: File, size: Long, progress: (Long) -> Unit)
}

/** Only this object owns update sockets; never changes global proxy, auth or WebView. */
data class UpdateNetwork(val mode: String = "direct", val prefix: String = "", val host: String = "", val port: Int = 0,
    val user: String = "", val password: String = "") {
    companion object {
        fun load(context: Context): UpdateNetwork {
            val prefs = context.getSharedPreferences("client_updates", Context.MODE_PRIVATE)
            return UpdateNetwork(prefs.getString("mode", "direct")!!, prefs.getString("prefix", "")!!,
                prefs.getString("host", "")!!, prefs.getInt("port", 0), prefs.getString("user", "")!!, prefs.getString("password", "")!!)
        }
    }
}
class UpdateTransport(private val settings: UpdateNetwork, builder: OkHttpClient.Builder = OkHttpClient.Builder()) : UpdateDownload {
    constructor(context: Context) : this(UpdateNetwork.load(context))
    private val mode = settings.mode
    private val prefix = settings.prefix.trim().trimEnd('/')
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val client: OkHttpClient
    init {
        builder.connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .eventListener(object : EventListener() {
                override fun callEnd(call: Call) { calls.remove(call) }
                override fun callFailed(call: Call, ioe: IOException) { calls.remove(call) }
            })
        if (mode == "http" || mode == "socks") {
            val host = settings.host.trim()
            val port = settings.port
            require(host.isNotBlank() && port in 1..65535) { "代理地址或端口无效" }
            builder.proxy(Proxy(if (mode == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(host, port)))
            val user = settings.user
            if (mode == "http" && user.isNotBlank()) {
                val credential = Credentials.basic(user, settings.password)
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            }
            require(mode != "socks" || user.isEmpty()) { "SOCKS5 暂支持无认证代理；带认证请使用 HTTP 代理" }
        } else if (mode == "system") {
            // Read Android's Wi-Fi proxy/PAC selection for this client only.
            // In particular, do not call ProxySelector.setDefault or change WebView.
            builder.proxy(null)
            ProxySelector.getDefault()?.let { builder.proxySelector(it) }
        } else builder.proxy(Proxy.NO_PROXY)
        if (mode == "accelerator") requireHttps(prefix)
        client = builder.build()
    }
    fun cancel() { cancelled.set(true); calls.forEach { it.cancel() } }
    override fun checkCancelled() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("更新已取消，可再次检查后续传") }
    private fun routed(url: String): String {
        requireHttps(url)
        return if (mode == "accelerator") {
            require(URI(url).host in setOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) { "加速模式仅允许 GitHub 更新链接" }
            "$prefix/$url"
        } else url
    }
    private fun response(url: String, offset: Long): Response {
        var current = routed(url)
        repeat(8) {
            checkCancelled()
            requireHttps(current)
            val request = Request.Builder().url(current).header("Accept-Encoding", "identity")
                .header("User-Agent", "Stronghold-Client-Updater/1")
            if (offset > 0) request.header("Range", "bytes=$offset-")
            val active = client.newCall(request.build()); calls.add(active)
            if (cancelled.get()) active.cancel()
            val response = active.execute()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")
                val resolved = next?.let { response.request.url.resolve(it)?.toString() }
                response.close()
                require(resolved != null) { "更新重定向无效" }
                requireHttps(resolved)
                // A GitHub redirect from a URL accelerator continues through the accelerator.
                current = if (mode == "accelerator" && URI(resolved).host in setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) routed(resolved) else resolved
            } else return response
        }
        throw IOException("更新重定向次数过多")
    }
    fun bytes(url: String, limit: Int): ByteArray = response(url, 0).use { r ->
        require(r.code == 200) { "更新请求失败 HTTP ${r.code}" }
        val body = r.body ?: throw IOException("更新响应为空")
        body.byteStream().use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val n = stream.read(buffer); if (n < 0) break
                require(out.size() + n <= limit) { "更新清单超过大小限制" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }
    override fun download(url: String, target: File, size: Long, progress: (Long) -> Unit) {
        require(size in 1..2_000_000_000L) { "更新文件大小无效" }
        target.parentFile!!.mkdirs()
        if (target.length() > size) target.delete()
        var offset = target.length()
        if (offset == size) { progress(size); return }
        response(url, offset).use { response ->
            if (response.code == 200) offset = 0
            else {
                require(response.code == 206 && offset > 0) { "下载失败 HTTP ${response.code}" }
                require(response.header("Content-Range")?.startsWith("bytes $offset-") == true &&
                    response.header("Content-Range")?.endsWith("/$size") == true) { "续传范围无效" }
            }
            val body = response.body ?: throw IOException("下载响应为空")
            progress(offset)
            java.io.FileOutputStream(target, offset > 0).use { out ->
                body.byteStream().use { stream ->
                    val buffer = ByteArray(131072)
                    var received = offset
                    while (true) {
                        checkCancelled()
                        val n = stream.read(buffer); if (n < 0) break
                        require(received + n <= size) { "下载大小与签名清单不符" }
                        out.write(buffer, 0, n); received += n; progress(received)
                    }
                    out.fd.sync()
                    require(received == size) { "下载中断，下次继续" }
                }
            }
        }
    }
    companion object {
        fun requireHttps(url: String) {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null) { "更新地址必须使用有效 HTTPS" }
        }
    }
}
