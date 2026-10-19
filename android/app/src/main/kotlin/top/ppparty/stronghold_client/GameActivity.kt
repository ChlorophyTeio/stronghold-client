package top.ppparty.stronghold_client

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A real Android window: no Flutter platform-view/rotation/compositor dependency. */
class GameActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView
    private lateinit var server: URI
    private val ui = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    @Volatile private var entries: JSONObject? = null
    @Volatile private var updatedEntries: JSONObject? = null
    private lateinit var updateStore: UpdateStore
    @Volatile private var usePack = true
    private val hits = AtomicLong()
    private val saved = AtomicLong()
    private val errors = ArrayDeque<String>()
    private val manualInsets = IntArray(4)
    private val marginKeys = arrayOf("left", "top", "right", "bottom")
    private var generation = 0
    private var ready = false
    private var rendererGone = false
    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        server = URI(ServerConfig.ORIGIN)
        val displayPrefs = getSharedPreferences("client_display", MODE_PRIVATE)
        marginKeys.forEachIndexed { index, key ->
            manualInsets[index] = displayPrefs.getInt(key, 0).coerceIn(0, 64)
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 18, 23)) }
        setContentView(root)
        status = TextView(this).apply {
            text = "正在打开测试服…"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6222930.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))
        applyFullscreenViewport()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { menu() }
        }
        fullscreen()
        // Parse the immutable manifest off the UI thread; do not send texture
        // bytes over Flutter's platform channel for each resource request.
        loader.execute {
            updateStore = UpdateStore(this)
            updatedEntries = updateStore.active()?.entries
            try {
                entries = assets.open("flutter_assets/assets/game/index.json").bufferedReader().use {
                    JSONObject(it.readText()).getJSONObject("entries")
                }
            } catch (_: Exception) { record("本地资源清单不可用，使用在线资源") }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    try { createWebView() }
                    catch (error: Exception) { showFailure("无法启动系统 WebView：${error.javaClass.simpleName}") }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        fullscreen()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen()
    }

    @Suppress("DEPRECATION")
    private fun fullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    /** Full display first: only explicit user margins reduce the game viewport. */
    private fun applyFullscreenViewport() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        root.fitsSystemWindows = false
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyContentPadding()
            // Stop WebView from applying a second cutout/system-bar safe area.
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else {
                @Suppress("DEPRECATION")
                val consumed = insets.consumeSystemWindowInsets()
                if (Build.VERSION.SDK_INT >= 28) consumed.consumeDisplayCutout() else consumed
            }
        }
        applyContentPadding()
        root.requestApplyInsets()
    }

    private fun applyContentPadding() {
        root.setPadding(dp(manualInsets[0]), dp(manualInsets[1]),
            dp(manualInsets[2]), dp(manualInsets[3]))
    }

    private fun saveMargins() {
        getSharedPreferences("client_display", MODE_PRIVATE).edit().apply {
            marginKeys.forEachIndexed { index, key -> putInt(key, manualInsets[index]) }
        }.apply()
    }

    private fun marginSettings() {
        val labels = arrayOf("左侧边距", "顶部边距", "右侧边距", "底部边距")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(TextView(this).apply {
            text = "默认铺满全屏，不自动避让挖孔、圆角和手势区。四边均为 0 时无额外留白；按需要手动增加边距，拖动立即生效并自动保存。"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })
        val sliders = mutableListOf<SeekBar>()
        for (pair in listOf(listOf(0, 2), listOf(1, 3))) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            content.addView(row)
            for (index in pair) {
            val label = labels[index]
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            val value = TextView(this).apply { text = "$label：${manualInsets[index]}"; textSize = 15f }
            cell.addView(value)
            val slider = SeekBar(this).apply {
                max = 64; progress = manualInsets[index]; contentDescription = label
                minimumHeight = dp(44)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        manualInsets[index] = progress
                        value.text = "$label：$progress"
                        applyContentPadding()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) { saveMargins() }
                })
            }
            sliders.add(slider)
            cell.addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
            }
        }
        content.addView(Button(this).apply {
            text = "恢复默认"
            setOnClickListener {
                sliders.forEach { it.progress = 0 }
                saveMargins()
            }
        })
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle("屏幕边距")
            .setView(scroll).setPositiveButton("完成", null).create()
        dialog.setOnDismissListener { saveMargins() }
        dialog.show()
        dialog.window?.apply {
            setLayout(minOf(dp(420), (resources.displayMetrics.widthPixels * 0.8).toInt()),
                (resources.displayMetrics.heightPixels * 0.88).toInt())
            attributes = attributes.apply { dimAmount = 0.15f }
        }
    }

    private fun installSettings(view: WebView) {
        // UI-only hook, no JavaScript interface or account/native filesystem access.
        try {
            val script = assets.open("client-settings.js").bufferedReader().use { it.readText() }
            view.evaluateJavascript(script, null)
        } catch (_: Exception) { record("客户端设置入口不可用，请使用 Android 返回手势") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(12, 18, 23))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }
        WebView.setWebContentsDebuggingEnabled(
            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, false) }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { progress.progress = value }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    record("页面脚本：${message.message().take(500)}")
                }
                return true
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), 6413)
                    true
                } catch (_: Exception) { fileCallback = null; false }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = try { URI(request.url.toString()) } catch (_: Exception) { return true }
                val sameOrigin = AssetRules.sameOrigin(url, server)
                if (sameOrigin && url.path == "/__sp_client_settings__") {
                    if (request.isForMainFrame) menu()
                    return true
                }
                return url.scheme != "https" || !sameOrigin
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                localResource(request)

            override fun onPageStarted(view: WebView, url: String?, icon: Bitmap?) {
                generation++; ready = false
                status.text = "正在连接测试服…"; status.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                val current = generation
                ui.postDelayed({
                    if (!isDestroyed && generation == current && !ready) {
                        showFailure("加载超过 25 秒。请检查网络，或使用 Android 返回手势查看诊断。")
                    }
                }, 25000)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                installSettings(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                installSettings(view)
                probe(generation, 0)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                record("加载失败 ${error.errorCode}：${request.url.path} ${error.description}")
                if (request.isForMainFrame) showFailure("无法打开测试服：${error.description}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                response: WebResourceResponse) {
                record("HTTP ${response.statusCode}：${request.url.path}")
                if (request.isForMainFrame) showFailure("测试服返回 HTTP ${response.statusCode}")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                record("TLS 证书校验失败：${error.primaryError}")
                if (!ready && try { AssetRules.sameOrigin(URI(error.url), server) }
                    catch (_: Exception) { false }) {
                    showFailure("安全连接失败：请检查服务器证书和设备时间")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                record("WebView 渲染进程退出，crash=${detail.didCrash()}")
                rendererGone = true
                root.removeView(view); view.destroy()
                showFailure("系统 WebView 渲染进程退出，请返回启动器重新进入")
                return true
            }
        }
        root.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        web.loadUrl(server.toString())
    }

    private fun probe(current: Int, attempt: Int) {
        if (isDestroyed || generation != current) return
        web.evaluateJavascript("""(function(){return JSON.stringify({
          ready:!!window.__SP__, title:document.title,
          error:document.getElementById('boot-err')?.innerText||'',
          app:document.getElementById('app')?.innerText?.length||0
        })})()""") { value ->
            if (isDestroyed || generation != current) return@evaluateJavascript
            try {
                val raw = org.json.JSONTokener(value).nextValue() as? String ?: "{}"
                val state = JSONObject(raw)
                if (state.optBoolean("ready") || state.optInt("app") > 0) {
                    ready = true; status.visibility = View.GONE; progress.visibility = View.GONE
                } else if (state.optString("error").isNotBlank()) {
                    showFailure(state.optString("error").take(300))
                } else if (attempt < 45) ui.postDelayed({ probe(current, attempt + 1) }, 500)
            } catch (_: Exception) { record("无法读取页面启动状态") }
        }
    }

    private fun localResource(request: WebResourceRequest): WebResourceResponse? {
        if (!usePack || request.isForMainFrame || request.method != "GET") return null
        val lookup = AssetRules.lookup(request.url.toString(), server) ?: return null
        for (index in listOfNotNull(updatedEntries, entries)) for (path in lookup.paths) {
            val entry = index.optJSONObject(path) ?: continue
            val hashes = entry.optJSONArray("hashes") ?: continue
            if ((0 until hashes.length()).none { hashes.optString(it) == lookup.hash }) continue
            val headers = request.requestHeaders
            if (headers.keys.any { it.equals("if-range", true) }) return null
            val size = entry.optLong("bytes", -1)
            if (size < 0) return null
            val rawRange = headers.entries.firstOrNull { it.key.equals("range", true) }?.value
            val range = rawRange?.let { AssetRules.range(it, size) }
            if (rawRange != null && range == null) return null
            try {
                val downloaded = if (index === updatedEntries) updateStore.file(entry) ?: continue else null
                val asset = if (downloaded != null) path else entry.getString("asset")
                val stream = downloaded?.inputStream() ?: assets.open("flutter_assets/$asset", android.content.res.AssetManager.ACCESS_STREAMING)
                if (range != null) {
                    var left = range.start
                    while (left > 0) {
                        val skipped = stream.skip(left)
                        if (skipped > 0) left -= skipped else {
                            if (stream.read() < 0) { stream.close(); return null }
                            left--
                        }
                    }
                }
                val count = range?.length ?: size
                val body = object : FilterInputStream(stream) {
                    private var remaining = count
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        val value = super.read()
                        if (value >= 0) remaining--
                        return value
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        if (remaining <= 0) return -1
                        val n = `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                        if (n > 0) remaining -= n
                        return n
                    }
                }
                val responseHeaders = mutableMapOf(
                    "Content-Length" to count.toString(), "Accept-Ranges" to "bytes",
                    "Cache-Control" to "public, max-age=31536000, immutable", "X-SP-Resource" to if (downloaded == null) "apk-native" else "update-native")
                if (range != null) responseHeaders["Content-Range"] = "bytes ${range.start}-${range.end}/$size"
                hits.incrementAndGet(); saved.addAndGet(count)
                val mime = AssetRules.mime(asset)
                return WebResourceResponse(mime, if (mime.startsWith("text/")) "utf-8" else null,
                    if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content",
                    responseHeaders, body)
            } catch (_: Exception) { record("本地素材读取失败，回退网络"); return null }
        }
        return null
    }

    private fun showFailure(message: String) {
        if (isDestroyed) return
        progress.visibility = View.GONE
        status.text = "$message\n\n点此重试，或使用 Android 返回手势打开客户端设置"
        status.visibility = View.VISIBLE
        status.setOnClickListener { reload() }
    }

    @Synchronized private fun record(message: String) {
        if (errors.size >= 12) errors.removeFirst()
        errors.addLast(message)
    }

    private fun diagnostics(): String {
        val provider = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.let {
            "${it.packageName} ${it.versionName}" } ?: "不可用" else "系统 WebView"
        return "客户端 0.1.4\n${server}\nAndroid ${Build.VERSION.RELEASE} / ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "WebView：$provider\n横屏：${resources.configuration.orientation == 2}\n" +
            "额外边距（左/上/右/下）：${manualInsets.joinToString("/")}\n" +
            "页面已启动：$ready\n本地素材：$usePack / ${hits.get()} 次 / ${saved.get() / 1048576} MiB\n" +
            synchronized(this) { errors.joinToString("\n") }
    }

    private fun menu() {
        AlertDialog.Builder(this).setTitle("客户端设置 · 测试服")
            .setItems(arrayOf("继续游戏", "屏幕边距", "重新加载", "切换为纯在线资源并重载", "查看 / 复制诊断", "返回启动器", "更新设置")) { _, which ->
                when (which) {
                    6 -> startActivity(Intent(this, UpdateActivity::class.java))
                    1 -> marginSettings()
                    2 -> confirmReload { reload() }
                    3 -> confirmReload { usePack = false; reload() }
                    4 -> AlertDialog.Builder(this).setTitle("客户端诊断").setMessage(diagnostics())
                        .setPositiveButton("复制") { _, _ ->
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("客户端诊断", diagnostics()))
                        }.setNegativeButton("关闭", null).show()
                    5 -> AlertDialog.Builder(this).setMessage("返回启动器可能中断当前对局，是否继续？")
                        .setPositiveButton("返回") { _, _ -> finish() }.setNegativeButton("取消", null).show()
                }
            }.show()
    }

    private fun confirmReload(action: () -> Unit) {
        AlertDialog.Builder(this).setMessage("重新加载可能中断当前对局，是否继续？")
            .setPositiveButton("重新加载") { _, _ -> status.setOnClickListener(null); action() }
            .setNegativeButton("取消", null).show()
    }

    private fun reload() {
        if (rendererGone || !::web.isInitialized) recreate() else web.reload()
    }

    @Deprecated("Game back opens menu instead of accidentally leaving a match")
    override fun onBackPressed() { menu() }

    @Deprecated("System file chooser result")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 6413) { fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileCallback = null }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        fileCallback?.onReceiveValue(null)
        if (::web.isInitialized && !rendererGone) { root.removeView(web); web.stopLoading(); web.destroy() }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
