package com.booxin.launcher.data.repository

import android.content.Context
import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.VanillaGameInstaller
import com.booxin.launcher.core.download.game.VersionJsonMerger
import com.booxin.launcher.core.download.game.VersionManifestClient
import com.booxin.launcher.core.download.modloader.FabricGameInstaller
import com.booxin.launcher.core.download.modloader.ForgeGameInstaller
import com.booxin.launcher.core.download.modloader.OptiFineGameInstaller
import com.booxin.launcher.core.download.modloader.QuiltGameInstaller
import com.booxin.launcher.core.java.InstalledJavaRuntime
import com.booxin.launcher.core.version.VersionUiMetaStore
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.data.model.LauncherSession
import com.booxin.launcher.data.model.VersionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Launcher repository: local installs + Mojang/BMCL remote version list + accounts.
 */
class LauncherRepository(
    private val appContext: Context,
    private val manifestClient: VersionManifestClient = VersionManifestClient(),
    private val gameInstaller: VanillaGameInstaller = VanillaGameInstaller(),
) {
    // Share the same vanilla installer so mod-loader base installs report on installProgress.
    private val forgeInstaller = ForgeGameInstaller(vanillaInstaller = gameInstaller)
    private val fabricInstaller = FabricGameInstaller(vanillaInstaller = gameInstaller)
    private val quiltInstaller = QuiltGameInstaller(vanillaInstaller = gameInstaller)
    private val optiFineInstaller = OptiFineGameInstaller(vanillaInstaller = gameInstaller)

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_ACCOUNTS, Context.MODE_PRIVATE)
    }

    private val _remoteVersions = MutableStateFlow<List<GameVersion>>(emptyList())
    val remoteVersions: StateFlow<List<GameVersion>> = _remoteVersions.asStateFlow()

    /** @deprecated 请用 [remoteVersions] / [installedVersions]。 */
    val versions: StateFlow<List<GameVersion>> = _remoteVersions.asStateFlow()

    private val _installedVersions = MutableStateFlow<List<GameVersion>>(emptyList())
    val installedVersions: StateFlow<List<GameVersion>> = _installedVersions.asStateFlow()

    private val _accounts = MutableStateFlow<List<LauncherAccount>>(emptyList())
    val accounts: StateFlow<List<LauncherAccount>> = _accounts.asStateFlow()

    private val _session = MutableStateFlow(LauncherSession())
    val session: StateFlow<LauncherSession> = _session.asStateFlow()

    val installProgress = gameInstaller.progress
    val forgeInstallProgress = forgeInstaller.progress
    val fabricInstallProgress = fabricInstaller.progress
    val quiltInstallProgress = quiltInstaller.progress
    val optiFineInstallProgress = optiFineInstaller.progress

    init {
        loadAccounts()
    }

    fun refreshInstalledVersions() {
        val meta = VersionUiMetaStore.load()
        val dirs = LauncherPaths.versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val allInstalled = dirs.mapNotNull { dir ->
            val id = dir.name
            val installed = gameInstaller.isInstalled(id) ||
                forgeInstaller.isInstalled(id) ||
                fabricInstaller.isInstalled(id) ||
                quiltInstaller.isInstalled(id) ||
                optiFineInstaller.isInstalled(id)
            if (!installed) return@mapNotNull null
            val remote = _remoteVersions.value.firstOrNull { it.id == id }
            GameVersion(
                id = id,
                type = remote?.type ?: readLocalType(dir),
                installed = true,
                releaseTime = remote?.releaseTime,
                url = remote?.url,
                customDisplayName = meta.aliases[id],
                isDefault = meta.defaultVersionId == id
            )
        }
        // Hide vanilla (or other) parents that only exist as inheritsFrom for a loader version.
        // Explicitly installed vanilla stays visible.
        val inheritedParents = allInstalled
            .mapNotNull { VersionJsonMerger.resolveInheritsFrom(it.id) }
            .toSet()
        val explicit = explicitVersionIds()
        val visible = allInstalled.filter { version ->
            version.id !in inheritedParents || version.id in explicit
        }
        val orderedIds = VersionUiMetaStore.sortInstalled(visible.map { it.id }, meta)
        val byId = visible.associateBy { it.id }
        _installedVersions.value = orderedIds.mapIndexedNotNull { index, id ->
            byId[id]?.copy(sortIndex = index, isDefault = meta.defaultVersionId == id)
        }
        val selected = _session.value.selectedVersionId
            ?: meta.selectedVersionId
            ?: meta.defaultVersionId
        if (selected == null || _installedVersions.value.none { it.id == selected }) {
            val fallback = meta.defaultVersionId
                ?.takeIf { id -> _installedVersions.value.any { it.id == id } }
                ?: _installedVersions.value.firstOrNull()?.id
            fallback?.let { selectVersion(it) }
        } else if (_session.value.selectedVersionId != selected) {
            selectVersion(selected)
        }
    }

    suspend fun refreshVersions(): Result<Unit> {
        return runCatching {
            val manifest = manifestClient.fetchManifest().getOrThrow()
            val mapped = manifest.versions.map { remote ->
                GameVersion(
                    id = remote.id,
                    type = remote.type,
                    installed = gameInstaller.isInstalled(remote.id),
                    releaseTime = remote.releaseTime,
                    url = remote.url
                )
            }
            _remoteVersions.value = mapped.sortedWith(
                compareBy<GameVersion> {
                    when (it.type) {
                        VersionType.RELEASE -> 0
                        VersionType.SNAPSHOT -> 1
                        VersionType.OLD_BETA -> 2
                        VersionType.OLD_ALPHA -> 3
                    }
                }.thenByDescending { it.releaseTime.orEmpty() }
            )
            refreshInstalledVersions()
            if (_session.value.selectedVersionId == null) {
                val latestInstalled = _installedVersions.value.firstOrNull()?.id
                val latest = latestInstalled
                    ?: manifest.latestRelease
                    ?: mapped.firstOrNull { it.type == VersionType.RELEASE }?.id
                if (latest != null) {
                    _session.update { it.copy(selectedVersionId = latest) }
                }
            }
        }
    }

    suspend fun installSelectedVersion(): Result<Unit> {
        val id = _session.value.selectedVersionId
            ?: return Result.failure(IllegalStateException("尚未选择版本"))
        return installVersion(id)
    }

    suspend fun installVersion(versionId: String): Result<Unit> {
        val remote = _remoteVersions.value.firstOrNull { it.id == versionId }
        val result = gameInstaller.install(versionId, remote?.url)
        if (result.isSuccess) {
            markExplicitVersion(versionId)
            _remoteVersions.update { list ->
                list.map { if (it.id == versionId) it.copy(installed = true) else it }
            }
            refreshInstalledVersions()
            selectVersion(versionId)
        }
        return result
    }

    /**
     * Ensure a version is ready to launch. Loader wrappers are never fed to the
     * vanilla installer (their ids are not Mojang versions).
     *
     * 只有 inheritsFrom 的残缺 profile 不算已就绪 —
     * that previously made taps on the download page report "安装完成" with no work.
     */
    suspend fun ensureVersionReady(versionId: String): Result<Unit> {
        if (isModLoaderInstalled(versionId)) {
            val parent = VersionJsonMerger.resolveMinecraftVersionId(versionId)
            if (parent != versionId && !gameInstaller.isInstalled(parent)) {
                val remote = _remoteVersions.value.firstOrNull { it.id == parent }
                gameInstaller.install(parent, remote?.url).getOrElse {
                    return Result.failure(it)
                }
            }
            if (!isModLoaderInstalled(versionId)) {
                return Result.failure(IllegalStateException("模组加载器版本未安装: $versionId"))
            }
            refreshInstalledVersions()
            return Result.success(Unit)
        }
        if (gameInstaller.isInstalled(versionId)) {
            return Result.success(Unit)
        }
        // Broken stub json with inheritsFrom but no real loader install: repair via vanilla.
        return installVersion(versionId)
    }

    /**
     * Resolve vanilla/parent id and download any missing asset objects.
     * 启动前必须补全资源，否则 TextureManager 会崩。
     */
    suspend fun ensureGameAssets(versionId: String): Result<Unit> {
        val id = VersionJsonMerger.resolveMinecraftVersionId(versionId)
        val json = File(LauncherPaths.versionsDir, "$id/$id.json")
        if (!json.isFile) {
            return Result.failure(IllegalStateException("缺少版本元数据，无法校验资源: $id"))
        }
        gameInstaller.ensureAssets(id).getOrElse { return Result.failure(it) }
        if (!gameInstaller.assetsComplete(id)) {
            return Result.failure(
                IllegalStateException("游戏资源不完整（assets），请到下载页重新安装 $id")
            )
        }
        return Result.success(Unit)
    }

    private fun isModLoaderInstalled(versionId: String): Boolean {
        return forgeInstaller.isInstalled(versionId) ||
            fabricInstaller.isInstalled(versionId) ||
            quiltInstaller.isInstalled(versionId) ||
            optiFineInstaller.isInstalled(versionId)
    }

    suspend fun installForgeVersion(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null,
        java: InstalledJavaRuntime,
        isNeoForge: Boolean = false
    ): Result<String> {
        val result = forgeInstaller.install(
            mcVersion = mcVersion,
            loaderVersion = loaderVersion,
            versionJsonUrl = versionJsonUrl,
            java = java,
            isNeoForge = isNeoForge
        )
        if (result.isSuccess) {
            val versionId = result.getOrThrow()
            // Forge/NeoForge 的原版基座不当成独立版本列出。
            markExplicitVersion(versionId)
            refreshInstalledVersions()
            selectVersion(versionId)
        }
        return result
    }

    suspend fun installNeoForgeVersion(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null,
        java: InstalledJavaRuntime
    ): Result<String> = installForgeVersion(
        mcVersion = mcVersion,
        loaderVersion = loaderVersion,
        versionJsonUrl = versionJsonUrl,
        java = java,
        isNeoForge = true
    )

    suspend fun installFabricVersion(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null
    ): Result<String> {
        val result = fabricInstaller.install(mcVersion, loaderVersion, versionJsonUrl)
        if (result.isSuccess) {
            val versionId = result.getOrThrow()
            markExplicitVersion(versionId)
            refreshInstalledVersions()
            selectVersion(versionId)
        }
        return result
    }

    suspend fun installQuiltVersion(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null
    ): Result<String> {
        val result = quiltInstaller.install(mcVersion, loaderVersion, versionJsonUrl)
        if (result.isSuccess) {
            val versionId = result.getOrThrow()
            markExplicitVersion(versionId)
            refreshInstalledVersions()
            selectVersion(versionId)
        }
        return result
    }

    suspend fun installOptiFineVersion(
        mcVersion: String,
        type: String,
        patch: String,
        versionJsonUrl: String? = null,
        java: InstalledJavaRuntime
    ): Result<String> {
        val result = optiFineInstaller.install(mcVersion, type, patch, versionJsonUrl, java)
        if (result.isSuccess) {
            val versionId = result.getOrThrow()
            markExplicitVersion(versionId)
            refreshInstalledVersions()
            selectVersion(versionId)
        }
        return result
    }

    fun selectVersion(versionId: String) {
        _session.update { it.copy(selectedVersionId = versionId) }
        VersionUiMetaStore.setSelected(versionId)
    }

    fun setVersionDisplayName(versionId: String, displayName: String?) {
        VersionUiMetaStore.setAlias(versionId, displayName)
        refreshInstalledVersions()
    }

    fun setDefaultVersion(versionId: String?) {
        VersionUiMetaStore.setDefault(versionId)
        if (!versionId.isNullOrBlank()) {
            selectVersion(versionId)
        } else {
            refreshInstalledVersions()
        }
    }

    fun reorderInstalledVersions(orderedIds: List<String>) {
        VersionUiMetaStore.setOrder(orderedIds)
        refreshInstalledVersions()
    }

    fun deleteInstalledVersion(versionId: String): Result<Unit> = runCatching {
        val versionDir = File(LauncherPaths.versionsDir, versionId)
        if (!versionDir.isDirectory) {
            error("版本不存在: $versionId")
        }
        versionDir.deleteRecursively()
        clearForgeInstallerCache(versionId)
        unmarkExplicitVersion(versionId)
        VersionUiMetaStore.removeVersion(versionId)
        _remoteVersions.update { list ->
            list.map { if (it.id == versionId) it.copy(installed = false) else it }
        }
        refreshInstalledVersions()
    }

    private fun clearForgeInstallerCache(versionId: String) {
        fun clear(marker: String, cacheSubdir: String) {
            if (!versionId.contains(marker, ignoreCase = true)) return
            val parts = versionId.split(marker, ignoreCase = true, limit = 2)
            if (parts.size != 2) return
            File(LauncherPaths.rootDir, "cache/$cacheSubdir/installer-${parts[0]}-${parts[1]}.jar")
                .takeIf { it.isFile }
                ?.delete()
        }
        // PC-aligned ids: 1.20.1-NeoForge_21.x / 1.20.1-Forge_47.x
        // Legacy Android ids: 1.20.1-neoforge-21.x / 1.20.1-forge-47.x
        clear("-NeoForge_", "neoforge")
        clear("-neoforge-", "neoforge")
        clear("-Forge_", "forge")
        clear("-forge-", "forge")
    }

    fun addOfflineAccount(name: String) {
        val trimmed = name.trim().ifBlank { "Player" }
        require(com.booxin.launcher.core.launch.OfflineAuth.isValidUsername(trimmed)) {
            "离线用户名需为 3–16 位字母/数字/下划线"
        }
        val account = LauncherAccount(
            id = "offline-${System.currentTimeMillis()}",
            name = trimmed,
            type = AccountType.OFFLINE,
            selected = true,
            // Match PC offline accounts: stable UUID + dummy token for LAN/offline servers.
            uuid = com.booxin.launcher.core.launch.OfflineAuth.uuidNoDash(trimmed),
            accessToken = "0",
            userType = "legacy",
            hasMinecraft = true
        )
        upsertAccount(account)
    }

    fun upsertMicrosoftAccount(account: LauncherAccount) {
        upsertAccount(account.copy(type = AccountType.MICROSOFT, selected = true))
    }

    fun upsertThirdPartyAccount(account: LauncherAccount) {
        upsertAccount(account.copy(type = AccountType.THIRD_PARTY, selected = true))
    }

    fun selectAccount(accountId: String) {
        _accounts.update { list ->
            list.map { it.copy(selected = it.id == accountId) }
        }
        _session.update { it.copy(selectedAccountId = accountId) }
        persistAccounts()
    }

    fun selectedAccount(): LauncherAccount? =
        _accounts.value.firstOrNull { it.selected }
            ?: _accounts.value.firstOrNull()

    fun removeAccount(accountId: String) {
        _accounts.update { list ->
            val next = list.filterNot { it.id == accountId }
            if (next.none { it.selected } && next.isNotEmpty()) {
                listOf(next.first().copy(selected = true)) + next.drop(1)
            } else next
        }
        _session.update { it.copy(selectedAccountId = selectedAccount()?.id) }
        persistAccounts()
    }

    private fun upsertAccount(account: LauncherAccount) {
        _accounts.update { current ->
            val without = current.filterNot { it.id == account.id }.map { it.copy(selected = false) }
            without + account.copy(selected = true)
        }
        _session.update { it.copy(selectedAccountId = account.id) }
        persistAccounts()
    }

    fun selectedVersion(): GameVersion? {
        val id = _session.value.selectedVersionId ?: return null
        return _installedVersions.value.firstOrNull { it.id == id }
            ?: _remoteVersions.value.firstOrNull { it.id == id }
    }

    private fun loadAccounts() {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val type = runCatching {
                        AccountType.valueOf(o.optString("type", "OFFLINE"))
                    }.getOrDefault(AccountType.OFFLINE)
                    add(
                        LauncherAccount(
                            id = o.getString("id"),
                            name = o.optString("name", "Player"),
                            type = type,
                            selected = o.optBoolean("selected", false),
                            uuid = o.optString("uuid").ifBlank { null },
                            accessToken = o.optString("accessToken").ifBlank { null },
                            refreshToken = o.optString("refreshToken").ifBlank { null },
                            accessTokenExpiresAtMs = o.optLong("accessTokenExpiresAtMs", 0L)
                                .takeIf { it > 0 },
                            xuid = o.optString("xuid").ifBlank { null },
                            userType = o.optString(
                                "userType",
                                when (type) {
                                    AccountType.MICROSOFT -> "msa"
                                    AccountType.THIRD_PARTY -> "mojang"
                                    AccountType.OFFLINE -> "legacy"
                                }
                            ),
                            hasMinecraft = o.optBoolean(
                                "hasMinecraft",
                                type != AccountType.MICROSOFT
                            ),
                            skinPath = o.optString("skinPath").ifBlank { null }
                                ?: com.booxin.launcher.core.skin.OfflineSkinStore
                                    .skinFile(o.getString("id"))
                                    .takeIf { it.isFile }
                                    ?.absolutePath,
                            skinMode = o.optString("skinMode").ifBlank { null },
                            skinModel = o.optString("skinModel", "classic").ifBlank { "classic" },
                            skinPlayerName = o.optString("skinPlayerName").ifBlank { null },
                            skinPlayerUuid = o.optString("skinPlayerUuid").ifBlank { null },
                            thirdPartyServerUrl = o.optString("thirdPartyServerUrl").ifBlank { null }
                        )
                    )
                }
            }
            if (list.isNotEmpty()) {
                // Backfill offline UUID/token for accounts created before offline-join fix.
                val normalized = list.map { account ->
                    if (account.type != AccountType.OFFLINE) return@map account
                    val uuid = account.uuid?.replace("-", "")?.ifBlank { null }
                        ?: com.booxin.launcher.core.launch.OfflineAuth.uuidNoDash(account.name)
                    val mode = account.skinMode?.ifBlank { null }
                        ?: if (!account.skinPath.isNullOrBlank()) "custom" else null
                    account.copy(
                        uuid = uuid,
                        accessToken = "0",
                        userType = "legacy",
                        hasMinecraft = true,
                        skinMode = mode
                    )
                }
                val hasSelected = normalized.any { it.selected }
                _accounts.value = if (hasSelected) {
                    normalized
                } else {
                    listOf(normalized.first().copy(selected = true)) + normalized.drop(1)
                }
                _session.update { it.copy(selectedAccountId = selectedAccount()?.id) }
                if (normalized != list) persistAccounts()
            }
        }
    }

    private fun persistAccounts() {
        val arr = JSONArray()
        _accounts.value.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("type", a.type.name)
                    .put("selected", a.selected)
                    .put("uuid", a.uuid)
                    .put("accessToken", a.accessToken)
                    .put("refreshToken", a.refreshToken)
                    .put("accessTokenExpiresAtMs", a.accessTokenExpiresAtMs)
                    .put("xuid", a.xuid)
                    .put("userType", a.userType)
                    .put("hasMinecraft", a.hasMinecraft)
                    .put("skinPath", a.skinPath)
                    .put("skinMode", a.skinMode)
                    .put("skinModel", a.skinModel)
                    .put("skinPlayerName", a.skinPlayerName)
                    .put("skinPlayerUuid", a.skinPlayerUuid)
                    .put("thirdPartyServerUrl", a.thirdPartyServerUrl)
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun setOfflineSkin(accountId: String, skinPath: String?) {
        updateOfflineSkin(
            accountId = accountId,
            skinPath = skinPath,
            skinMode = if (skinPath.isNullOrBlank()) "random" else "custom"
        )
    }

    fun updateOfflineSkin(
        accountId: String,
        skinPath: String? = null,
        skinMode: String? = null,
        skinModel: String? = null,
        skinPlayerName: String? = null,
        skinPlayerUuid: String? = null,
        clearSkinFile: Boolean = false
    ) {
        _accounts.update { list ->
            list.map {
                if (it.id != accountId || it.type != AccountType.OFFLINE) return@map it
                val nextMode = skinMode ?: it.skinMode
                val mode = com.booxin.launcher.core.skin.OfflineSkinMode.parse(nextMode)
                val keepPlayer = mode == com.booxin.launcher.core.skin.OfflineSkinMode.PLAYER
                it.copy(
                    skinPath = when {
                        clearSkinFile -> null
                        skinPath != null -> skinPath
                        else -> it.skinPath
                    },
                    skinMode = nextMode,
                    skinModel = skinModel ?: it.skinModel,
                    skinPlayerName = when {
                        !keepPlayer -> null
                        skinPlayerName != null -> skinPlayerName
                        else -> it.skinPlayerName
                    },
                    skinPlayerUuid = when {
                        !keepPlayer -> null
                        skinPlayerUuid != null -> skinPlayerUuid
                        else -> it.skinPlayerUuid
                    }
                )
            }
        }
        persistAccounts()
    }

    private fun explicitVersionIds(): Set<String> {
        return prefs.getStringSet(KEY_EXPLICIT_VERSIONS, emptySet())?.toSet().orEmpty()
    }

    private fun markExplicitVersion(versionId: String) {
        val next = explicitVersionIds().toMutableSet().apply { add(versionId) }
        prefs.edit().putStringSet(KEY_EXPLICIT_VERSIONS, next).apply()
    }

    private fun unmarkExplicitVersion(versionId: String) {
        val next = explicitVersionIds().toMutableSet().apply { remove(versionId) }
        prefs.edit().putStringSet(KEY_EXPLICIT_VERSIONS, next).apply()
    }

    private fun readLocalType(dir: File): VersionType {
        val jsonFile = File(dir, "${dir.name}.json")
        if (!jsonFile.exists()) return VersionType.RELEASE
        return runCatching {
            val type = JSONObject(jsonFile.readText()).optString("type")
            when (type.lowercase()) {
                "snapshot" -> VersionType.SNAPSHOT
                "old_beta" -> VersionType.OLD_BETA
                "old_alpha" -> VersionType.OLD_ALPHA
                else -> VersionType.RELEASE
            }
        }.getOrDefault(VersionType.RELEASE)
    }

    companion object {
        private const val PREFS_ACCOUNTS = "booxin_accounts"
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_EXPLICIT_VERSIONS = "explicit_version_ids"
    }
}
