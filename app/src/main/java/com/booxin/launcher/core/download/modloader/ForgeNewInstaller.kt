package com.booxin.launcher.core.download.modloader

import android.util.Log
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.Digests
import com.booxin.launcher.core.download.game.GameJsonParser
import com.booxin.launcher.core.download.game.LibraryDownloadHelper
import com.booxin.launcher.core.java.EmbeddedJavaRunner
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.net.FileDownloader
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** step is 0-based completed count; total is client processor count. */
typealias ForgeInstallProgressCallback = (message: String, step: Int, total: Int) -> Unit

/**
 * Run embedded Forge processors to generate client jars.
 */
object ForgeNewInstaller {
    private const val TAG = "ForgeNewInstaller"

    suspend fun install(
        java: InstalledJavaRuntime,
        installerJar: File,
        mcVersion: String,
        versionId: String,
        libraryDownloader: LibraryDownloadHelper = LibraryDownloadHelper(),
        onProgress: ForgeInstallProgressCallback = { _, _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(LauncherPaths.rootDir, "cache/forge/processor-${System.currentTimeMillis()}")
            tempDir.mkdirs()
            try {
                ZipFile(installerJar).use { zip ->
                    Log.i(TAG, "install $versionId from ${installerJar.name}")
                    val profileText = zip.readZipText("install_profile.json")
                        ?: error("Forge 安装包缺少 install_profile.json")
                    val profile = JSONObject(profileText)
                    val profileMc = profile.optString("minecraft")
                    if (profileMc.isNotBlank() && profileMc != mcVersion) {
                        error("Forge 安装包与 MC 版本不匹配: $profileMc != $mcVersion")
                    }

                    val profileLibraries = profile.optJSONArray("libraries") ?: JSONArray()
                    val processors = profile.optJSONArray("processors") ?: JSONArray()
                    val clientProcessors = (0 until processors.length())
                        .map { processors.getJSONObject(it) }
                        .filter { isClientProcessor(it) }
                    val totalSteps = clientProcessors.size.coerceAtLeast(1)
                    val roadmap = clientProcessors.mapIndexed { index, processor ->
                        "${index + 1}.${shortProcessorLabel(processor)}"
                    }.joinToString(" → ")

                    onProgress(
                        "Forge 安装流程：$roadmap\n准备：解压安装器内嵌库与补丁数据…",
                        0,
                        totalSteps
                    )
                    copyEmbeddedLibraries(zip, profileLibraries)
                    copyProfileMainJar(zip, profile.optJSONObject("path") ?: pathAsObject(profile.opt("path")))

                    onProgress(
                        "Forge 安装流程：$roadmap\n准备：下载安装工具依赖（ASM / renaming / patcher）…",
                        0,
                        totalSteps
                    )
                    // Do not LibraryFilter-dedupe by group:artifact — Forge processors need
                    // multiple versions (e.g. jopt-simple 5.0.4 + 6.0-alpha-3).
                    libraryDownloader.downloadLibraries(
                        profileLibraries,
                        mcVersion,
                        applyUpgrade = false
                    ) { done, total, _ ->
                        if (total > 0) {
                            onProgress(
                                "Forge 安装流程：$roadmap\n准备：下载安装工具依赖 $done / $total",
                                0,
                                totalSteps
                            )
                        }
                    }

                    val vars = buildProcessorVars(zip, profile, mcVersion, installerJar, tempDir)
                    var step = 0
                    for (processor in clientProcessors) {
                        step++
                        val detail = processorDetail(processor)
                        onProgress(
                            "工具步骤 $step/$totalSteps：${detail.title}\n${detail.hint}\n流程：$roadmap",
                            step - 1,
                            totalSteps
                        )
                        if (tryDownloadMojmaps(processor, vars, mcVersion, onStatus = { msg ->
                                onProgress(
                                    "工具步骤 $step/$totalSteps：${detail.title}\n$msg\n流程：$roadmap",
                                    step - 1,
                                    totalSteps
                                )
                            })
                        ) {
                            onProgress(
                                "工具步骤 $step/$totalSteps：${detail.title} ✓ 完成\n流程：$roadmap",
                                step,
                                totalSteps
                            )
                            continue
                        }
                        runProcessor(java, processor, vars, mcVersion, onStatus = { msg ->
                            onProgress(
                                "工具步骤 $step/$totalSteps：${detail.title}\n$msg\n流程：$roadmap",
                                step - 1,
                                totalSteps
                            )
                        })
                        onProgress(
                            "工具步骤 $step/$totalSteps：${detail.title} ✓ 完成\n流程：$roadmap",
                            step,
                            totalSteps
                        )
                    }

                    onProgress(
                        "写入 Forge 版本 JSON（$versionId）…\n随后还会下载 Forge 运行库",
                        totalSteps,
                        totalSteps
                    )
                    val versionJsonPath = profile.getString("json").trimStart('/')
                    val versionJsonText = zip.readZipText(versionJsonPath)
                        ?: error("Forge 安装包缺少版本 JSON: $versionJsonPath")
                    val versionJson = JSONObject(versionJsonText).put("id", versionId)
                    val versionDir = File(LauncherPaths.versionsDir, versionId).also { it.mkdirs() }
                    File(versionDir, "$versionId.json").writeText(versionJson.toString(2))
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }.onFailure { error ->
            Log.e(TAG, "install failed: ${error.message}", error)
        }
    }

    private fun pathAsObject(raw: Any?): JSONObject? = when (raw) {
        is JSONObject -> raw
        is String -> JSONObject().put("name", raw)
        else -> null
    }

    private fun copyEmbeddedLibraries(zip: ZipFile, libraries: JSONArray) {
        for (i in 0 until libraries.length()) {
            val lib = libraries.getJSONObject(i)
            val relPath = libraryRelPath(lib) ?: continue
            val entry = zip.getEntry("maven/$relPath") ?: continue
            val dest = File(LauncherPaths.librariesDir, relPath)
            if (dest.isFile && dest.length() > 0L) continue
            dest.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun copyProfileMainJar(zip: ZipFile, pathObj: JSONObject?) {
        if (pathObj == null) return
        val relPath = pathObj.optString("path").ifBlank {
            pathObj.optString("name").takeIf { it.isNotBlank() }?.let { GameJsonParser.mavenPath(it) }.orEmpty()
        }
        if (relPath.isBlank()) return
        val entry = zip.getEntry("maven/$relPath") ?: return
        val dest = File(LauncherPaths.librariesDir, relPath)
        dest.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun buildProcessorVars(
        zip: ZipFile,
        profile: JSONObject,
        mcVersion: String,
        installerJar: File,
        tempDir: File
    ): MutableMap<String, String> {
        val vars = mutableMapOf<String, String>()
        val data = profile.optJSONObject("data")
        if (data != null) {
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val datum = data.optJSONObject(key) ?: continue
                val raw = datum.optString("client")
                if (raw.isBlank()) continue
                vars[key] = parseLiteral(raw, emptyMap(), zip, tempDir) { path ->
                    extractInstallerEntry(zip, tempDir, path)
                } ?: continue
            }
        }
        val mcJar = File(LauncherPaths.versionsDir, "$mcVersion/$mcVersion.jar")
        if (!mcJar.isFile) error("缺少基础版本 client.jar: $mcVersion")
        vars["SIDE"] = "client"
        vars["MINECRAFT_JAR"] = mcJar.absolutePath
        // 必须是 MC 版本 id，不能是 jar 路径。
        vars["MINECRAFT_VERSION"] = mcVersion
        vars["ROOT"] = LauncherPaths.rootDir.absolutePath
        vars["INSTALLER"] = installerJar.absolutePath
        vars["LIBRARY_DIR"] = LauncherPaths.librariesDir.absolutePath
        return vars
    }

    private suspend fun runProcessor(
        java: InstalledJavaRuntime,
        processor: JSONObject,
        vars: Map<String, String>,
        mcVersion: String,
        onStatus: (String) -> Unit = {}
    ) {
        val outputs = mutableMapOf<String, String>()
        val outputsJson = processor.optJSONObject("outputs")
        if (outputsJson != null) {
            val keys = outputsJson.keys()
            while (keys.hasNext()) {
                val outKey = keys.next()
                val outVal = outputsJson.getString(outKey)
                val keyPath = parseLiteral(outKey, vars, null, null) { it }
                    ?: error("Forge processor 输出路径无效")
                val valueHash = parseLiteral(outVal, vars, null, null) { it }
                    ?: error("Forge processor 输出校验无效")
                outputs[keyPath] = valueHash
            }
        }

        var miss = false
        for ((path, sha1) in outputs) {
            val file = File(path)
            if (file.isFile && Digests.matchesSha1(file, sha1)) continue
            if (file.isFile) file.delete()
            miss = true
        }
        if (outputs.isNotEmpty() && !miss) {
            onStatus("输出已存在且校验通过，跳过本步")
            return
        }

        val jarDescriptor = when (val jarObj = processor.get("jar")) {
            is String -> jarObj
            is JSONObject -> jarObj.optString("path").ifBlank {
                jarObj.optString("name").ifBlank { error("Forge processor jar 无效") }
            }
            else -> error("Forge processor jar 无效")
        }
        val processorJar = artifactFile(jarDescriptor)
        if (!processorJar.isFile) error("缺少 processor 依赖: $jarDescriptor")

        val classpath = buildList {
            val cp = processor.optJSONArray("classpath")
            if (cp != null) {
                for (i in 0 until cp.length()) {
                    when (val item = cp.get(i)) {
                        is String -> {
                            val file = artifactFile(item)
                            if (!file.isFile) error("缺少 processor classpath: $item")
                            add(file.absolutePath)
                        }
                        is JSONObject -> {
                            val path = item.optString("path").ifBlank { item.optString("name") }
                            val file = artifactFile(path)
                            if (!file.isFile) error("缺少 processor classpath: $path")
                            add(file.absolutePath)
                        }
                    }
                }
            }
            add(processorJar.absolutePath)
        }

        val mainClass = JarFile(processorJar).use { jar ->
            jar.manifest?.mainAttributes?.getValue(Attributes.Name.MAIN_CLASS)?.trim()
        }.orEmpty().ifBlank { error("processor 缺少 Main-Class: $processorJar") }

        val args = processor.optJSONArray("args")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    add(
                        parseLiteral(arr.getString(i), vars, null, null) { it }
                            ?: error("Forge processor 参数无效")
                    )
                }
            }
        }.orEmpty()

        Log.i(TAG, "processor: $mainClass (${args.size} args)")
        val outputHint = outputs.keys.firstOrNull()?.let { File(it).name } ?: "输出 jar"
        onStatus("正在启动 Java 工具…\n目标：$outputHint")

        val command = buildList {
            add("-cp")
            add(classpath.joinToString(File.pathSeparator))
            add(mainClass)
            addAll(args)
        }
        val watchFiles = outputs.keys.map(::File)
        val exitCode = try {
            coroutineScope {
                val startedAt = System.currentTimeMillis()
                val runner = async(Dispatchers.IO) {
                    EmbeddedJavaRunner.run(
                        java = java,
                        workingDir = LauncherPaths.rootDir,
                        command = command
                    )
                }
                while (!runner.isCompleted) {
                    delay(3_000)
                    val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    val grown = watchFiles.firstOrNull { it.isFile && it.length() > 0L }
                    val sizeText = grown?.let { formatBytes(it.length()) } ?: "尚未写出"
                    onStatus(
                        "Java 工具运行中（已 ${elapsedSec}s）· 输出 $sizeText\n" +
                            "这一步在手机上可能要几分钟，请保持应用在前台"
                    )
                }
                runner.await()
            }
        } catch (error: Exception) {
            Log.e(TAG, "processor crashed: $mainClass", error)
            throw IllegalStateException("Forge processor 运行失败: ${error.message}", error)
        }
        if (exitCode != 0) {
            throw IllegalStateException("Forge processor 退出码 $exitCode（$mainClass）")
        }
        Log.i(TAG, "processor done: $mainClass")
        onStatus("工具已结束，正在校验输出…")

        for ((path, sha1) in outputs) {
            val file = File(path)
            if (!file.isFile) error("processor 未生成文件: $path")
            if (!Digests.matchesSha1(file, sha1)) {
                file.delete()
                error("processor 输出校验失败: ${file.name}")
            }
        }
        onStatus("校验通过：${outputs.keys.joinToString { File(it).name }}")
    }

    private suspend fun tryDownloadMojmaps(
        processor: JSONObject,
        vars: Map<String, String>,
        mcVersion: String,
        onStatus: (String) -> Unit = {}
    ): Boolean {
        val args = processor.optJSONArray("args") ?: return false
        val options = parseOptions(args, vars)
        if (options["task"] != "DOWNLOAD_MOJMAPS" || options["side"] != "client") return false
        val output = options["output"] ?: return false
        val version = options["version"] ?: mcVersion

        onStatus("正在读取 $version 的官方 mappings 地址…")
        val vanillaJson = File(LauncherPaths.versionsDir, "$version/$version.json")
        if (!vanillaJson.isFile) error("缺少版本 JSON: $version")
        val downloads = JSONObject(vanillaJson.readText()).optJSONObject("downloads")
            ?: error("版本 JSON 缺少 downloads")
        val mappings = downloads.optJSONObject("client_mappings")
            ?: error("版本 JSON 缺少 client_mappings")
        val url = mappings.optString("url")
        val sha1 = mappings.optString("sha1").ifBlank { null }
        if (url.isBlank()) error("client_mappings 缺少 url")

        val dest = File(output)
        dest.parentFile?.mkdirs()
        if (dest.isFile && Digests.matchesSha1(dest, sha1)) {
            onStatus("mappings 已缓存，跳过下载")
            return true
        }
        onStatus("正在下载官方 mappings…")
        FileDownloader().download(url, dest).getOrThrow()
        if (!Digests.matchesSha1(dest, sha1)) {
            dest.delete()
            error("mappings 校验失败")
        }
        onStatus("mappings 下载完成：${dest.name}")
        return true
    }

    private fun parseOptions(args: JSONArray, vars: Map<String, String>): Map<String, String> {
        val options = linkedMapOf<String, String>()
        var optionName: String? = null
        for (i in 0 until args.length()) {
            val arg = args.getString(i)
            if (arg.startsWith("--")) {
                optionName?.let { if (!options.containsKey(it)) options[it] = "" }
                optionName = arg.removePrefix("--")
            } else if (optionName != null) {
                options[optionName] = parseLiteral(arg, vars, null, null) { it }.orEmpty()
                optionName = null
            }
        }
        optionName?.let { if (!options.containsKey(it)) options[it] = "" }
        return options
    }

    private fun isClientProcessor(processor: JSONObject): Boolean {
        val sides = processor.optJSONArray("sides") ?: return true
        for (i in 0 until sides.length()) {
            if (sides.optString(i).equals("client", ignoreCase = true)) return true
        }
        return false
    }

    private fun processorJarDescriptor(processor: JSONObject): String {
        return when (val jarObj = processor.opt("jar")) {
            is String -> jarObj
            is JSONObject -> jarObj.optString("path").ifBlank { jarObj.optString("name") }
            else -> ""
        }
    }

    private data class ProcessorDetail(val title: String, val hint: String)

    private fun shortProcessorLabel(processor: JSONObject): String = processorDetail(processor).title

    private fun processorDetail(processor: JSONObject): ProcessorDetail {
        val args = processor.optJSONArray("args")
        if (args != null) {
            for (i in 0 until args.length()) {
                if (args.optString(i) == "DOWNLOAD_MOJMAPS") {
                    return ProcessorDetail(
                        "下载官方 mappings",
                        "从 Mojang 拉取混淆对照表，通常很快"
                    )
                }
            }
        }
        val descriptor = processorJarDescriptor(processor).lowercase()
        return when {
            "fart" in descriptor || "renaming" in descriptor -> ProcessorDetail(
                "重命名 MC jar",
                "把原版 client.jar 转成 official 映射名；约 2–5 分钟，属正常"
            )
            "binarypatcher" in descriptor || "binpatch" in descriptor -> ProcessorDetail(
                "应用 Forge 补丁",
                "生成 forge-*-client.jar；约 1–3 分钟。完成后会立刻进入写版本信息"
            )
            descriptor.isBlank() -> ProcessorDetail("运行安装工具", "正在执行 Forge 安装器子步骤")
            else -> ProcessorDetail(
                descriptor.substringAfter(':').substringAfterLast('.'),
                "正在执行 Forge 安装器子步骤"
            )
        }
    }

    private fun processorLabel(processor: JSONObject): String {
        val detail = processorDetail(processor)
        return "${detail.title}（${detail.hint}）"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return "%.1fKB".format(bytes / 1024.0)
        return "%.1fMB".format(bytes / (1024.0 * 1024.0))
    }

    private fun libraryRelPath(lib: JSONObject): String? {
        val artifact = lib.optJSONObject("downloads")?.optJSONObject("artifact")
        val path = artifact?.optString("path")?.ifBlank { null }
        if (path != null) return path
        val name = lib.optString("name").ifBlank { null } ?: return null
        return GameJsonParser.mavenPath(name)
    }

    private fun artifactFile(descriptor: String): File {
        val path = if (descriptor.contains('/')) {
            descriptor
        } else {
            GameJsonParser.mavenPath(descriptor)
        }
        return File(LauncherPaths.librariesDir, path)
    }

    private fun extractInstallerEntry(zip: ZipFile, tempDir: File, path: String): String {
        val normalized = path.trimStart('/')
        val entry = zip.getEntry(normalized)
            ?: zip.getEntry(path)
            ?: error("Forge 安装包缺少数据文件: $path")
        val suffix = entry.name.substringAfterLast('.', "tmp")
        val dest = File.createTempFile("forge-data-", ".$suffix", tempDir)
        zip.getInputStream(entry).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest.absolutePath
    }

    private fun parseLiteral(
        literal: String,
        vars: Map<String, String>,
        zip: ZipFile?,
        tempDir: File?,
        plainConverter: (String) -> String = { it }
    ): String? {
        val trimmed = literal.trim()
        if (isSurrounded(trimmed, "{", "}")) {
            return vars[trimmed.substring(1, trimmed.length - 1)]
        }
        if (isSurrounded(trimmed, "'", "'")) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        if (isSurrounded(trimmed, "[", "]")) {
            val descriptor = trimmed.substring(1, trimmed.length - 1)
            return artifactFile(descriptor).absolutePath
        }
        val replaced = replaceTokens(vars, trimmed)
        if ((replaced.startsWith("/") || replaced.startsWith("data/")) && zip != null && tempDir != null) {
            return extractInstallerEntry(zip, tempDir, replaced)
        }
        return plainConverter(replaced)
    }

    private fun isSurrounded(value: String, prefix: String, suffix: String): Boolean {
        return value.length >= prefix.length + suffix.length &&
            value.startsWith(prefix) &&
            value.endsWith(suffix)
    }

    private fun replaceTokens(vars: Map<String, String>, value: String): String {
        val buf = StringBuilder()
        var index = 0
        while (index < value.length) {
            when (val char = value[index]) {
                '\\' -> {
                    require(index < value.lastIndex) { "非法转义: $value" }
                    buf.append(value[++index])
                }
                '{', '\'' -> {
                    val endQuote = if (char == '{') '}' else '\''
                    val key = StringBuilder()
                    var cursor = index + 1
                    while (cursor < value.length) {
                        when (val next = value[cursor]) {
                            '\\' -> {
                                require(cursor < value.lastIndex) { "非法转义: $value" }
                                key.append(value[++cursor])
                            }
                            endQuote -> {
                                index = cursor
                                break
                            }
                            else -> key.append(next)
                        }
                        cursor++
                    }
                    if (char == '\'') {
                        buf.append(key)
                    } else {
                        buf.append(vars[key.toString()] ?: error("缺少变量: {$key}"))
                    }
                }
                else -> buf.append(char)
            }
            index++
        }
        return buf.toString()
    }

    private fun ZipFile.readZipText(path: String): String? {
        val entry = getEntry(path) ?: getEntry(path.trimStart('/')) ?: return null
        return getInputStream(entry).bufferedReader().readText()
    }
}
