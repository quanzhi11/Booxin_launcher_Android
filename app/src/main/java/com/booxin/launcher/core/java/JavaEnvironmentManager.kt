package com.booxin.launcher.core.java

import android.system.Os
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.net.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Owns Java environment discovery, download, extract, and selection.
 *
 * Layout mirrors FCL runtime java roots:
 * ```
 * filesDir/minecraft/java/
 *   java-8/
 *   java-17/
 *   java-21/
 *   java-25/
 * filesDir/minecraft/cache/java/
 *   jreN-pojav.zip
 * ```
 */
class JavaEnvironmentManager(
    private val downloader: FileDownloader = FileDownloader()
) {

    private val mutex = Mutex()

    private val _progress = MutableStateFlow<JavaInstallProgress?>(null)
    val progress: StateFlow<JavaInstallProgress?> = _progress.asStateFlow()

    fun deviceAbi(): JavaAbi = JavaAbi.current()

    fun listCatalog(): List<JavaRuntimePackage> = JavaRuntimeCatalog.packagesForDevice()

    fun listInstalled(): List<InstalledJavaRuntime> {
        return listCatalog().mapNotNull { pkg ->
            locateInstalled(pkg.componentId, pkg.majorVersion)
        }
    }

    fun isInstalled(componentId: String): Boolean {
        return locateInstalled(componentId, majorFromId(componentId)) != null
    }

    /** True if a runtime directory exists (including incomplete / broken installs). */
    fun isPresent(componentId: String): Boolean {
        return LauncherPaths.javaRuntimeDir(componentId).exists()
    }

    fun findInstalled(majorVersion: Int): InstalledJavaRuntime? {
        val id = "java-$majorVersion"
        return locateInstalled(id, majorVersion)
    }

    /**
     * Ensures a Java runtime suitable for [mcVersionId] is present.
     * Downloads + extracts when missing.
     */
    suspend fun ensureForMinecraft(mcVersionId: String): Result<InstalledJavaRuntime> {
        val major = MinecraftJavaRequirement.requiredMajor(mcVersionId)
        return ensureMajor(major)
    }

    suspend fun ensureMajor(majorVersion: Int): Result<InstalledJavaRuntime> {
        findInstalled(majorVersion)?.let { runtime ->
            return runCatching {
                withContext(Dispatchers.IO) {
                    finalizeRuntimeHome(runtime.homeDir)
                }
                findInstalled(majorVersion)
                    ?: error("运行时修复后未找到可用 java")
            }
        }
        val pkg = JavaRuntimeCatalog.find(majorVersion)
            ?: return Result.failure(
                IllegalStateException("当前 ABI(${deviceAbi().packageToken}) 没有 Java $majorVersion 的下载源")
            )
        return install(pkg).mapCatching {
            findInstalled(majorVersion)
                ?: error("安装完成但未找到可用 java 可执行文件")
        }
    }

    suspend fun install(componentId: String): Result<File> {
        val pkg = JavaRuntimeCatalog.findByComponentId(componentId)
            ?: return Result.failure(IllegalArgumentException("未知组件: $componentId"))
        return install(pkg)
    }

    suspend fun install(pkg: JavaRuntimePackage): Result<File> = mutex.withLock {
        runCatching {
            emit(pkg.componentId, JavaInstallState.DOWNLOADING, message = "开始下载 ${pkg.displayName}")

            val cacheFile = File(LauncherPaths.javaCacheDir, pkg.fileName)
            val archive = downloadWithFallback(pkg, cacheFile)

            if (!pkg.sha256.isNullOrBlank()) {
                val actual = sha256(archive)
                if (!actual.equals(pkg.sha256, ignoreCase = true)) {
                    throw IllegalStateException("校验失败: expected ${pkg.sha256}, got $actual")
                }
            }

            emit(pkg.componentId, JavaInstallState.EXTRACTING, message = "正在解压 ${pkg.displayName}")
            val targetDir = LauncherPaths.javaRuntimeDir(pkg.componentId)
            val lowerName = archive.name.lowercase()
            when {
                lowerName.endsWith(".zip") && pkg.packageKind == JavaPackageKind.POJAV_SPLIT_ZIP ->
                    ArchiveExtractor.installPojavSplit(archive, targetDir, pkg.abi)
                else -> ArchiveExtractor.extract(archive, targetDir)
            }

            emit(pkg.componentId, JavaInstallState.EXTRACTING, message = "正在解压 Pack200…")
            finalizeRuntimeHome(targetDir)

            val installed = locateInstalled(pkg.componentId, pkg.majorVersion)
                ?: throw IllegalStateException("解压后未找到 bin/java")

            emit(
                pkg.componentId,
                JavaInstallState.INSTALLED,
                message = "已安装到 ${installed.homeDir.absolutePath}"
            )
            installed.homeDir
        }.onFailure { error ->
            emit(
                pkg.componentId,
                JavaInstallState.FAILED,
                message = error.message ?: "安装失败"
            )
        }
    }

    private suspend fun downloadWithFallback(pkg: JavaRuntimePackage, cacheFile: File): File {
        val urls = pkg.downloadUrls.ifEmpty {
            listOfNotNull(pkg.downloadUrl, pkg.fallbackUrl)
        }
        var lastError: Throwable? = null
        for ((index, url) in urls.withIndex()) {
            val label = when {
                index == 0 -> "下载 ${pkg.displayName}"
                url.contains("gitee.com", ignoreCase = true) -> "Gitee 备用源 ${pkg.displayName}"
                else -> "备用源 ${index + 1} ${pkg.displayName}"
            }
            if (index > 0) {
                emit(pkg.componentId, JavaInstallState.DOWNLOADING, message = "上一源失败，尝试：$label")
            }
            val target = if (index == 0) {
                cacheFile
            } else {
                val name = url.substringAfterLast('/').ifBlank { cacheFile.name }
                File(LauncherPaths.javaCacheDir, name)
            }
            val result = downloader.download(url, target) { downloaded, total ->
                _progress.value = JavaInstallProgress(
                    componentId = pkg.componentId,
                    state = JavaInstallState.DOWNLOADING,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = label
                )
            }
            if (result.isSuccess) return result.getOrThrow()
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("下载失败：无可用源")
    }

    suspend fun delete(componentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = LauncherPaths.javaRuntimeDir(componentId)
            if (dir.exists()) {
                if (!dir.deleteRecursively()) {
                    error("删除失败: ${dir.absolutePath}")
                }
            }
            emit(componentId, JavaInstallState.NOT_INSTALLED, message = "已卸载 $componentId")
            Unit
        }.onFailure { error ->
            emit(
                componentId,
                JavaInstallState.FAILED,
                message = error.message ?: "卸载失败"
            )
        }
    }

    fun statusText(): String {
        val abi = deviceAbi().packageToken
        val installed = listInstalled()
        return if (installed.isEmpty()) {
            "ABI: $abi · 未安装 Java 运行时"
        } else {
            val names = installed.joinToString { "Java ${it.majorVersion}" }
            "ABI: $abi · 已安装: $names"
        }
    }

    private fun locateInstalled(componentId: String, majorVersion: Int): InstalledJavaRuntime? {
        val home = LauncherPaths.javaRuntimeDir(componentId)
        if (!home.exists()) return null
        // Pack200 not finished yet — treat as incomplete so install/finalize can finish it.
        if (Pack200Unpacker.needsUnpack(home)) return null
        val binary = findJavaBinary(home) ?: return null
        return InstalledJavaRuntime(
            componentId = componentId,
            majorVersion = majorVersion,
            homeDir = home,
            javaBinary = binary
        )
    }

    private fun findJavaBinary(home: File): File? {
        val candidates = listOf(
            File(home, "bin/java"),
            File(home, "bin/java.exe"),
            File(home, "jre/bin/java")
        )
        candidates.firstOrNull { it.exists() && it.isFile }?.let { return it }

        val libJvm = home.walkTopDown()
            .maxDepth(6)
            .firstOrNull { it.name == "libjvm.so" }
            ?: return null
        val binJava = File(libJvm.parentFile?.parentFile, "bin/java")
        return binJava.takeIf { it.exists() } ?: File(home, "bin/java").takeIf { it.exists() }
    }

    /**
     * chmod + unpack pack200 jars so HotSpot can load `rt.jar` / bootstrap classes.
     */
    private fun finalizeRuntimeHome(home: File) {
        markJavaExecutable(home)
        if (Pack200Unpacker.needsUnpack(home)) {
            Pack200Unpacker.unpackAll(home)
            markJavaExecutable(home)
        }
    }

    private fun markJavaExecutable(home: File) {
        // Directories need +x to traverse; .so / bin tools need +rx for dlopen/linker.
        home.walkTopDown().forEach { file ->
            val needsExec = file.isDirectory ||
                file.name == "java" ||
                file.name == "unpack200" ||
                file.name.endsWith(".so") ||
                file.parentFile?.name == "bin"
            if (!needsExec) return@forEach
            runCatching {
                Os.chmod(file.absolutePath, 493) // 0755
            }.recoverCatching {
                file.setReadable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    private fun majorFromId(componentId: String): Int {
        return componentId.substringAfter("java-").toIntOrNull() ?: -1
    }

    private fun emit(
        componentId: String,
        state: JavaInstallState,
        downloaded: Long = 0L,
        total: Long = -1L,
        message: String
    ) {
        _progress.value = JavaInstallProgress(
            componentId = componentId,
            state = state,
            downloadedBytes = downloaded,
            totalBytes = total,
            message = message
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
