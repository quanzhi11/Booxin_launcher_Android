package com.booxin.launcher.data.repository

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.community.CommunitySearchQuery
import com.booxin.launcher.core.community.CurseForgeClient
import com.booxin.launcher.core.community.ModrinthClient
import com.booxin.launcher.core.community.ModrinthUrlCandidates
import com.booxin.launcher.core.download.game.Digests
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.core.net.FileDownloader
import com.booxin.launcher.core.version.AndroidIncompatibleMods
import com.booxin.launcher.core.version.VersionModsManager
import com.booxin.launcher.data.model.CommunityContentType
import com.booxin.launcher.data.model.CommunityLoader
import com.booxin.launcher.data.model.CommunitySource
import com.booxin.launcher.data.model.InstallTargetRecommendation
import com.booxin.launcher.data.model.ModpackInstallProgress
import com.booxin.launcher.data.model.ModrinthDependency
import com.booxin.launcher.data.model.ModrinthProject
import com.booxin.launcher.data.model.ModrinthProjectVersion
import com.booxin.launcher.data.model.ModrinthResolvedDependency
import com.booxin.launcher.data.model.ModrinthSearchPage
import com.booxin.launcher.data.model.ModrinthVersionFile
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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class CommunityRepository(
    private val launcherRepository: LauncherRepository,
    private val javaEnvironment: JavaEnvironmentManager,
    private val client: ModrinthClient = ModrinthClient(),
    private val curseForgeClient: CurseForgeClient = CurseForgeClient(),
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
        limit: Int = ModrinthClient.PAGE_SIZE,
        targetVersionId: String? = null,
        source: CommunitySource = CommunitySource.MODRINTH
    ): Result<ModrinthSearchPage> {
        val mcSource = targetVersionId ?: launcherRepository.session.value.selectedVersionId
        val selectedMc = mcSource?.let { resolveMinecraftVersionId(it) }
        // Modpacks: do not lock search to the currently selected MC — users need
        // older pack builds. Mods/shaders/resource packs still follow current MC.
        val searchMc = if (contentType == CommunityContentType.MODPACK) null else selectedMc
        // Modrinth is English-indexed; expand Chinese keywords before searching.
        val resolved = CommunitySearchQuery.resolve(query)
        if (resolved.usedChinese) {
            android.util.Log.i(
                "CommunitySearch",
                "zh→en via=${resolved.via} raw=${resolved.original} q=${resolved.query}"
            )
        }
        return when (source) {
            CommunitySource.MODRINTH -> client.searchProjects(
                resolved.query,
                contentType,
                searchMc,
                loader,
                offset,
                limit
            )
            CommunitySource.CURSEFORGE -> curseForgeClient.searchProjects(
                resolved.query,
                contentType,
                searchMc,
                loader,
                offset,
                limit
            )
        }
    }

    /** Modrinth search hits that can install into [targetVersionId]. */
    fun filterCompatibleProjects(
        targetVersionId: String,
        contentType: CommunityContentType,
        projects: List<ModrinthProject>
    ): List<ModrinthProject> {
        val profile = profileForVersionId(targetVersionId) ?: return emptyList()
        return projects.filter { isProjectCompatible(profile, contentType, it) }
    }

    /**
     * Search and keep scanning forward until [limit] compatible projects are found
     * (in-game list should not show items that cannot install into the running instance).
     */
    suspend fun searchCompatibleProjectsForTarget(
        query: String,
        contentType: CommunityContentType,
        loader: CommunityLoader,
        targetVersionId: String,
        offset: Int,
        limit: Int,
        maxScanPages: Int = 4
    ): Result<ModrinthSearchPage> {
        val profile = profileForVersionId(targetVersionId)
            ?: return Result.success(ModrinthSearchPage(emptyList(), offset, limit, 0))
        var scanOffset = offset
        val collected = mutableListOf<ModrinthProject>()
        var lastPage: ModrinthSearchPage? = null
        repeat(maxScanPages) {
            if (collected.size >= limit) return@repeat
            val page = searchProjects(
                query = query,
                contentType = contentType,
                loader = loader,
                offset = scanOffset,
                limit = limit,
                targetVersionId = targetVersionId
            ).getOrElse { return Result.failure(it) }
            lastPage = page
            if (page.projects.isEmpty()) return@repeat
            collected += page.projects.filter { isProjectCompatible(profile, contentType, it) }
            scanOffset += page.limit
            if (!page.hasNext) return@repeat
        }
        val base = lastPage ?: ModrinthSearchPage(emptyList(), offset, limit, 0)
        val shown = collected.take(limit)
        val moreAvailable = base.hasNext || collected.size > limit
        val adjustedTotal = if (moreAvailable) {
            offset + shown.size + 1
        } else {
            offset + shown.size
        }
        return Result.success(
            ModrinthSearchPage(
                projects = shown,
                offset = offset,
                limit = limit,
                totalHits = adjustedTotal
            )
        )
    }

    suspend fun getProject(projectId: String): Result<ModrinthProject> {
        cacheMutex.withLock {
            projectCache[projectId]?.takeIf { !it.expired }?.let { return Result.success(it.value) }
        }
        val result = if (CurseForgeClient.isCurseForgeId(projectId)) {
            curseForgeClient.getProject(projectId)
        } else {
            client.getProject(projectId)
        }
        return result.onSuccess { project ->
            cacheMutex.withLock {
                projectCache[projectId] = TimedCache(project)
                projectCache[project.id] = TimedCache(project)
            }
        }
    }

    /**
     * Full version list from Modrinth (all game versions).
     * Filtering by installed MC hid older builds and made the list look “too short”.
     * UI sorts / recommends against the current instance.
     */
    suspend fun getProjectVersions(projectId: String): Result<List<ModrinthProjectVersion>> {
        val cacheKey = "$projectId|all"
        cacheMutex.withLock {
            versionsCache[cacheKey]?.takeIf { !it.expired }?.let { return Result.success(it.value) }
        }
        val result = if (CurseForgeClient.isCurseForgeId(projectId)) {
            curseForgeClient.getProjectFiles(projectId)
        } else {
            client.getProjectVersions(projectId)
        }
        return result.onSuccess { versions ->
            cacheMutex.withLock {
                versionsCache[cacheKey] = TimedCache(versions)
            }
        }
    }

    /** Only versions compatible with the running / target game instance. */
    suspend fun getCompatibleProjectVersions(
        projectId: String,
        targetVersionId: String,
        contentType: CommunityContentType
    ): Result<List<ModrinthProjectVersion>> {
        val profile = profileForVersionId(targetVersionId)
            ?: return Result.success(emptyList())
        val loaderFilter = loaderQueryForProfile(profile, contentType)
        val cacheKey = "$projectId|${profile.minecraftId}|${loaderFilter.joinToString()}|$targetVersionId"
        cacheMutex.withLock {
            versionsCache[cacheKey]?.takeIf { !it.expired }?.let { return Result.success(it.value) }
        }
        val result = if (CurseForgeClient.isCurseForgeId(projectId)) {
            curseForgeClient.getProjectFiles(
                modId = projectId,
                gameVersion = profile.minecraftId,
                loader = profile.loader
            )
        } else {
            client.getProjectVersions(
                projectId = projectId,
                gameVersions = listOf(profile.minecraftId),
                loaders = loaderFilter
            )
        }
        return result.map { versions ->
            filterCompatibleVersions(targetVersionId, contentType, versions)
        }.onSuccess { versions ->
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
            val resolved = required.mapNotNull { dep -> resolveDependencyProjectId(dep) }
            if (resolved.isEmpty()) return@runCatching emptyList()
            val projectsById = linkedMapOf<String, ModrinthProject>()
            val modrinthIds = resolved.filterNot { CurseForgeClient.isCurseForgeId(it) }
            client.getProjects(modrinthIds).getOrNull()?.forEach { projectsById[it.id] = it }
            for (projectId in resolved) {
                if (projectsById.containsKey(projectId)) continue
                val project = if (CurseForgeClient.isCurseForgeId(projectId)) {
                    curseForgeClient.getProject(projectId).getOrNull()
                } else {
                    client.getProject(projectId).getOrNull()
                }
                if (project != null) projectsById[projectId] = project
            }
            required.mapNotNull { dep ->
                val projectId = resolveDependencyProjectId(dep) ?: return@mapNotNull null
                val project = projectsById[projectId] ?: return@mapNotNull null
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

    private suspend fun resolveDependencyProjectId(dep: ModrinthDependency): String? {
        dep.projectId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val versionId = dep.versionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val version = client.getVersion(versionId).getOrNull() ?: return null
        return version.projectId?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Versions compatible with a specific installed instance (e.g. in-game launch target). */
    fun filterCompatibleVersions(
        targetVersionId: String,
        contentType: CommunityContentType,
        versions: List<ModrinthProjectVersion>
    ): List<ModrinthProjectVersion> {
        val profile = profileForVersionId(targetVersionId) ?: return emptyList()
        return versions.filter { isProfileCompatible(profile, contentType, it) }
            .sortedByDescending { it.datePublished.orEmpty() }
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
        version: ModrinthProjectVersion,
        onProgress: (ModpackInstallProgress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val file = requireNotNull(version.primaryFile) { "未找到可下载文件" }
            onProgress(
                ModpackInstallProgress(
                    stage = "正在准备安装目标",
                    detail = targetVersionId
                )
            )
            launcherRepository.ensureVersionReady(targetVersionId).getOrThrow()
            val versionRoot = File(LauncherPaths.versionsDir, targetVersionId).also { it.mkdirs() }
            val destination = File(versionRoot, contentType.targetSubdir).also { it.mkdirs() }
            val output = File(destination, file.filename)
            if (file.sha1 != null && Digests.matchesSha1(output, file.sha1)) {
                onProgress(
                    ModpackInstallProgress(
                        stage = "已存在，跳过下载",
                        detail = file.filename,
                        bytesDownloaded = output.length(),
                        bytesTotal = output.length()
                    )
                )
            } else {
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在下载",
                        detail = file.filename
                    )
                )
                downloader.download(
                    urls = downloadCandidatesForFile(version, file),
                    destination = output,
                    onProgress = { downloaded, total ->
                        onProgress(
                            ModpackInstallProgress(
                                stage = "正在下载",
                                detail = file.filename,
                                bytesDownloaded = downloaded,
                                bytesTotal = total
                            )
                        )
                    },
                    accelerate = false
                ).getOrThrow()
                if (file.sha1 != null && !Digests.matchesSha1(output, file.sha1)) {
                    output.delete()
                    error("文件校验失败: ${file.filename}")
                }
            }
            launcherRepository.selectVersion(targetVersionId)
            onProgress(
                ModpackInstallProgress(
                    stage = "安装完成",
                    detail = file.filename
                )
            )
            output
        }
    }

    /** 按 mod id / slug 一键下载缺失依赖。 */
    suspend fun installMissingMods(
        targetVersionId: String,
        modIds: List<String>,
        onProgress: (ModpackInstallProgress) -> Unit = {}
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (modIds.isEmpty()) return@runCatching emptyList()
            val installed = mutableListOf<String>()
            for (modId in modIds.distinct()) {
                onProgress(
                    ModpackInstallProgress(
                        stage = "正在查找模组",
                        detail = modId
                    )
                )
                val project = findModProject(modId).getOrThrow()
                val versions = getCompatibleProjectVersions(
                    projectId = project.id,
                    targetVersionId = targetVersionId,
                    contentType = CommunityContentType.MOD
                ).getOrThrow()
                val pick = versions.firstOrNull()
                    ?: error("未找到与当前版本兼容的 ${project.title}")
                installVersionFile(
                    contentType = CommunityContentType.MOD,
                    targetVersionId = targetVersionId,
                    version = pick,
                    onProgress = onProgress
                ).getOrThrow()
                installed += project.title
            }
            AndroidIncompatibleMods.scanAndDisable(targetVersionId)
            installed
        }
    }

    private suspend fun findModProject(modIdOrSlug: String): Result<ModrinthProject> {
        client.getProject(modIdOrSlug).onSuccess { return Result.success(it) }
        val page = client.searchProjects(
            query = modIdOrSlug,
            contentType = CommunityContentType.MOD,
            gameVersion = null,
            loader = CommunityLoader.ANY,
            offset = 0,
            limit = 8
        ).getOrElse { return Result.failure(it) }
        val exact = page.projects.firstOrNull {
            it.slug.equals(modIdOrSlug, ignoreCase = true) ||
                it.id.equals(modIdOrSlug, ignoreCase = true)
        } ?: page.projects.firstOrNull()
        return if (exact != null) {
            Result.success(exact)
        } else {
            Result.failure(IllegalStateException("未在 Modrinth 找到模组: $modIdOrSlug"))
        }
    }

    suspend fun installModpack(
        requestedTargetVersionId: String?,
        version: ModrinthProjectVersion,
        onProgress: (ModpackInstallProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // Prefer .mrpack over sidecar jars/zips on the same version.
            val file = requireNotNull(pickModpackFile(version)) { "未找到整合包文件" }
            val archive = downloadModpackArchive(
                url = file.url,
                filename = file.filename,
                expectedSha1 = file.sha1,
                onProgress = onProgress
            )
            installModpackArchive(archive, requestedTargetVersionId, onProgress)
        }
    }

    /** Install a Modrinth `.mrpack` / CurseForge zip already on disk. */
    suspend fun installModpackFromArchive(
        archive: File,
        requestedTargetVersionId: String? = null,
        onProgress: (ModpackInstallProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching { installModpackArchive(archive, requestedTargetVersionId, onProgress) }
    }

    /** Download a pack from a direct URL, then install. */
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
            val archive = downloadModpackArchive(
                url = trimmed,
                filename = sanitizeArchiveName(name),
                expectedSha1 = null,
                onProgress = onProgress
            )
            installModpackArchive(archive, requestedTargetVersionId, onProgress)
        }
    }

    private fun pickModpackFile(version: ModrinthProjectVersion): ModrinthVersionFile? {
        val files = version.files
        if (files.isEmpty()) return null
        return files.firstOrNull { it.primary && it.filename.endsWith(".mrpack", ignoreCase = true) }
            ?: files.firstOrNull { it.filename.endsWith(".mrpack", ignoreCase = true) }
            ?: files.firstOrNull { it.primary }
            ?: files.firstOrNull()
    }

    private suspend fun downloadModpackArchive(
        url: String,
        filename: String,
        expectedSha1: String?,
        onProgress: (ModpackInstallProgress) -> Unit
    ): File {
        val cacheDir = File(LauncherPaths.rootDir, "cache/modrinth/modpacks").also { it.mkdirs() }
        val archive = File(cacheDir, sanitizeArchiveName(filename))
        if (expectedSha1 != null && Digests.matchesSha1(archive, expectedSha1)) {
            onProgress(
                ModpackInstallProgress(
                    stage = "使用已缓存整合包",
                    detail = filename,
                    bytesDownloaded = archive.length(),
                    bytesTotal = archive.length()
                )
            )
            return archive
        }
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
        if (expectedSha1 != null && !Digests.matchesSha1(archive, expectedSha1)) {
            archive.delete()
            error("整合包校验失败，请重试")
        }
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
        val pack = readModpackArchive(archive)
        android.util.Log.i(
            "ModpackInstall",
            "parsed type=${pack.packageType} base='${pack.basePath}' files=${pack.files.size} " +
                "mc=${pack.deps.minecraft} forge=${pack.deps.forge} fabric=${pack.deps.fabricLoader} " +
                "neo=${pack.deps.neoforge} quilt=${pack.deps.quiltLoader}"
        )
        // Align with PC: Modrinth packs must declare downloadable files (overrides alone are not enough).
        if (pack.packageType == ModpackPackageType.MODRINTH && pack.files.isEmpty()) {
            error("整合包清单 files 为空，无法下载模组（请确认是有效的 .mrpack）")
        }
        val targetVersionId = resolveOrCreateModpackTarget(
            requestedTargetVersionId = requestedTargetVersionId,
            deps = pack.deps,
            onProgress = onProgress
        )
        val root = File(LauncherPaths.versionsDir, targetVersionId).also { it.mkdirs() }
        onProgress(
            ModpackInstallProgress(
                stage = "正在下载模组文件",
                detail = "$targetVersionId · 共 ${pack.files.size} 个",
                current = 0,
                total = pack.files.size
            )
        )
        downloadPackFiles(pack.files, root, onProgress)
        onProgress(
            ModpackInstallProgress(
                stage = "正在解压 overrides",
                detail = targetVersionId
            )
        )
        extractOverrides(archive, root, pack.basePath, pack.packageType, pack.overrideRoot)
        val modsCount = VersionModsManager.list(targetVersionId).size
        android.util.Log.i("ModpackInstall", "after install modsCount=$modsCount in $targetVersionId")
        if (pack.files.isNotEmpty() && modsCount == 0) {
            error("模组下载后仍为空（目标 $targetVersionId），请检查网络或换镜像源后重试")
        }
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
                detail = "$targetVersionId · ${VersionModsManager.list(targetVersionId).size} 个模组"
            )
        )
        return targetVersionId
    }

    private suspend fun resolveOrCreateModpackTarget(
        requestedTargetVersionId: String?,
        deps: ModpackDependencies,
        onProgress: (ModpackInstallProgress) -> Unit
    ): String {
        // PC always installs into a dedicated instance. On phone, only reuse when the user
        // explicitly picks a target — auto mode always ensures the matching loader profile.
        if (requestedTargetVersionId != null) {
            require(isTargetCompatibleWithDependencies(requestedTargetVersionId, deps)) {
                "目标版本与整合包依赖不兼容"
            }
            return requestedTargetVersionId
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
            // Spec uses fabric-loader; some third-party packs still write "fabric".
            fabricLoader = raw.optString("fabric-loader").ifBlank { null }
                ?: raw.optString("fabric").ifBlank { null },
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
            deps.neoforge != null -> loader == CommunityLoader.NEOFORGE
            deps.forge != null -> loader == CommunityLoader.FORGE
            deps.fabricLoader != null -> loader == CommunityLoader.FABRIC
            deps.quiltLoader != null -> loader == CommunityLoader.QUILT
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
        return loadersMatchProfile(profile, contentType, version.loaders)
    }

    private fun isProjectCompatible(
        profile: InstalledProfile,
        contentType: CommunityContentType,
        project: ModrinthProject
    ): Boolean {
        if (contentType == CommunityContentType.RESOURCE_PACK ||
            contentType == CommunityContentType.SHADER
        ) {
            // List search is already scoped to profile.minecraftId via Modrinth facets.
            return true
        }
        if (project.gameVersions.isNotEmpty() && profile.minecraftId !in project.gameVersions) {
            return false
        }
        return loadersMatchProfile(profile, contentType, project.loaders)
    }

    private fun loadersMatchProfile(
        profile: InstalledProfile,
        contentType: CommunityContentType,
        loaders: List<String>
    ): Boolean {
        val normalizedLoaders = loaders.map { it.lowercase() }
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

    private fun loaderQueryForProfile(
        profile: InstalledProfile,
        contentType: CommunityContentType
    ): List<String> {
        if (contentType != CommunityContentType.MOD && contentType != CommunityContentType.MODPACK) {
            return emptyList()
        }
        return profile.loader.apiValue?.let { listOf(it) } ?: emptyList()
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

    /** Works in `:game` where [installedProfiles] may be empty — derive from [versionId] on disk. */
    private fun profileForVersionId(versionId: String): InstalledProfile? {
        val id = versionId.trim()
        if (id.isBlank()) return null
        installedProfiles().firstOrNull { it.versionId == id }?.let { return it }
        val minecraftId = runCatching { resolveMinecraftVersionId(id) }
            .getOrNull()
            ?.trim()
            .orEmpty()
        if (minecraftId.isBlank()) return null
        return InstalledProfile(
            versionId = id,
            minecraftId = minecraftId,
            loader = CommunityLoader.fromVersionId(id)
        )
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

    /**
     * Aligns with PC [ModpackArchiveReader]: find `modrinth.index.json` or CurseForge
     * `manifest.json` by basename, allow one nesting folder, then parse per format.
     */
    private fun readModpackArchive(archive: File): ParsedModpack {
        ZipFile(archive).use { zip ->
            data class Candidate(
                val entry: ZipEntry,
                val baseName: String,
                val basePath: String
            )

            val candidates = buildList {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val full = entry.name.replace('\\', '/')
                    val baseName = full.substringAfterLast('/')
                    if (!baseName.equals("modrinth.index.json", ignoreCase = true) &&
                        !baseName.equals("manifest.json", ignoreCase = true)
                    ) {
                        continue
                    }
                    val basePath = full.substringBeforeLast('/', missingDelimiterValue = "")
                        .let { if (it.isEmpty()) "" else "$it/" }
                    // Same rule as PC: at most one nesting folder (slash count ≤ 1).
                    if (basePath.count { it == '/' } > 1) continue
                    add(Candidate(entry, baseName, basePath))
                }
            }.sortedWith(
                // Prefer shallower path, then prefer Modrinth index over CurseForge manifest.
                compareBy<Candidate> { it.basePath.length }
                    .thenBy { if (it.baseName.equals("modrinth.index.json", ignoreCase = true)) 0 else 1 }
            )

            val selected = candidates.firstOrNull()
                ?: error("未找到可识别的整合包清单（modrinth.index.json / manifest.json）")

            android.util.Log.i(
                "ModpackInstall",
                "manifest entry=${selected.entry.name} typeHint=${selected.baseName}"
            )
            val json = zip.getInputStream(selected.entry).bufferedReader().use { it.readText() }
            return if (selected.baseName.equals("modrinth.index.json", ignoreCase = true)) {
                parseModrinthIndex(json, selected.basePath)
            } else {
                parseCurseForgeManifest(json, selected.basePath)
            }
        }
    }

    private fun parseModrinthIndex(json: String, basePath: String): ParsedModpack {
        val root = JSONObject(json)
        val game = root.optString("game").ifBlank { "minecraft" }
        require(game.equals("minecraft", ignoreCase = true)) { "整合包不支持游戏: $game" }
        val depsObj = root.optJSONObject("dependencies")
            ?: error("Modrinth 整合包缺少 dependencies 节点")
        val deps = parseModpackDependencies(depsObj)
        val filesArr = root.optJSONArray("files")
            ?: error("Modrinth 整合包缺少 files 节点")
        var skippedUnsupported = 0
        var skippedNoUrl = 0
        val files = buildList {
            for (i in 0 until filesArr.length()) {
                val item = filesArr.optJSONObject(i) ?: continue
                val path = item.optString("path").trim()
                if (path.isBlank()) continue
                // Client launcher: skip server-only entries (PC Modrinth App does the same).
                val env = item.optJSONObject("env")
                val clientEnv = env?.optString("client").orEmpty()
                if (clientEnv.equals("unsupported", ignoreCase = true)) {
                    skippedUnsupported++
                    continue
                }
                val downloads = item.optJSONArray("downloads")
                if (downloads == null || downloads.length() == 0) {
                    skippedNoUrl++
                    continue
                }
                val primary = downloads.optString(0).trim()
                if (primary.isBlank()) {
                    skippedNoUrl++
                    continue
                }
                val sha1 = item.optJSONObject("hashes")?.optString("sha1")?.takeIf { it.isNotBlank() }
                val urls = buildList {
                    addAll(modrinthDownloadCandidates(primary))
                    for (j in 1 until downloads.length()) {
                        val alt = downloads.optString(j).trim()
                        if (alt.isNotBlank()) addAll(modrinthDownloadCandidates(alt))
                    }
                }.distinct()
                add(PackFile(path, urls, sha1, required = true))
            }
        }
        android.util.Log.i(
            "ModpackInstall",
            "modrinth files kept=${files.size} skippedUnsupported=$skippedUnsupported skippedNoUrl=$skippedNoUrl raw=${filesArr.length()}"
        )
        return ParsedModpack(
            packageType = ModpackPackageType.MODRINTH,
            basePath = basePath,
            overrideRoot = "overrides",
            deps = deps,
            files = files
        )
    }

    private fun parseCurseForgeManifest(json: String, basePath: String): ParsedModpack {
        val root = JSONObject(json)
        val minecraft = root.optJSONObject("minecraft")
            ?: error("CurseForge 整合包缺少 minecraft 节点")
        val mcVersion = minecraft.optString("version").ifBlank {
            error("CurseForge 整合包未提供 Minecraft 版本")
        }
        val loaderDeps = resolveCurseForgeLoaders(minecraft.optJSONArray("modLoaders"))
        val filesArr = root.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (i in 0 until filesArr.length()) {
                val item = filesArr.optJSONObject(i) ?: continue
                val required = item.optBoolean("required", true)
                if (!required) continue
                val projectId = item.optInt("projectID", 0)
                val fileId = item.optInt("fileID", 0)
                if (projectId <= 0 || fileId <= 0) continue
                val url = "https://bmclapi2.bangbang93.com/curseforge/download/$projectId/$fileId"
                add(
                    PackFile(
                        relativePath = "mods/$projectId-$fileId.jar",
                        urls = listOf(url),
                        sha1 = null,
                        required = true
                    )
                )
            }
        }
        val overrideRoot = root.optString("overrides").ifBlank { "overrides" }
        return ParsedModpack(
            packageType = ModpackPackageType.CURSEFORGE,
            basePath = basePath,
            overrideRoot = overrideRoot.trim('/'),
            deps = ModpackDependencies(
                minecraft = mcVersion.trim(),
                forge = loaderDeps.forge,
                neoforge = loaderDeps.neoforge,
                fabricLoader = loaderDeps.fabricLoader,
                quiltLoader = loaderDeps.quiltLoader
            ),
            files = files
        )
    }

    private fun resolveCurseForgeLoaders(
        loaders: JSONArray?
    ): ModpackDependencies {
        if (loaders == null || loaders.length() == 0) {
            return ModpackDependencies("", null, null, null, null)
        }
        var primary: JSONObject? = null
        for (i in 0 until loaders.length()) {
            val item = loaders.optJSONObject(i) ?: continue
            if (item.optBoolean("primary", false)) {
                primary = item
                break
            }
            if (primary == null) primary = item
        }
        val id = primary?.optString("id").orEmpty()
        return when {
            id.startsWith("forge-", ignoreCase = true) ->
                ModpackDependencies("", id.substringAfter('-'), null, null, null)
            id.startsWith("neoforge-", ignoreCase = true) ->
                ModpackDependencies("", null, id.substringAfter('-'), null, null)
            id.startsWith("fabric-", ignoreCase = true) ->
                ModpackDependencies("", null, null, id.substringAfter('-'), null)
            id.startsWith("quilt-", ignoreCase = true) ->
                ModpackDependencies("", null, null, null, id.substringAfter('-'))
            id.isBlank() -> ModpackDependencies("", null, null, null, null)
            else -> error("暂不支持 CurseForge 加载器: $id")
        }
    }

    private fun extractOverrides(
        archive: File,
        targetRoot: File,
        basePath: String,
        packageType: ModpackPackageType,
        overrideRoot: String
    ) {
        val prefixes = when (packageType) {
            ModpackPackageType.MODRINTH -> listOf(
                "${basePath}overrides/",
                "${basePath}client-overrides/"
            )
            ModpackPackageType.CURSEFORGE -> listOf("${basePath}$overrideRoot/")
        }
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val relative = prefixes.firstNotNullOfOrNull { prefix ->
                    if (name.startsWith(prefix, ignoreCase = true)) {
                        name.substring(prefix.length)
                    } else {
                        null
                    }
                } ?: continue
                if (relative.isBlank()) continue
                val out = safeChild(targetRoot, relative)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private suspend fun downloadPackFiles(
        files: List<PackFile>,
        targetRoot: File,
        onProgress: (ModpackInstallProgress) -> Unit
    ) = coroutineScope {
        // Match PC ModpackImportService: download every required file; do not skip by "exists".
        val jobs = files.filter { it.required && it.urls.isNotEmpty() }
        val total = jobs.size
        if (total == 0) {
            android.util.Log.w("ModpackInstall", "downloadPackFiles: no jobs (input=${files.size})")
            return@coroutineScope
        }

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
                    val out = safeChild(targetRoot, job.relativePath)
                    out.parentFile?.mkdirs()
                    // PC always re-downloads. Only keep a file when sha1 is known and matches.
                    val canReuse = !job.sha1.isNullOrBlank() &&
                        out.isFile &&
                        Digests.matchesSha1(out, job.sha1)
                    if (!canReuse) {
                        if (out.exists()) out.delete()
                        downloader.download(
                            urls = job.urls,
                            destination = out,
                            accelerate = false
                        ).getOrThrow()
                        if (!job.sha1.isNullOrBlank() && !Digests.matchesSha1(out, job.sha1)) {
                            out.delete()
                            error("校验失败: ${job.relativePath}")
                        }
                    }
                    val done = completed.incrementAndGet()
                    onProgress(
                        ModpackInstallProgress(
                            stage = if (canReuse) "模组已缓存" else "正在并行下载模组文件",
                            current = done,
                            total = total,
                            detail = job.relativePath.substringAfterLast('/')
                        )
                    )
                }
            }
        }.awaitAll()
    }

    /**
     * Modrinth CDN → official / alt / MCIM candidates (CN-safe, with fallbacks).
     */
    private fun modrinthDownloadCandidates(rawUrl: String): List<String> =
        ModrinthUrlCandidates.fileDownloads(rawUrl)

    private fun downloadCandidatesForFile(
        version: ModrinthProjectVersion,
        file: ModrinthVersionFile
    ): List<String> {
        val ownerId = version.projectId ?: version.id
        if (!CurseForgeClient.isCurseForgeId(ownerId)) {
            return modrinthDownloadCandidates(file.url)
        }
        val bare = version.id.removePrefix(CurseForgeClient.ID_PREFIX)
        val parts = bare.split(':', limit = 2)
        val modId = parts.getOrNull(0)
        val fileId = parts.getOrNull(1)?.toIntOrNull()
        return CurseForgeClient.fileDownloads(
            rawUrl = file.url,
            modId = modId,
            fileId = fileId
        )
    }

    /** Path join + zip-slip guard, aligned with PC ModpackPathGuard. */
    private fun safeChild(root: File, relativePath: String): File {
        val clean = relativePath.trim().removePrefix("/").replace('\\', '/')
        require(clean.isNotBlank()) { "非法文件路径: 空路径" }
        require(!clean.split('/').any { it == ".." }) { "非法文件路径: $relativePath" }
        val rootAbs = root.absoluteFile
        val rootPrefix = rootAbs.path.let { p ->
            if (p.endsWith(File.separatorChar)) p else p + File.separatorChar
        }
        val child = File(rootAbs, clean).absoluteFile
        require(child.path.startsWith(rootPrefix) || child.path == rootAbs.path) {
            "非法文件路径: $relativePath"
        }
        return child
    }

    private data class ParsedModpack(
        val packageType: ModpackPackageType,
        val basePath: String,
        val overrideRoot: String,
        val deps: ModpackDependencies,
        val files: List<PackFile>
    )

    private data class PackFile(
        val relativePath: String,
        val urls: List<String>,
        val sha1: String?,
        val required: Boolean = true
    )

    private enum class ModpackPackageType {
        MODRINTH,
        CURSEFORGE
    }

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
