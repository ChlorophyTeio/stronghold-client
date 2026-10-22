package top.ppparty.stronghold_client

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

class UpdateActivity : Activity() {
    private lateinit var store: UpdateStore
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var source: JSONObject
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadDetails: TextView
    private var lastProgressAt = 0L
    private var lastProgressStage = ""
    private var lastRateAt = 0L
    private var lastRateBytes = 0L
    private var bytesPerSecond = 0.0
    private var release: UpdateStore.Release? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var transport: UpdateTransport? = null
    @Volatile private var busy = false
    private val buttons = ArrayList<Button>()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = UpdateStore(this)
        source = assets.open("update-source.json").bufferedReader().use { JSONObject(it.readText()) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val safe = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()) else null
            @Suppress("DEPRECATION")
            view.setPadding(safe?.left ?: insets.systemWindowInsetLeft, safe?.top ?: insets.systemWindowInsetTop,
                safe?.right ?: insets.systemWindowInsetRight, safe?.bottom ?: insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll); scroll.requestApplyInsets()
        text("客户端更新 · 正式服", 22f)
        text("游戏素材在安装后独立下载，下次进入游戏生效。可先在线登录；游戏连接不经过更新代理。", 14f)
        status = text(if (source.optString("repository").isBlank()) "尚未配置更新源：等待建立 GitHub 仓库。" else "更新源：${source.getString("repository")} · preview", 16f)
        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }
        content.addView(downloadProgress, LinearLayout.LayoutParams(-1, -2))
        downloadDetails = text("", 14f).apply { visibility = View.GONE }
        text("当前素材：${store.active()?.label ?: "尚未下载，游戏将在线加载"}", 14f)
        action("检查更新 / 测试连接") { check() }
        action("下载并启用素材更新") {
            val found = release
            if (found == null) message("请先检查更新") else {
                val packs = found.json.getJSONArray("packs")
                val total = (0 until packs.length()).sumOf { packs.getJSONObject(it).getLong("bytes") }
                AlertDialog.Builder(this).setTitle("下载游戏素材")
                    .setMessage("完整素材分包约 ${total / 1048576} MiB，仅下载本机缺少的分包。建议使用 Wi-Fi，可取消并续传。下载前可先设置仅更新代理。是否继续？")
                    .setPositiveButton("下载并启用") { _, _ -> launch { net -> store.apply(found, net, ::message, ::showProgress) } }
                    .setNegativeButton("暂不下载", null).show()
            }
        }
        action("下载客户端 APK") { downloadApk() }
        action("导入 ZIP 素材包") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }, 642)
        }
        text("自打包 ZIP 请在根目录放 assets/ 和 fonts/。先检查更新保存索引；导入匹配素材，缺失部分可下载补齐。", 12f)
        action("安装已验证的 APK") { install() }
        action("更新代理设置（仅更新）") { proxySettings() }
        action("回退上一版素材") {
            AlertDialog.Builder(this).setMessage("回退后下次进入游戏生效，当前对局不切换。确认回退？")
                .setPositiveButton("回退") { _, _ -> launch { store.rollback(); message("已回退；下次进入游戏生效") } }
                .setNegativeButton("取消", null).show()
        }
        Button(this).apply { text = "取消当前操作（下载可续传）"; setOnClickListener { transport?.cancel() }; content.addView(this) }
        Button(this).apply { text = "返回"; setOnClickListener { finish() }; content.addView(this) }
        if (intent.getBooleanExtra("checkOnOpen", false)) check()
    }
    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setPadding(8, 12, 8, 12); content.addView(this)
    }
    private fun action(label: String, block: () -> Unit) {
        val button = Button(this).apply { text = label; setOnClickListener { if (!busy) block() } }
        buttons.add(button); content.addView(button)
    }
    private fun message(value: String) { runOnUiThread { if (!isDestroyed && !isFinishing) status.text = value } }
    @Synchronized private fun showProgress(value: TransferProgress) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRateAt == 0L) { lastRateAt = now; lastRateBytes = value.completedBytes }
        if (now - lastRateAt >= 500) {
            bytesPerSecond = ((value.completedBytes - lastRateBytes).coerceAtLeast(0) * 1000.0 / (now - lastRateAt))
            lastRateAt = now; lastRateBytes = value.completedBytes
        }
        if (now - lastProgressAt < 100 && value.stage == lastProgressStage && value.completedBytes != value.totalBytes) return
        lastProgressAt = now; lastProgressStage = value.stage
        val speed = if (value.stage.contains("下载"))
            " · " + String.format(java.util.Locale.ROOT, "%.2f MiB/s", bytesPerSecond / 1048576) else ""
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                downloadProgress.visibility = View.VISIBLE
                downloadProgress.isIndeterminate = value.totalBytes <= 0 && value.stage.contains("ZIP")
                downloadProgress.progress = value.fraction
                downloadDetails.visibility = View.VISIBLE
                downloadDetails.text = "${value.stage}\n${value.description}$speed"
            }
        }
    }
    private fun launch(offline: Boolean = false, block: (UpdateTransport) -> Unit) {
        if (busy) return
        busy = true; buttons.forEach { it.isEnabled = false }
        lastProgressAt = 0; lastProgressStage = ""
        lastRateAt = 0; lastRateBytes = 0; bytesPerSecond = 0.0
        downloadProgress.visibility = View.VISIBLE; downloadProgress.isIndeterminate = true
        downloadDetails.visibility = View.VISIBLE; downloadDetails.text = "正在处理…"
        worker.execute {
            try {
                val net = if (offline) UpdateTransport(UpdateNetwork()) else UpdateTransport(this); transport = net
                block(net)
            } catch (error: Exception) { message(error.message ?: "更新失败，请重试") }
            finally {
                transport = null; busy = false
                runOnUiThread { if (!isDestroyed) {
                    buttons.forEach { it.isEnabled = true }
                    if (downloadProgress.isIndeterminate) { downloadProgress.visibility = View.GONE; downloadDetails.visibility = View.GONE }
                } }
            }
        }
    }
    @Deprecated("Storage Access Framework result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 642 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        launch(offline = true) { net ->
            val archive = File.createTempFile("import-input-", ".zip", store.staging)
            try {
                val size = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                } ?: -1L
                require(size <= 2_000_000_000L) { "ZIP 不能超过 2 GB" }
                contentResolver.openInputStream(uri).use { input ->
                    require(input != null) { "无法读取所选 ZIP" }
                    java.io.FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(131072); var copied = 0L
                        while (true) {
                            net.checkCancelled(); val count = input.read(buffer); if (count < 0) break
                            copied += count; require(copied <= 2_000_000_000L && store.root.usableSpace > 32L * 1048576) { "ZIP 过大或存储空间不足" }
                            output.write(buffer, 0, count)
                            showProgress(TransferProgress(copied, size.coerceAtLeast(0), "读取 ZIP 素材包"))
                        }
                        output.fd.sync()
                    }
                }
                message(store.importZip(archive, release ?: store.active(), net::checkCancelled, ::showProgress))
            } finally { archive.delete() }
        }
    }
    private fun check() = launch { net ->
        val repo = source.optString("repository")
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "尚未配置更新源，当前仍可正常进入正式服。" }
        message("正在检查签名更新清单…")
        val releases = JSONArray(String(net.bytes("https://api.github.com/repos/$repo/releases?per_page=20", 2 * 1024 * 1024), Charsets.UTF_8))
        var manifestUrl: String? = null
        for (i in 0 until releases.length()) {
            val item = releases.getJSONObject(i)
            if (item.optBoolean("draft")) continue
            val artifacts = item.getJSONArray("assets")
            for (j in 0 until artifacts.length()) {
                val artifact = artifacts.getJSONObject(j)
                if (artifact.optString("name") == source.getString("manifestAsset")) {
                    manifestUrl = artifact.getString("browser_download_url"); break
                }
            }
            if (manifestUrl != null) break
        }
        val url = manifestUrl ?: error("仓库尚未发布 preview 签名更新清单")
        require(url.startsWith("https://github.com/$repo/releases/download/")) { "GitHub 更新附件地址无效" }
        val bytes = net.bytes(url, 24 * 1024 * 1024)
        val found = store.parse(bytes)
        store.remember(found)
        release = found
        val packBytes = found.json.getJSONArray("packs").let { array -> (0 until array.length()).sumOf { array.getJSONObject(it).getLong("bytes") } }
        message("已验证更新：${found.label}\n素材包总量：${packBytes / 1048576} MiB（仅下载本机缺少的分包）\n${if (store.isNew(found)) "有可用素材更新" else "素材已安装或该版本已回退"}\n${found.json.optString("notes").take(4000)}")
    }
    private fun proxySettings() {
        val prefs = getSharedPreferences("client_updates", MODE_PRIVATE)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val modes = arrayOf("direct", "http", "socks", "accelerator", "system")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@UpdateActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("关闭代理：直连", "HTTP 连接代理（支持认证）", "SOCKS5（无认证）", "GitHub 下载加速前缀", "HTTP 自动代理（跟随 Wi-Fi 系统设置）"))
            setSelection(modes.indexOf(prefs.getString("mode", "direct")).coerceAtLeast(0)); form.addView(this)
        }
        fun field(label: String, key: String, password: Boolean = false): EditText = EditText(this).apply {
            hint = label; setText(prefs.getString(key, "")); isSingleLine = true
            inputType = if (password) 129 else 1
            form.addView(this)
        }
        val host = field("代理主机（不含协议）", "host")
        val port = EditText(this).apply { hint = "端口"; inputType = 2; setText(prefs.getInt("port", 0).takeIf { it > 0 }?.toString() ?: ""); form.addView(this) }
        val user = field("HTTP 代理用户名（可选）", "user")
        val password = field("HTTP 代理密码（可选）", "password", true)
        val prefix = field("加速前缀 https://你的加速服务", "prefix")
        val note = TextView(this).apply { text = "加速前缀会拼接 /https://github.com/…；不预置第三方服务。代理设置不改变游戏网络。"; form.addView(this) }
        fun visibility() {
            val connection = spinner.selectedItemPosition in 1..2
            listOf(host, port).forEach { it.visibility = if (connection) View.VISIBLE else View.GONE }
            listOf(user, password).forEach { it.visibility = if (spinner.selectedItemPosition == 1) View.VISIBLE else View.GONE }
            prefix.visibility = if (spinner.selectedItemPosition == 3) View.VISIBLE else View.GONE
            note.text = if (spinner.selectedItemPosition == 4)
                "更新下载自动使用 Wi-Fi 的系统代理；未配置时直连。无需填写主机和端口，不修改系统或游戏代理设置。需认证的代理可使用手动 HTTP 模式。"
                else "加速前缀会拼接 /https://github.com/…；不预置第三方服务。代理设置不改变游戏网络。"
            note.visibility = View.VISIBLE
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = visibility()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        visibility()
        val dialog = AlertDialog.Builder(this).setTitle("更新代理 · 仅影响更新下载")
            .setView(ScrollView(this).apply { addView(form) }).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val mode = modes[spinner.selectedItemPosition]
                    val number = port.text.toString().toIntOrNull() ?: 0
                    if (mode == "http" || mode == "socks") require(host.text.isNotBlank() && number in 1..65535) { "请填写有效的代理主机和端口" }
                    if (mode == "accelerator") UpdateTransport.requireHttps(prefix.text.toString().trim())
                    prefs.edit().putString("mode", mode).putString("host", host.text.toString().trim()).putInt("port", number)
                        .putString("user", if (mode == "socks") "" else user.text.toString()).putString("password", if (mode == "socks") "" else password.text.toString())
                        .putString("prefix", prefix.text.toString().trim()).commit()
                    dialog.dismiss(); message("更新网络设置已保存；可点击“检查更新 / 测试连接”验证")
                } catch (error: Exception) { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
    private fun downloadApk() {
        val found = release ?: return message("请先检查更新")
        val apk = found.json.optJSONObject("apk") ?: return message("本次发布没有 APK 更新")
        if (apk.getLong("versionCode") <= store.installedCode()) return message("客户端已是该版本或更新版本")
        AlertDialog.Builder(this).setMessage("下载客户端 APK，大小 ${apk.getLong("bytes") / 1048576} MiB？安装会交由系统确认。请在对局结束后安装。")
            .setPositiveButton("下载") { _, _ -> launch { net ->
                val temp = File(store.staging, "${apk.getString("sha256")}.apk.part")
                require(store.root.usableSpace > apk.getLong("bytes") + 32L * 1048576) { "APK 下载空间不足" }
                net.download(apk.getString("url"), temp, apk.getLong("bytes")) {
                    showProgress(TransferProgress(it, apk.getLong("bytes"), "客户端 APK · 下载中"))
                }
                showProgress(TransferProgress(apk.getLong("bytes"), apk.getLong("bytes"), "客户端 APK · 校验签名"))
                require(UpdateTrust.digest(temp) == apk.getString("sha256")) { temp.delete(); "APK SHA-256 校验失败，请重试" }
                validateApk(temp, apk)
                net.checkCancelled()
                require(temp.renameTo(File(store.root, "client.apk"))) { "无法保存 APK" }
                getSharedPreferences("client_updates", MODE_PRIVATE).edit().putString("verifiedApk", apk.toString()).commit()
                showProgress(TransferProgress(apk.getLong("bytes"), apk.getLong("bytes"), "客户端 APK 已验证"))
                message("APK 已验证。对局结束后点击“安装已验证的 APK”。")
            } }.setNegativeButton("取消", null).show()
    }
    @Suppress("DEPRECATION")
    private fun validateApk(file: File, expected: JSONObject) {
        val pm = packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES) ?: error("无法解析 APK")
        val installed = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == packageName && code == expected.getLong("versionCode") && code > store.installedCode()) { "APK 应用或版本不匹配" }
        val actual = archive.signatures?.map { UpdateTrust.digest(it.toByteArray()) }?.toSet() ?: emptySet()
        val current = installed.signatures?.map { UpdateTrust.digest(it.toByteArray()) }?.toSet() ?: emptySet()
        require(actual.isNotEmpty() && actual == current && expected.getString("certificateSha256") in actual) { "APK 签名与已安装应用不一致" }
    }
    private fun install() {
        val value = getSharedPreferences("client_updates", MODE_PRIVATE).getString("verifiedApk", null) ?: return message("尚未下载并验证新 APK")
        val file = File(store.root, "client.apk")
        AlertDialog.Builder(this).setMessage("安装会离开当前界面并可能关闭游戏，请确认当前没有进行中的对局。")
            .setPositiveButton("继续安装") { _, _ -> launch {
                val expected = JSONObject(value)
                require(file.isFile && file.length() == expected.getLong("bytes") && UpdateTrust.digest(file) == expected.getString("sha256")) { "安装包已损坏，请重新下载" }
                validateApk(file, expected)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                        message("请允许本应用安装更新，然后返回再次点击安装")
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://$packageName.updates/client.apk"), "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: Exception) { message("系统安装器不可用") }
                    }
                }
            } }.setNegativeButton("取消", null).show()
    }
    override fun onDestroy() { transport?.cancel(); worker.shutdownNow(); super.onDestroy() }
}
