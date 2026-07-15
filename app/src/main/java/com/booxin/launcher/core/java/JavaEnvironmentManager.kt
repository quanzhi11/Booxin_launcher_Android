package com.booxin.launcher.core.java

import com.booxin.launcher.core.LauncherPaths
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
 * Layout:
 * ```
 * filesDir/minecraft/java/
 *   java-8/
 *   java-17/
 *   java-21/
 * filesDir/minecraft/cache/java/
 *   *.tar.xz
 * ```
 */
class JavaEnvironmentManager(
    private val downloader: JavaDownloader = JavaDownloader()
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
        findInstalled(majorVersion)?.let { return Result.success(it) }
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
            val downloadResult = downloader.download(pkg.downloadUrl, cacheFile) { downloaded, total ->
                _progress.value = JavaInstallProgress(
                    componentId = pkg.componentId,
                    state = JavaInstallState.DOWNLOADING,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = "下载 ${pkg.displayName}"
                )
            }
            val archive = downloadResult.getOrElse { throw it }

            if (!pkg.sha256.isNullOrBlank()) {
                val actual = sha256(archive)
                if (!actual.equals(pkg.sha256, ignoreCase = true)) {
                    throw IllegalStateException("校验失败: expected ${pkg.sha256}, got $actual")
                }
            }

            emit(pkg.componentId, JavaInstallState.EXTRACTING, message = "正在解压 ${pkg.displayName}")
            val targetDir = LauncherPaths.javaRuntimeDir(pkg.componentId)
            ArchiveExtractor.extract(archive, targetDir)

            // Mark executable bits frequently needed on Android.
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

        // Fallback: shallow search for libjvm.so which proves a usable JRE tree.
        val libJvm = home.walkTopDown()
            .maxDepth(6)
            .firstOrNull { it.name == "libjvm.so" }
            ?: return null
        val binJava = File(libJvm.parentFile?.parentFile, "bin/java")
        return binJava.takeIf { it.exists() } ?: File(home, "bin/java").takeIf { it.exists() }
    }

    private fun markJavaExecutable(home: File) {
        listOf("bin/java", "bin/keytool", "bin/jdb").forEach { relative ->
            val file = File(home, relative)
            if (file.exists()) {
                file.setExecutable(true, false)
                file.setReadable(true, false)
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
