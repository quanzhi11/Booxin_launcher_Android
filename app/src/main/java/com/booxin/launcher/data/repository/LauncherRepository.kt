package com.booxin.launcher.data.repository

import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.GameVersion
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.data.model.LauncherSession
import com.booxin.launcher.data.model.VersionType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory repository scaffolding.
 * Later: wire Mojang manifest API, BMCLAPI mirrors, and local installs.
 */
class LauncherRepository {

    private val _versions = MutableStateFlow(
        listOf(
            GameVersion("1.21.4", VersionType.RELEASE),
            GameVersion("1.21.3", VersionType.RELEASE),
            GameVersion("1.20.4", VersionType.RELEASE),
            GameVersion("24w46a", VersionType.SNAPSHOT),
            GameVersion("1.12.2", VersionType.RELEASE)
        )
    )
    val versions: StateFlow<List<GameVersion>> = _versions.asStateFlow()

    private val _accounts = MutableStateFlow<List<LauncherAccount>>(emptyList())
    val accounts: StateFlow<List<LauncherAccount>> = _accounts.asStateFlow()

    private val _session = MutableStateFlow(LauncherSession(selectedVersionId = "1.21.4"))
    val session: StateFlow<LauncherSession> = _session.asStateFlow()

    suspend fun refreshVersions() {
        delay(400)
        // Placeholder: keep sample data for UI wiring.
        _versions.value = _versions.value
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
