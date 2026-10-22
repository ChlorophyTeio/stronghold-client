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
    fun checked(): Release? = read("checked.json")
    fun remember(release: Release) {
        val previous = checked()
        if (release.sequence >= maxOf(previous?.sequence ?: 0, prefs.getLong("highestSequence", 0)) &&
            previous?.envelope?.contentEquals(release.envelope) != true) write("checked.json", release.envelope)
    }
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
    /** Import signed bundles, individual release packs, or raw art matched to a trusted index. */
    fun importZip(archive: File, fallback: Release?, checkCancelled: () -> Unit,
                  transfer: (TransferProgress) -> Unit): String {
        ZipImportRules.validate(archive)
        ZipFile(archive).use { zip ->
            val names = HashSet<String>()
            for (entry in zip.entries()) {
                val parts = entry.name.trimEnd('/').split('/')
                require(!entry.name.startsWith('/') && parts.none { it == "." || it == ".." } &&
                    !entry.name.contains('\\') && !entry.name.contains('\u0000') && names.add(entry.name)) { "ZIP 包含不安全或重复路径" }
            }
            val manifest = zip.getEntry("update-preview.json")
            val bundledRelease = if (manifest != null) {
                require(manifest.size in 1..24L * 1024 * 1024) { "ZIP 签名清单大小无效" }
                val bytes = zip.getInputStream(manifest).use { input ->
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled(); val count = input.read(buffer); if (count < 0) break
                        require(result.size() + count <= 24 * 1024 * 1024) { "ZIP 签名清单过大" }
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
                parse(bytes)
            } else null
            // Reuse older archives against the newest trusted index; never activate
            // an older index merely because it was embedded in an imported ZIP.
            val release = listOfNotNull(bundledRelease, fallback, active(), checked()).maxByOrNull { it.sequence }
                ?: error("请先检查更新获取签名素材索引，或选择含 update-preview.json 的完整离线包")
            require(release.json.getInt("minClientCode") <= installedCode()) { "请先升级客户端" }
            require(isNew(release) || active()?.sequence == release.sequence) { "素材包版本过旧；回退请使用回退按钮" }
            val embedded = embedded()
            val needed = HashMap<String, Long>()
            for (path in release.entries.keys()) {
                val entry = release.entries.getJSONObject(path)
                if (entry.getString("sha256") !in embedded && file(entry) == null)
                    needed[entry.getString("sha256")] = entry.getLong("bytes")
            }
            val initial = needed.size
            val packs = release.json.getJSONArray("packs")
            val byHash = HashMap<String, JSONObject>()
            for (i in 0 until packs.length()) byHash[packs.getJSONObject(i).getString("sha256")] = packs.getJSONObject(i)
            val digestNames = names.filter { UpdateTrust.validHash(it) }
            if (manifest == null && digestNames.isNotEmpty() && digestNames.size == names.size) {
                val hash = UpdateTrust.digest(archive)
                val pack = byHash[hash] ?: error("ZIP 与签名索引中的分包不匹配")
                require(archive.length() == pack.getLong("bytes")) { "ZIP 大小不符" }
                val contents = pack.getJSONArray("files")
                val required = (0 until contents.length()).sumOf { needed[contents.getString(it)] ?: 0L }
                require(root.usableSpace >= required + 32L * 1024 * 1024) { "导入空间不足" }
                transfer(TransferProgress(0, archive.length(), "校验与导入素材分包"))
                extractPack(archive, pack, needed, checkCancelled)
                transfer(TransferProgress(archive.length(), archive.length(), "分包导入完成"))
            } else {
                val files = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                val known = files.filter { entry ->
                    val hash = entry.name.removePrefix("resources-").removeSuffix(".zip")
                    (entry.name == "resources-$hash.zip" && hash in byHash) || release.entries.has("/${entry.name}")
                }
                require(known.isNotEmpty()) { "ZIP 中未找到当前签名索引对应的素材" }
                val largestPack = byHash.values.maxOfOrNull { it.getLong("bytes") } ?: 0
                require(root.usableSpace >= needed.values.sum() + largestPack + 32L * 1024 * 1024) { "导入空间不足" }
                val total = known.sumOf { it.size.coerceAtLeast(0) }
                var processed = 0L
                for ((number, item) in known.withIndex()) {
                    checkCancelled()
                    val hash = item.name.removePrefix("resources-").removeSuffix(".zip")
                    if (item.name == "resources-$hash.zip" && hash in byHash) {
                        val pack = byHash.getValue(hash)
                        val packFiles = pack.getJSONArray("files")
                        if ((0 until packFiles.length()).any { packFiles.getString(it) in needed }) {
                            require(item.size == pack.getLong("bytes")) { "内层分包大小不符" }
                            val temp = File(staging, "import-$hash.zip")
                            try {
                                zip.getInputStream(item).use { input -> copyImport(input, temp, item.size, checkCancelled) { bytes ->
                                    transfer(TransferProgress(processed + bytes, total, "导入分包 ${number + 1}/${known.size}"))
                                } }
                                require(UpdateTrust.digest(temp) == hash) { "导入分包 SHA-256 不符" }
                                extractPack(temp, pack, needed, checkCancelled)
                            } finally { temp.delete() }
                        }
                    } else {
                        val entry = release.entries.getJSONObject("/${item.name}")
                        val expectedHash = entry.getString("sha256")
                        if (expectedHash in needed) {
                            require(item.size == entry.getLong("bytes")) { "素材大小与签名索引不符" }
                            val temp = File(staging, "import-$expectedHash.blob")
                            try {
                                zip.getInputStream(item).use { input -> copyImport(input, temp, item.size, checkCancelled) {} }
                                require(UpdateTrust.digest(temp) == expectedHash) { "素材 SHA-256 与签名索引不符" }
                                require(temp.renameTo(File(blobs, expectedHash))) { "无法保存导入素材" }
                                needed.remove(expectedHash)
                            } finally { temp.delete() }
                        }
                    }
                    processed += item.size.coerceAtLeast(0)
                    transfer(TransferProgress(processed, total, "导入文件 ${number + 1}/${known.size}"))
                }
            }
            checkCancelled()
            if (complete(release)) {
                active()?.takeIf { it.sequence != release.sequence }?.let { write("previous.json", it.envelope) }
                write("active.json", release.envelope)
                prefs.edit().putLong("highestSequence", maxOf(release.sequence, prefs.getLong("highestSequence", 0))).commit()
                return "素材 ${release.label} 已导入并启用；重新进入游戏生效"
            }
            return "已导入 ${initial - needed.size} 项素材，仍缺 ${needed.size} 项；可继续导入其他分包或下载补齐，现有版本保持"
        }
    }
    private fun copyImport(input: java.io.InputStream, target: File, expected: Long,
                           checkCancelled: () -> Unit, progress: (Long) -> Unit) {
        java.io.FileOutputStream(target).use { output ->
            var total = 0L; val buffer = ByteArray(131072)
            while (true) {
                checkCancelled(); val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= expected) { "导入内容超出签名大小限制" }
                output.write(buffer, 0, count); progress(total)
            }
            require(total == expected) { "导入内容不完整" }; output.fd.sync()
        }
    }
    @JvmOverloads
    fun apply(release: Release, transport: UpdateDownload, progress: (String) -> Unit,
              transfer: ((TransferProgress) -> Unit)? = null) {
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
        val covered = HashSet<String>()
        selected.forEach { pack ->
            val files = pack.getJSONArray("files")
            for (i in 0 until files.length()) covered.add(files.getString(i))
        }
        require(needed.keys.all { it in covered }) { "当前清单缺少轻量版所需素材，请重新检查更新" }
        val inFlightSpace = selected.map { it.getLong("bytes") }.sortedDescending().take(4).sum()
        val requiredSpace = needed.values.sum() + inFlightSpace + 32L * 1024 * 1024
        require(root.usableSpace >= requiredSpace) { "存储空间不足，需约 ${requiredSpace / 1048576} MiB 可用空间" }
        val totalBytes = selected.sumOf { it.getLong("bytes") }
        val amounts = LongArray(selected.size) { index ->
            val pack = selected[index]
            File(staging, "${pack.getString("sha256")}.part").length().coerceIn(0, pack.getLong("bytes"))
        }
        val progressLock = Any()
        val finished = java.util.concurrent.atomic.AtomicInteger()
        fun report(index: Int, bytes: Long, stage: String) = synchronized(progressLock) {
            amounts[index] = bytes.coerceIn(0, selected[index].getLong("bytes"))
            transfer?.invoke(TransferProgress(amounts.sum(), totalBytes, stage))
        }
        transfer?.invoke(TransferProgress(amounts.sum(), totalBytes, "准备 4 路并发下载 ${selected.size} 个素材分包"))
        ParallelDownloads.run(selected.indices.toList(), transport::checkCancelled,
            { (transport as? UpdateTransport)?.cancel() }, download = { index ->
                val pack = selected[index]
                val hash = pack.getString("sha256")
                val temp = File(staging, "$hash.part")
                transport.download(pack.getString("url"), temp, pack.getLong("bytes")) {
                    report(index, it, "素材 4 路并发下载 · 已就绪 ${finished.get()}/${selected.size} 包")
                }
                if (UpdateTrust.digest(temp) != hash) { temp.delete(); error("资源包 SHA-256 不符，已拒绝；请重试") }
            }, consume = { index ->
            val pack = selected[index]
            val hash = pack.getString("sha256")
            val temp = File(staging, "$hash.part")
            report(index, pack.getLong("bytes"), "素材分包 ${finished.get() + 1}/${selected.size} · 校验与解包")
            extractPack(temp, pack, needed, transport::checkCancelled)
            temp.delete()
            finished.incrementAndGet()
            progress("素材分包已就绪 ${finished.get()}/${selected.size}")
        })
        require(needed.isEmpty() && complete(release)) { "更新资源缺失，保留现有版本" }
        transport.checkCancelled()
        active()?.takeIf { it.sequence != release.sequence }?.let { write("previous.json", it.envelope) }
        write("active.json", release.envelope)
        prefs.edit().putLong("highestSequence", maxOf(release.sequence, prefs.getLong("highestSequence", 0))).commit()
        transfer?.invoke(TransferProgress(totalBytes, totalBytes, "素材已就绪"))
        progress("素材 ${release.label} 已就绪；下次进入游戏生效，当前对局不切换")
    }
    private fun extractPack(temp: File, pack: JSONObject, needed: MutableMap<String, Long>, checkCancelled: () -> Unit) {
            val allowed = pack.getJSONArray("files").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            ZipFile(temp).use { zip ->
                val names = HashSet<String>()
                for (entry in zip.entries()) {
                    checkCancelled()
                    require(!entry.isDirectory && entry.name in allowed && names.add(entry.name)) { "分包包含未知或重复文件" }
                    val expected = needed[entry.name] ?: continue
                    require(entry.size == expected) { "解包大小不符" }
                    val output = File(staging, "${entry.name}.blob")
                    try {
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(output).use { out ->
                                var total = 0L; val buffer = ByteArray(131072)
                                while (true) {
                                    checkCancelled()
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
    }

}
