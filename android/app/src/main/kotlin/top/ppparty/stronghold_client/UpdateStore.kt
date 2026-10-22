package top.ppparty.stronghold_client

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Signed immutable indices and content-addressed blobs. An open game pins one index. */
class UpdateStore(private val context: Context) {
    val root = File(context.filesDir, "updates").apply { mkdirs() }
    private val blobs = File(root, "blobs").apply { mkdirs() }
    val staging = File(root, "staging").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("client_updates", Context.MODE_PRIVATE)
    data class Release(val envelope: ByteArray, val json: JSONObject) {
        val entries: JSONObject get() = json.getJSONObject("entries")
        val sequence: Long get() = json.getLong("sequence")
        val label: String get() = json.getString("resourceVersion")
    }
    fun parse(bytes: ByteArray): Release {
        require(bytes.size <= 24 * 1024 * 1024) { "更新清单过大" }
        val outer = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = Base64.decode(outer.getString("payload"), Base64.DEFAULT)
        val signature = Base64.decode(outer.getString("signature"), Base64.DEFAULT)
        val pem = context.assets.open("update-public.pem").bufferedReader().use { it.readText() }
        val der = Base64.decode(pem.replace(Regex("-----[^-]+-----"), "").replace(Regex("\\s"), ""), Base64.DEFAULT)
        require(UpdateTrust.verify(payload, signature, der)) { "更新清单签名无效，已拒绝" }
        val json = JSONObject(String(payload, Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("packageId") == context.packageName && json.getString("channel") == "preview") { "更新渠道或应用不匹配" }
        require(json.getLong("sequence") > 0 && json.getString("resourceVersion").length in 1..128) { "更新版本无效" }
        require(json.getInt("minClientCode") > 0) { "客户端兼容版本无效" }
        val entries = json.getJSONObject("entries")
        require(entries.length() <= 100000) { "资源清单过大" }
        for (path in entries.keys()) {
            require(UpdateTrust.artPath(path)) { "更新包含非素材路径：$path" }
            val entry = entries.getJSONObject(path)
            require(UpdateTrust.validHash(entry.getString("sha256")) && entry.getLong("bytes") in 0..512_000_000L) { "资源摘要或大小无效" }
            val hashes = entry.getJSONArray("hashes")
            require(hashes.length() in 1..100 && (0 until hashes.length()).all { hashes.getString(it).matches(Regex("[0-9a-f]{16,64}")) }) { "资源 URL 哈希无效" }
        }
        val packs = json.getJSONArray("packs")
        require(packs.length() <= 10000) { "资源分包过多" }
        val covered = HashSet<String>()
        for (i in 0 until packs.length()) {
            val pack = packs.getJSONObject(i)
            validateDownload(pack)
            val files = pack.getJSONArray("files")
            for (j in 0 until files.length()) {
                val hash = files.getString(j)
                require(UpdateTrust.validHash(hash) && covered.add(hash)) { "分包资源重复或无效" }
            }
        }
        json.optJSONObject("apk")?.let { apk ->
            validateDownload(apk)
            require(UpdateTrust.validHash(apk.getString("certificateSha256"))) { "APK 证书摘要无效" }
        }
        return Release(bytes, json)
    }
    private fun validateDownload(entry: JSONObject) {
        UpdateTransport.requireHttps(entry.getString("url"))
        require(UpdateTrust.validHash(entry.getString("sha256")) && entry.getLong("bytes") in 1..2_000_000_000L) { "下载摘要或大小无效" }
    }
    @Suppress("DEPRECATION")
    fun installedCode(): Long = context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }
    fun active(): Release? = read("active.json")
    private fun read(name: String): Release? = try {
        AtomicFile(File(root, name)).openRead().use { parse(it.readBytes()) }
    } catch (_: Exception) { null }
    private fun write(name: String, bytes: ByteArray) {
        val file = AtomicFile(File(root, name))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun isNew(release: Release) = release.sequence > maxOf(prefs.getLong("highestSequence", 0), active()?.sequence ?: 0)
    fun rollback() {
        val previous = read("previous.json")
        if (previous == null) {
            AtomicFile(File(root, "active.json")).delete()
        } else {
            require(complete(previous)) { "上一版资源缺失" }
            write("active.json", previous.envelope)
        }
    }
    fun file(entry: JSONObject): File? = File(blobs, entry.getString("sha256")).takeIf { it.isFile && it.length() == entry.getLong("bytes") }
    private fun embedded(): Set<String> = context.assets.open("flutter_assets/assets/game/index.json").bufferedReader().use {
        val entries = JSONObject(it.readText()).getJSONObject("entries")
        entries.keys().asSequence().map { path -> entries.getJSONObject(path).getString("sha256") }.toSet()
    }
    private fun complete(release: Release): Boolean {
        val embedded = embedded()
        return release.entries.keys().asSequence().all { path ->
            val entry = release.entries.getJSONObject(path)
            entry.getString("sha256") in embedded || file(entry) != null
        }
    }
    fun apply(release: Release, transport: UpdateDownload, progress: (String) -> Unit) {
        require(release.json.getInt("minClientCode") <= installedCode()) { "请先下载并升级客户端 APK，再启用此素材版本" }
        require(isNew(release) || active()?.sequence == release.sequence) { "已拒绝旧版更新清单；需要回退请使用回退按钮" }
        if (active()?.sequence == release.sequence && complete(release)) { progress("此素材版本已经启用"); return }
        val embedded = embedded()
        val needed = HashMap<String, Long>()
        for (path in release.entries.keys()) {
            val entry = release.entries.getJSONObject(path)
            val hash = entry.getString("sha256")
            if (hash !in embedded && file(entry) == null) needed[hash] = entry.getLong("bytes")
        }
        val packs = release.json.getJSONArray("packs")
        val selected = (0 until packs.length()).map { packs.getJSONObject(it) }.filter { pack ->
            val files = pack.getJSONArray("files")
            (0 until files.length()).any { files.getString(it) in needed }
        }
        val requiredSpace = needed.values.sum() + selected.sumOf { it.getLong("bytes") } + 32L * 1024 * 1024
        require(root.usableSpace >= requiredSpace) { "存储空间不足，需约 ${requiredSpace / 1048576} MiB 可用空间" }
        for ((number, pack) in selected.withIndex()) {
            transport.checkCancelled()
            val hash = pack.getString("sha256")
            val temp = File(staging, "$hash.part")
            transport.download(pack.getString("url"), temp, pack.getLong("bytes")) {
                progress("资源包 ${number + 1}/${selected.size}：${it / 1048576} / ${pack.getLong("bytes") / 1048576} MiB")
            }
            if (UpdateTrust.digest(temp) != hash) { temp.delete(); error("资源包 SHA-256 不符，已拒绝；请重试") }
            val allowed = pack.getJSONArray("files").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            ZipFile(temp).use { zip ->
                val names = HashSet<String>()
                for (entry in zip.entries()) {
                    transport.checkCancelled()
                    require(!entry.isDirectory && entry.name in allowed && names.add(entry.name)) { "分包包含未知或重复文件" }
                    val expected = needed[entry.name] ?: continue
                    require(entry.size == expected) { "解包大小不符" }
                    val output = File(staging, "${entry.name}.blob")
                    try {
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(output).use { out ->
                                var total = 0L; val buffer = ByteArray(131072)
                                while (true) {
                                    transport.checkCancelled()
                                    val n = input.read(buffer); if (n < 0) break
                                    total += n; require(total <= expected) { "解包超出大小限制" }; out.write(buffer, 0, n)
                                }
                                out.fd.sync(); require(total == expected) { "资源内容不完整" }
                            }
                        }
                        require(UpdateTrust.digest(output) == entry.name) { "资源 SHA-256 无效" }
                        require(output.renameTo(File(blobs, entry.name))) { "无法保存资源" }
                        needed.remove(entry.name)
                    } finally { output.delete() }
                }
                require(names == allowed) { "分包文件列表不符" }
            }
            temp.delete()
        }
        require(needed.isEmpty() && complete(release)) { "更新资源缺失，保留现有版本" }
        transport.checkCancelled()
        active()?.takeIf { it.sequence != release.sequence }?.let { write("previous.json", it.envelope) }
        write("active.json", release.envelope)
        prefs.edit().putLong("highestSequence", maxOf(release.sequence, prefs.getLong("highestSequence", 0))).commit()
        progress("素材 ${release.label} 已就绪；下次进入游戏生效，当前对局不切换")
    }
}
