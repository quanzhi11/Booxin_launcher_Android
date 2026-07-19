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
            markJavaExecutable(runtime.homeDir)
            return Result.success(runtime)
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

            markJavaExecutable(targetDir)

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
        val primary = downloader.download(pkg.downloadUrl, cacheFile) { downloaded, total ->
            _progress.value = JavaInstallProgress(
                componentId = pkg.componentId,
                state = JavaInstallState.DOWNLOADING,
                downloadedBytes = downloaded,
                totalBytes = total,
                message = "下载 ${pkg.displayName}"
            )
        }
        if (primary.isSuccess) return primary.getOrThrow()

        val fallbackUrl = pkg.fallbackUrl
            ?: throw primary.exceptionOrNull() ?: IllegalStateException("下载失败")

        emit(pkg.componentId, JavaInstallState.DOWNLOADING, message = "主源失败，尝试备用源…")
        val fallbackName = fallbackUrl.substringAfterLast('/')
        val fallbackFile = File(LauncherPaths.javaCacheDir, fallbackName)
        return downloader.download(fallbackUrl, fallbackFile) { downloaded, total ->
            _progress.value = JavaInstallProgress(
                componentId = pkg.componentId,
                state = JavaInstallState.DOWNLOADING,
                downloadedBytes = downloaded,
                totalBytes = total,
                message = "备用源下载 ${pkg.displayName}"
            )
        }.getOrElse { throw it }
    }

    suspend fun delete(componentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = LauncherPaths.javaRuntimeDir(componentId)
            if (dir.exists()) dir.deleteRecursively()
            Unit
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

    private fun markJavaExecutable(home: File) {
        // Directories need +x to traverse; .so / bin tools need +rx for dlopen/linker.
        home.walkTopDown().forEach { file ->
            val needsExec = file.isDirectory ||
                file.name == "java" ||
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
