package com.booxin.launcher.data.repository

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

/**
 * Launcher repository: Mojang/BMCL version list + local install detection.
 */
class LauncherRepository(
    private val manifestClient: VersionManifestClient = VersionManifestClient(),
    private val gameInstaller: VanillaGameInstaller = VanillaGameInstaller()
) {

    private val _versions = MutableStateFlow<List<GameVersion>>(emptyList())
    val versions: StateFlow<List<GameVersion>> = _versions.asStateFlow()

    private val _accounts = MutableStateFlow<List<LauncherAccount>>(emptyList())
    val accounts: StateFlow<List<LauncherAccount>> = _accounts.asStateFlow()

    private val _session = MutableStateFlow(LauncherSession())
    val session: StateFlow<LauncherSession> = _session.asStateFlow()

    val installProgress = gameInstaller.progress

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
            // Prefer releases first in UI; keep full list available.
            _versions.value = mapped.sortedWith(
                compareBy<GameVersion> {
                    when (it.type) {
                        VersionType.RELEASE -> 0
                        VersionType.SNAPSHOT -> 1
                        VersionType.OLD_BETA -> 2
                        VersionType.OLD_ALPHA -> 3
                    }
                }.thenByDescending { it.releaseTime.orEmpty() }
            )
            if (_session.value.selectedVersionId == null) {
                val latest = manifest.latestRelease
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
        val remote = _versions.value.firstOrNull { it.id == versionId }
        val result = gameInstaller.install(versionId, remote?.url)
        if (result.isSuccess) {
            _versions.update { list ->
                list.map { if (it.id == versionId) it.copy(installed = true) else it }
            }
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
        return _versions.value.firstOrNull { it.id == id }
    }
}
