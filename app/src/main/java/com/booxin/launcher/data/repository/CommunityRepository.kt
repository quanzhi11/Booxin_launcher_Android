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
        return client.getProject(projectId)
    }

    suspend fun getProjectVersions(projectId: String): Result<List<ModrinthProjectVersion>> {
        return client.getProjectVersions(projectId)
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
        val installed = launcherRepository.installedVersions.value
        val compatible = installed.filter { installedVersion ->
            isVersionCompatible(installedVersion.id, contentType, version)
        }
        return compatible.mapIndexed { index, item ->
            InstallTargetRecommendation(
                versionId = item.id,
                reason = buildTargetReason(item.id, version),
                recommended = item.id == selectedId || (selectedId == null && index == 0)
            )
        }.sortedWith(compareByDescending<InstallTargetRecommendation> { it.recommended }
            .thenBy { it.versionId })
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
            val minecraftVersion = manifest.dependencies.optString("minecraft")
                .ifBlank { error("整合包缺少 minecraft 依赖") }
            val forgeVersion = manifest.dependencies.optString("forge").ifBlank { null }
            val targetVersionId = resolveOrCreateModpackTarget(
                requestedTargetVersionId = requestedTargetVersionId,
                minecraftVersion = minecraftVersion,
                forgeVersion = forgeVersion
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
        minecraftVersion: String,
        forgeVersion: String?
    ): String {
        if (requestedTargetVersionId != null) {
            require(isTargetCompatibleWithDependencies(requestedTargetVersionId, minecraftVersion, forgeVersion)) {
                "目标版本与整合包依赖不兼容"
            }
            return requestedTargetVersionId
        }
        val existing = launcherRepository.installedVersions.value.firstOrNull {
            isTargetCompatibleWithDependencies(it.id, minecraftVersion, forgeVersion)
        }?.id
        if (existing != null) return existing

        return if (forgeVersion != null) {
            val java = javaEnvironment.ensureForMinecraft(minecraftVersion).getOrThrow()
            launcherRepository.installForgeVersion(minecraftVersion, forgeVersion, null, java).getOrThrow()
        } else {
            launcherRepository.installVersion(minecraftVersion).getOrThrow()
            minecraftVersion
        }
    }

    private fun isTargetCompatibleWithDependencies(
        versionId: String,
        minecraftVersion: String,
        forgeVersion: String?
    ): Boolean {
        if (resolveMinecraftVersionId(versionId) != minecraftVersion) return false
        return when {
            forgeVersion == null -> true
            else -> versionId.equals("$minecraftVersion-forge-$forgeVersion", ignoreCase = true)
        }
    }

    private fun isVersionCompatible(
        installedVersionId: String,
        contentType: CommunityContentType,
        version: ModrinthProjectVersion
    ): Boolean {
        val installedMc = resolveMinecraftVersionId(installedVersionId)
        if (version.gameVersions.isNotEmpty() && installedMc !in version.gameVersions) return false
        if (contentType == CommunityContentType.RESOURCE_PACK) return true
        if (contentType == CommunityContentType.MODPACK) {
            val forgeDep = version.loaders.firstOrNull { it.equals("forge", ignoreCase = true) }
            return forgeDep == null || CommunityLoader.fromVersionId(installedVersionId) == CommunityLoader.FORGE
        }
        val installedLoader = CommunityLoader.fromVersionId(installedVersionId)
        val normalizedLoaders = version.loaders.map { it.lowercase() }
        if (normalizedLoaders.isEmpty()) return true
        return when (installedLoader) {
            CommunityLoader.FORGE -> "forge" in normalizedLoaders
            CommunityLoader.NEOFORGE -> "neoforge" in normalizedLoaders
            CommunityLoader.FABRIC -> "fabric" in normalizedLoaders
            CommunityLoader.QUILT -> "quilt" in normalizedLoaders
            CommunityLoader.ANY -> false
        }
    }

    private fun buildTargetReason(versionId: String, version: ModrinthProjectVersion): String {
        val parent = resolveMinecraftVersionId(versionId)
        val loader = CommunityLoader.fromVersionId(versionId)
        val loaderText = when (loader) {
            CommunityLoader.ANY -> "原版"
            CommunityLoader.FORGE -> "Forge"
            CommunityLoader.NEOFORGE -> "NeoForge"
            CommunityLoader.FABRIC -> "Fabric"
            CommunityLoader.QUILT -> "Quilt"
        }
        val selected = launcherRepository.session.value.selectedVersionId == versionId
        val recommend = if (selected) "当前已选" else "兼容"
        return "$recommend · MC $parent · $loaderText · ${version.name}"
    }

    private fun resolveMinecraftVersionId(versionId: String): String {
        return VersionJsonMerger.resolveMinecraftVersionId(versionId)
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
}
