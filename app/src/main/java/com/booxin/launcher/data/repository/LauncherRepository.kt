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
    private val forgeInstaller: ForgeGameInstaller = ForgeGameInstaller(),
    private val fabricInstaller: FabricGameInstaller = FabricGameInstaller(),
    private val quiltInstaller: QuiltGameInstaller = QuiltGameInstaller(),
    private val optiFineInstaller: OptiFineGameInstaller = OptiFineGameInstaller()
) {

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_ACCOUNTS, Context.MODE_PRIVATE)
    }

    private val _remoteVersions = MutableStateFlow<List<GameVersion>>(emptyList())
    val remoteVersions: StateFlow<List<GameVersion>> = _remoteVersions.asStateFlow()

    /** @deprecated Prefer [remoteVersions] / [installedVersions]. Kept for callers. */
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
                url = remote?.url
            )
        }
        // Hide vanilla (or other) parents that only exist as inheritsFrom for a loader version.
        // Explicitly installed vanilla stays visible.
        val inheritedParents = allInstalled
            .mapNotNull { VersionJsonMerger.resolveInheritsFrom(it.id) }
            .toSet()
        val explicit = explicitVersionIds()
        _installedVersions.value = allInstalled.filter { version ->
            version.id !in inheritedParents || version.id in explicit
        }
        val selected = _session.value.selectedVersionId
        if (selected == null || _installedVersions.value.none { it.id == selected }) {
            _installedVersions.value.firstOrNull()?.let { selectVersion(it.id) }
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
     * Ensure a version is ready to launch. Forge/Fabric wrappers must not be
     * re-installed via the vanilla installer (their ids are not Mojang versions).
     */
    suspend fun ensureVersionReady(versionId: String): Result<Unit> {
        if (forgeInstaller.isInstalled(versionId) ||
            fabricInstaller.isInstalled(versionId) ||
            quiltInstaller.isInstalled(versionId) ||
            optiFineInstaller.isInstalled(versionId) ||
            VersionJsonMerger.isModLoaderVersion(versionId)
        ) {
            val parent = VersionJsonMerger.resolveMinecraftVersionId(versionId)
            if (parent != versionId && !gameInstaller.isInstalled(parent)) {
                val remote = _remoteVersions.value.firstOrNull { it.id == parent }
                gameInstaller.install(parent, remote?.url).getOrElse {
                    return Result.failure(it)
                }
            }
            val ready = forgeInstaller.isInstalled(versionId) ||
                fabricInstaller.isInstalled(versionId) ||
                quiltInstaller.isInstalled(versionId) ||
                optiFineInstaller.isInstalled(versionId) ||
                VersionJsonMerger.versionJsonFile(versionId) != null
            if (!ready) {
                return Result.failure(IllegalStateException("模组加载器版本未安装: $versionId"))
            }
            refreshInstalledVersions()
            return Result.success(Unit)
        }
        if (gameInstaller.isInstalled(versionId)) {
            return Result.success(Unit)
        }
        return installVersion(versionId)
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
            // Forge/NeoForge vanilla base is a dependency only — do not list it as a separate version.
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
    }

    fun deleteInstalledVersion(versionId: String): Result<Unit> = runCatching {
        val versionDir = File(LauncherPaths.versionsDir, versionId)
        if (!versionDir.isDirectory) {
            error("版本不存在: $versionId")
        }
        versionDir.deleteRecursively()
        clearForgeInstallerCache(versionId)
        unmarkExplicitVersion(versionId)
        _remoteVersions.update { list ->
            list.map { if (it.id == versionId) it.copy(installed = false) else it }
        }
        refreshInstalledVersions()
    }

    private fun clearForgeInstallerCache(versionId: String) {
        val neoMarker = "-neoforge-"
        if (versionId.contains(neoMarker, ignoreCase = true)) {
            val parts = versionId.split(neoMarker, ignoreCase = true, limit = 2)
            if (parts.size == 2) {
                File(LauncherPaths.rootDir, "cache/neoforge/installer-${parts[0]}-${parts[1]}.jar")
                    .takeIf { it.isFile }
                    ?.delete()
            }
            return
        }
        val marker = "-forge-"
        if (!versionId.contains(marker, ignoreCase = true)) return
        val parts = versionId.split(marker, ignoreCase = true, limit = 2)
        if (parts.size != 2) return
        File(LauncherPaths.rootDir, "cache/forge/installer-${parts[0]}-${parts[1]}.jar")
            .takeIf { it.isFile }
            ?.delete()
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
                                if (type == AccountType.MICROSOFT) "msa" else "legacy"
                            ),
                            hasMinecraft = o.optBoolean(
                                "hasMinecraft",
                                type != AccountType.MICROSOFT
                            ),
                            skinPath = o.optString("skinPath").ifBlank { null }
                                ?: com.booxin.launcher.core.skin.OfflineSkinStore
                                    .skinFile(o.getString("id"))
                                    .takeIf { it.isFile }
                                    ?.absolutePath
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
                    account.copy(
                        uuid = uuid,
                        accessToken = "0",
                        userType = "legacy",
                        hasMinecraft = true
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
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun setOfflineSkin(accountId: String, skinPath: String?) {
        _accounts.update { list ->
            list.map {
                if (it.id == accountId && it.type == AccountType.OFFLINE) {
                    it.copy(skinPath = skinPath)
                } else it
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
