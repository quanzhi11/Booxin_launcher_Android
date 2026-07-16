package com.booxin.launcher.data.repository

import com.booxin.launcher.core.LauncherPaths
import com.booxin.launcher.core.download.game.VanillaGameInstaller
import com.booxin.launcher.core.download.game.VersionManifestClient
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.data.model.LauncherSession
import com.booxin.launcher.data.model.VersionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

/**
 * Launcher repository: local installs + Mojang/BMCL remote version list.
 */
class LauncherRepository(
    private val manifestClient: VersionManifestClient = VersionManifestClient(),
    private val gameInstaller: VanillaGameInstaller = VanillaGameInstaller()
) {

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

    fun refreshInstalledVersions() {
        val dirs = LauncherPaths.versionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        _installedVersions.value = dirs.mapNotNull { dir ->
            val id = dir.name
            if (!gameInstaller.isInstalled(id)) return@mapNotNull null
            val remote = _remoteVersions.value.firstOrNull { it.id == id }
            GameVersion(
                id = id,
                type = remote?.type ?: readLocalType(dir),
                installed = true,
                releaseTime = remote?.releaseTime,
                url = remote?.url
            )
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
            _remoteVersions.update { list ->
                list.map { if (it.id == versionId) it.copy(installed = true) else it }
            }
            refreshInstalledVersions()
            selectVersion(versionId)
        }
        return result
    }

    fun selectVersion(versionId: String) {
        _session.update { it.copy(selectedVersionId = versionId) }
    }

    fun addOfflineAccount(name: String) {
        val account = LauncherAccount(
            id = "offline-${System.currentTimeMillis()}",
            name = name.ifBlank { "Player" },
            type = AccountType.OFFLINE,
            selected = _accounts.value.isEmpty()
        )
        _accounts.update { current ->
            val cleared = current.map { it.copy(selected = false) }
            cleared + account
        }
        _session.update { it.copy(selectedAccountId = account.id) }
    }

    fun selectedVersion(): GameVersion? {
        val id = _session.value.selectedVersionId ?: return null
        return _installedVersions.value.firstOrNull { it.id == id }
            ?: _remoteVersions.value.firstOrNull { it.id == id }
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
}
