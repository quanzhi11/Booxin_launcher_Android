package com.booxin.launcher.data.repository

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.community.ModrinthClient
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.InstallTargetRecommendation
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.data.model.ModrinthResolvedDependency
import com.booxin.launcher.data.model.ModrinthSearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        version: ModrinthProjectVersion
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = requireNotNull(version.primaryFile) { "未找到整合包文件" }
            val cacheDir = File(LauncherPaths.rootDir, "cache/modrinth/modpacks").also { it.mkdirs() }
            val archive = File(cacheDir, file.filename)
            downloader.download(file.url, archive).getOrThrow()
            val manifest = readModpackManifest(archive)
            val deps = parseModpackDependencies(manifest.dependencies)
            val targetVersionId = resolveOrCreateModpackTarget(
                requestedTargetVersionId = requestedTargetVersionId,
                deps = deps
            )
            val root = File(LauncherPaths.versionsDir, targetVersionId).also { it.mkdirs() }
            extractOverrides(archive, root)
            downloadModpackFiles(manifest.files, root)
            launcherRepository.refreshInstalledVersions()
            launcherRepository.selectVersion(targetVersionId)
            targetVersionId
        }
    }

    private suspend fun resolveOrCreateModpackTarget(
        requestedTargetVersionId: String?,
        deps: ModpackDependencies
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
        if (existing != null) return existing

        val java = javaEnvironment.ensureForMinecraft(deps.minecraft).getOrThrow()
        return when {
            deps.neoforge != null ->
                launcherRepository.installNeoForgeVersion(deps.minecraft, deps.neoforge, null, java)
                    .getOrThrow()
            deps.forge != null ->
                launcherRepository.installForgeVersion(deps.minecraft, deps.forge, null, java)
                    .getOrThrow()
            deps.fabricLoader != null ->
                launcherRepository.installFabricVersion(deps.minecraft, deps.fabricLoader, null)
                    .getOrThrow()
            deps.quiltLoader != null ->
                launcherRepository.installQuiltVersion(deps.minecraft, deps.quiltLoader, null)
                    .getOrThrow()
            else -> {
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
        if (contentType == CommunityContentType.RESOURCE_PACK) return true
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

    private suspend fun downloadModpackFiles(files: JSONArray, targetRoot: File) {
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (path.isBlank()) continue
            val downloads = item.optJSONArray("downloads") ?: continue
            val url = downloads.optString(0)
            if (url.isBlank()) continue
            val out = safeChild(targetRoot, path)
            out.parentFile?.mkdirs()
            downloader.download(url, out).getOrThrow()
        }
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
    }
}
