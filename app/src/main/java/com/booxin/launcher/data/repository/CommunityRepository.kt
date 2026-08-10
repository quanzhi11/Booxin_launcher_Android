package com.booxin.launcher.data.repository

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.community.ModrinthClient
import com.booxin.launcher.core.download.DownloadProviders
import com.booxin.launcher.core.download.DownloadSource
import com.booxin.launcher.core.download.MirrorPreference
import com.booxin.launcher.core.download.game.Digests
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.version.AndroidIncompatibleMods
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.InstallTargetRecommendation
import com.booxin.launcher.data.model.ModpackInstallProgress
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.data.model.ModrinthResolvedDependency
import com.booxin.launcher.data.model.ModrinthSearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

class CommunityRepository(
    private val launcherRepository: LauncherRepository,
    private val javaEnvironment: JavaEnvironmentManager,
    private val client: ModrinthClient = ModrinthClient(),
    private val downloader: FileDownloader = FileDownloader()
) {
    private val cacheMutex = Mutex()
    private val projectCache = mutableMapOf<String, TimedCache<ModrinthProject>>()
    private val versionsCache = mutableMapOf<String, TimedCache<List<ModrinthProjectVersion>>>()
    private val mcVersionCache = mutableMapOf<String, String>()

    suspend fun searchProjects(
        query: String,
        contentType: CommunityContentType,
        loader: CommunityLoader,
        offset: Int = 0,
        limit: Int = ModrinthClient.PAGE_SIZE
    ): Result<ModrinthSearchPage> {
        val selectedMc = launcherRepository.session.value.selectedVersionId
            ?.let { resolveMinecraftVersionId(it) }
        return client.searchProjects(query, contentType, selectedMc, loader, offset, limit)
    }

    suspend fun getProject(projectId: String): Result<ModrinthProject> {
        cacheMutex.withLock {
            projectCache[projectId]?.takeIf { !it.expired }?.let { return Result.success(it.value) }
        }
        return client.getProject(projectId).onSuccess { project ->
            cacheMutex.withLock {
                projectCache[projectId] = TimedCache(project)
            }
        }
    }

    /**
     * 优先匹配已安装 MC 版本。
     * Falls back to unfiltered if the filtered list is empty.
     */
    suspend fun getProjectVersions(projectId: String): Result<List<ModrinthProjectVersion>> {
        val gameVersions = installedMinecraftVersions()
        val cacheKey = "$projectId|${gameVersions.sorted().joinToString(",")}"
        cacheMutex.withLock {
            versionsCache[cacheKey]?.takeIf { !it.expired }?.let { return Result.success(it.value) }
        }

        val filtered = if (gameVersions.isNotEmpty()) {
            client.getProjectVersions(projectId, gameVersions = gameVersions)
        } else {
            Result.success(emptyList())
        }
        val result = filtered.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Result.success(it) }
            ?: client.getProjectVersions(projectId)

        return result.onSuccess { versions ->
            cacheMutex.withLock {
                versionsCache[cacheKey] = TimedCache(versions)
            }
        }
    }

    suspend fun resolveRequiredDependencies(
        version: ModrinthProjectVersion
    ): Result<List<ModrinthResolvedDependency>> = withContext(Dispatchers.IO) {
        runCatching {
            val required = version.requiredDependencies
            if (required.isEmpty()) return@runCatching emptyList()
            val projects = client.getProjects(required.mapNotNull { it.projectId }).getOrThrow()
            val byId = projects.associateBy { it.id }
            required.mapNotNull { dep ->
                val id = dep.projectId ?: return@mapNotNull null
                val project = byId[id] ?: return@mapNotNull null
                ModrinthResolvedDependency(
                    projectId = project.id,
                    title = project.title,
                    slug = project.slug,
                    iconUrl = project.iconUrl,
                    description = project.description,
                    versionId = dep.versionId
                )
            }
        }
    }

    fun recommendTargets(
        contentType: CommunityContentType,
        version: ModrinthProjectVersion
    ): List<InstallTargetRecommendation> {
        val selectedId = launcherRepository.session.value.selectedVersionId
        val profiles = installedProfiles()
        val compatible = profiles.filter { profile ->
            isProfileCompatible(profile, contentType, version)
        }
        return compatible.mapIndexed { index, profile ->
            InstallTargetRecommendation(
                versionId = profile.versionId,
                reason = buildTargetReason(profile, version),
                recommended = profile.versionId == selectedId || (selectedId == null && index == 0)
            )
        }.sortedWith(compareByDescending<InstallTargetRecommendation> { it.recommended }
            .thenBy { it.versionId })
    }

    /** One-shot recommend labels for many versions (avoids re-reading version.json per call). */
    fun recommendLabels(
        contentType: CommunityContentType,
        versions: List<ModrinthProjectVersion>
    ): Map<String, String?> {
        val selectedId = launcherRepository.session.value.selectedVersionId
        val profiles = installedProfiles()
        return versions.associate { version ->
            val compatible = profiles.filter { isProfileCompatible(it, contentType, version) }
            val pick = when {
                compatible.isEmpty() -> null
                selectedId != null ->
                    compatible.firstOrNull { it.versionId == selectedId } ?: compatible.first()
                else -> compatible.first()
            }
            version.id to pick?.versionId
        }
    }

    suspend fun installVersionFile(
        contentType: CommunityContentType,
        targetVersionId: String,
        version: ModrinthProjectVersion
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val file = requireNotNull(version.primaryFile) { "未找到可下载文件" }
            launcherRepository.ensureVersionReady(targetVersionId).getOrThrow()
            val versionRoot = File(LauncherPaths.versionsDir, targetVersionId).also { it.mkdirs() }
            val destination = File(versionRoot, contentType.targetSubdir).also { it.mkdirs() }
            val output = File(destination, file.filename)
            downloader.download(file.url, output).getOrThrow()
            launcherRepository.selectVersion(targetVersionId)
            output
        }
    }

    suspend fun installModpack(
        requestedTargetVersionId: String?,
        version: ModrinthProjectVersion,
        onProgress: (ModpackInstallProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = requireNotNull(version.primaryFile) { "未找到整合包文件" }
            val archive = downloadModpackArchive(file.url, file.filename, onProgress)
            installModpackArchive(archive, requestedTargetVersionId, onProgress)
        }
    }

    /** Install a Modrinth `.mrpack` / zip already on disk. */
    suspend fun installModpackFromArchive(
        archive: File,
        requestedTargetVersionId: String? = null,
        onProgress: (ModpackInstallProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching { installModpackArchive(archive, requestedTargetVersionId, onProgress) }
    }

    /** Download a Modrinth pack from a direct URL, then install. */
    suspend fun installModpackFromUrl(
        url: String,
        requestedTargetVersionId: String? = null,
        onProgress: (ModpackInstallProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = url.trim()
            require(trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)) {
                "请输入有效的 http/https 链接"
            }
            val name = trimmed.substringAfterLast('/').substringBefore('?').ifBlank { "modpack.mrpack" }
            val archive = downloadModpackArchive(trimmed, sanitizeArchiveName(name), onProgress)
            installModpackArchive(archive, requestedTargetVersionId, onProgress)
        }
    }

    private suspend fun downloadModpackArchive(
        url: String,
        filename: String,
        onProgress: (ModpackInstallProgress) -> Unit
    ): File {
        val cacheDir = File(LauncherPaths.rootDir, "cache/modrinth/modpacks").also { it.mkdirs() }
        val archive = File(cacheDir, sanitizeArchiveName(filename))
        onProgress(
            ModpackInstallProgress(
                stage = "正在下载整合包",
                detail = filename
            )
        )
        downloader.download(
            urls = modrinthDownloadCandidates(url),
            destination = archive,
            onProgress = { downloaded, total ->
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在下载整合包",
                        detail = filename,
                        bytesDownloaded = downloaded,
                        bytesTotal = total
                    )
                )
            },
            accelerate = true
        ).getOrThrow()
        return archive
    }

    private fun sanitizeArchiveName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "modpack.mrpack" }
    }

    private suspend fun installModpackArchive(
        archive: File,
        requestedTargetVersionId: String?,
        onProgress: (ModpackInstallProgress) -> Unit
    ): String {
        require(archive.isFile && archive.length() > 0L) { "整合包文件无效或不存在" }
        onProgress(ModpackInstallProgress(stage = "正在解析整合包清单"))
        val manifest = readModpackManifest(archive)
        val deps = parseModpackDependencies(manifest.dependencies)
        val targetVersionId = resolveOrCreateModpackTarget(
            requestedTargetVersionId = requestedTargetVersionId,
            deps = deps,
            onProgress = onProgress
        )
        val root = File(LauncherPaths.versionsDir, targetVersionId).also { it.mkdirs() }
        onProgress(
            ModpackInstallProgress(
                stage = "正在解压 overrides",
                detail = targetVersionId
            )
        )
        extractOverrides(archive, root)
        downloadModpackFiles(manifest.files, root, onProgress)
        val blocked = AndroidIncompatibleMods.scanAndDisable(targetVersionId)
        if (blocked.changed) {
            onProgress(
                ModpackInstallProgress(
                    stage = "已禁用 Android 不兼容模组并重新加载",
                    detail = blocked.disabled.joinToString("；") { it.substringBefore(" →") },
                    current = blocked.disabled.size,
                    total = blocked.disabled.size
                )
            )
        }
        onProgress(ModpackInstallProgress(stage = "正在刷新已安装版本"))
        launcherRepository.refreshInstalledVersions()
        launcherRepository.selectVersion(targetVersionId)
        onProgress(
            ModpackInstallProgress(
                stage = "安装完成",
                detail = targetVersionId
            )
        )
        return targetVersionId
    }

    private suspend fun resolveOrCreateModpackTarget(
        requestedTargetVersionId: String?,
        deps: ModpackDependencies,
        onProgress: (ModpackInstallProgress) -> Unit
    ): String {
        if (requestedTargetVersionId != null) {
            require(isTargetCompatibleWithDependencies(requestedTargetVersionId, deps)) {
                "目标版本与整合包依赖不兼容"
            }
            return requestedTargetVersionId
        }
        val existing = launcherRepository.installedVersions.value.firstOrNull {
            isTargetCompatibleWithDependencies(it.id, deps)
        }?.id
        if (existing != null) {
            onProgress(
                ModpackInstallProgress(
                    stage = "复用已有兼容版本",
                    detail = existing
                )
            )
            return existing
        }

        onProgress(
            ModpackInstallProgress(
                stage = "正在准备 Java / 加载器",
                detail = "Minecraft ${deps.minecraft}"
            )
        )
        val java = javaEnvironment.ensureForMinecraft(deps.minecraft).getOrThrow()
        return when {
            deps.neoforge != null -> {
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在安装 NeoForge",
                        detail = "${deps.minecraft}-neoforge-${deps.neoforge}"
                    )
                )
                launcherRepository.installNeoForgeVersion(deps.minecraft, deps.neoforge, null, java)
                    .getOrThrow()
            }
            deps.forge != null -> {
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在安装 Forge",
                        detail = "${deps.minecraft}-forge-${deps.forge}"
                    )
                )
                launcherRepository.installForgeVersion(deps.minecraft, deps.forge, null, java)
                    .getOrThrow()
            }
            deps.fabricLoader != null -> {
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在安装 Fabric",
                        detail = "${deps.minecraft}-fabric-${deps.fabricLoader}"
                    )
                )
                launcherRepository.installFabricVersion(deps.minecraft, deps.fabricLoader, null)
                    .getOrThrow()
            }
            deps.quiltLoader != null -> {
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在安装 Quilt",
                        detail = "${deps.minecraft}-quilt-${deps.quiltLoader}"
                    )
                )
                launcherRepository.installQuiltVersion(deps.minecraft, deps.quiltLoader, null)
                    .getOrThrow()
            }
            else -> {
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在安装原版",
                        detail = deps.minecraft
                    )
                )
                launcherRepository.installVersion(deps.minecraft).getOrThrow()
                deps.minecraft
            }
        }
    }

    private fun parseModpackDependencies(raw: JSONObject): ModpackDependencies {
        val minecraft = raw.optString("minecraft").ifBlank { error("整合包缺少 minecraft 依赖") }
        return ModpackDependencies(
            minecraft = minecraft,
            forge = raw.optString("forge").ifBlank { null },
            neoforge = raw.optString("neoforge").ifBlank { null },
            fabricLoader = raw.optString("fabric-loader").ifBlank { null },
            quiltLoader = raw.optString("quilt-loader").ifBlank { null }
        )
    }

    private fun isTargetCompatibleWithDependencies(
        versionId: String,
        deps: ModpackDependencies
    ): Boolean {
        if (resolveMinecraftVersionId(versionId) != deps.minecraft) return false
        val loader = CommunityLoader.fromVersionId(versionId)
        return when {
            deps.neoforge != null ->
                loader == CommunityLoader.NEOFORGE &&
                    versionId.equals("${deps.minecraft}-neoforge-${deps.neoforge}", ignoreCase = true)
            deps.forge != null ->
                loader == CommunityLoader.FORGE &&
                    versionId.equals("${deps.minecraft}-forge-${deps.forge}", ignoreCase = true)
            deps.fabricLoader != null ->
                loader == CommunityLoader.FABRIC &&
                    versionId.equals("${deps.minecraft}-fabric-${deps.fabricLoader}", ignoreCase = true)
            deps.quiltLoader != null ->
                loader == CommunityLoader.QUILT &&
                    versionId.equals("${deps.minecraft}-quilt-${deps.quiltLoader}", ignoreCase = true)
            else -> loader == CommunityLoader.ANY
        }
    }

    private fun isProfileCompatible(
        profile: InstalledProfile,
        contentType: CommunityContentType,
        version: ModrinthProjectVersion
    ): Boolean {
        if (version.gameVersions.isNotEmpty() && profile.minecraftId !in version.gameVersions) return false
        if (contentType == CommunityContentType.RESOURCE_PACK ||
            contentType == CommunityContentType.SHADER
        ) {
            return true
        }
        val normalizedLoaders = version.loaders.map { it.lowercase() }
        if (contentType == CommunityContentType.MODPACK) {
            if (normalizedLoaders.isEmpty()) return true
            return when (profile.loader) {
                CommunityLoader.FORGE -> "forge" in normalizedLoaders
                CommunityLoader.NEOFORGE -> "neoforge" in normalizedLoaders
                CommunityLoader.FABRIC -> "fabric" in normalizedLoaders
                CommunityLoader.QUILT -> "quilt" in normalizedLoaders
                CommunityLoader.ANY ->
                    normalizedLoaders.none { it in setOf("forge", "neoforge", "fabric", "quilt") }
            }
        }
        if (normalizedLoaders.isEmpty()) return true
        return when (profile.loader) {
            CommunityLoader.FORGE -> "forge" in normalizedLoaders
            CommunityLoader.NEOFORGE -> "neoforge" in normalizedLoaders
            CommunityLoader.FABRIC -> "fabric" in normalizedLoaders
            CommunityLoader.QUILT -> "quilt" in normalizedLoaders
            CommunityLoader.ANY -> false
        }
    }

    private fun buildTargetReason(profile: InstalledProfile, version: ModrinthProjectVersion): String {
        val loaderText = when (profile.loader) {
            CommunityLoader.ANY -> "原版"
            CommunityLoader.FORGE -> "Forge"
            CommunityLoader.NEOFORGE -> "NeoForge"
            CommunityLoader.FABRIC -> "Fabric"
            CommunityLoader.QUILT -> "Quilt"
        }
        val selected = launcherRepository.session.value.selectedVersionId == profile.versionId
        val recommend = if (selected) "当前已选" else "兼容"
        return "$recommend · MC ${profile.minecraftId} · $loaderText · ${version.name}"
    }

    private fun installedProfiles(): List<InstalledProfile> {
        return launcherRepository.installedVersions.value.map { item ->
            InstalledProfile(
                versionId = item.id,
                minecraftId = resolveMinecraftVersionId(item.id),
                loader = CommunityLoader.fromVersionId(item.id)
            )
        }
    }

    private fun installedMinecraftVersions(): List<String> {
        return installedProfiles().map { it.minecraftId }.filter { it.isNotBlank() }.distinct()
    }

    private fun resolveMinecraftVersionId(versionId: String): String {
        return mcVersionCache.getOrPut(versionId) {
            VersionJsonMerger.resolveMinecraftVersionId(versionId)
        }
    }

    private data class InstalledProfile(
        val versionId: String,
        val minecraftId: String,
        val loader: CommunityLoader
    )

    private data class TimedCache<T>(
        val value: T,
        val atMs: Long = System.currentTimeMillis()
    ) {
        val expired: Boolean get() = System.currentTimeMillis() - atMs > CACHE_TTL_MS
    }

    private fun readModpackManifest(archive: File): ModpackManifest {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("modrinth.index.json")
                ?: error("整合包缺少 modrinth.index.json")
            val root = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
            return ModpackManifest(
                dependencies = root.optJSONObject("dependencies") ?: JSONObject(),
                files = root.optJSONArray("files") ?: JSONArray()
            )
        }
    }

    private fun extractOverrides(archive: File, targetRoot: File) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val relative = when {
                    name.startsWith("overrides/") -> name.removePrefix("overrides/")
                    name.startsWith("client-overrides/") -> name.removePrefix("client-overrides/")
                    else -> null
                } ?: continue
                val out = safeChild(targetRoot, relative)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private suspend fun downloadModpackFiles(
        files: JSONArray,
        targetRoot: File,
        onProgress: (ModpackInstallProgress) -> Unit
    ) = coroutineScope {
        data class Job(val path: String, val urls: List<String>, val sha1: String?)

        val jobs = ArrayList<Job>(files.length())
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (path.isBlank()) continue
            val downloads = item.optJSONArray("downloads") ?: continue
            val url = downloads.optString(0)
            if (url.isBlank()) continue
            val sha1 = item.optJSONObject("hashes")?.optString("sha1")?.takeIf { it.isNotBlank() }
            // Prefer primary URL + mirror candidates; keep extras from the pack as fallback.
            val urls = buildList {
                addAll(modrinthDownloadCandidates(url))
                for (j in 1 until downloads.length()) {
                    val alt = downloads.optString(j)
                    if (alt.isNotBlank()) addAll(modrinthDownloadCandidates(alt))
                }
            }.distinct()
            jobs += Job(path, urls, sha1)
        }
        val total = jobs.size
        if (total == 0) return@coroutineScope

        onProgress(
            ModpackInstallProgress(
                stage = "正在并行下载模组文件",
                current = 0,
                total = total,
                detail = "并发 $MODPACK_FILE_CONCURRENCY"
            )
        )

        val semaphore = Semaphore(MODPACK_FILE_CONCURRENCY)
        val completed = AtomicInteger(0)
        jobs.map { job ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val out = safeChild(targetRoot, job.path)
                    out.parentFile?.mkdirs()
                    if (Digests.matchesSha1(out, job.sha1) ||
                        (job.sha1 == null && out.isFile && out.length() > 0L)
                    ) {
                        // Keep existing file when hash matches (or unknown hash + non-empty).
                    } else {
                        downloader.download(
                            urls = job.urls,
                            destination = out,
                            // Small mod jars: skip Range probe — many parallel probes hurt more.
                            accelerate = false
                        ).getOrThrow()
                        if (job.sha1 != null && !Digests.matchesSha1(out, job.sha1)) {
                            error("校验失败: ${job.path}")
                        }
                    }
                    val done = completed.incrementAndGet()
                    onProgress(
                        ModpackInstallProgress(
                            stage = "正在并行下载模组文件",
                            current = done,
                            total = total,
                            detail = job.path.substringAfterLast('/')
                        )
                    )
                }
            }
        }.awaitAll()
    }

    /**
     * Modrinth CDN → MCIM mirror candidates (CN). Order follows download-source preference.
     */
    private fun modrinthDownloadCandidates(rawUrl: String): List<String> {
        val official = rawUrl.trim()
        if (official.isEmpty()) return emptyList()
        val mirrored = when {
            official.startsWith("https://cdn.modrinth.com/") ->
                "https://mod.mcimirror.top/" + official.removePrefix("https://cdn.modrinth.com/")
            official.startsWith("http://cdn.modrinth.com/") ->
                "https://mod.mcimirror.top/" + official.removePrefix("http://cdn.modrinth.com/")
            else -> null
        }
        if (mirrored == null || mirrored == official) return listOf(official)
        val preferMirror = when (DownloadProviders.source) {
            DownloadSource.OFFICIAL -> false
            DownloadSource.MIRROR -> true
            DownloadSource.BALANCED ->
                DownloadProviders.libraryPreference == MirrorPreference.MIRROR_FIRST
        }
        return if (preferMirror) listOf(mirrored, official) else listOf(official, mirrored)
    }

    private fun safeChild(root: File, relativePath: String): File {
        val clean = relativePath.removePrefix("/").replace('\\', '/')
        val child = File(root, clean).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(child.path.startsWith(canonicalRoot.path)) { "非法文件路径: $relativePath" }
        return child
    }

    private data class ModpackManifest(
        val dependencies: JSONObject,
        val files: JSONArray
    )

    private data class ModpackDependencies(
        val minecraft: String,
        val forge: String?,
        val neoforge: String?,
        val fabricLoader: String?,
        val quiltLoader: String?
    )

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        /** Parallel Modrinth file downloads (OkHttp maxRequestsPerHost=16). */
        private const val MODPACK_FILE_CONCURRENCY = 8
    }
}
