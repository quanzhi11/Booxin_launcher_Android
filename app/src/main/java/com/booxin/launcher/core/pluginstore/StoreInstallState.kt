package com.booxin.launcher.core.pluginstore

import com.booxin.launcher.core.plugin.PluginManager
import com.booxin.launcher.core.uiplugin.UiPluginManager

enum class StoreInstallAction {
    /** Not installed locally — show download. */
    DOWNLOAD,
    /** Installed and up to date — show 已下载 (disabled). */
    INSTALLED,
    /** Installed but store version is newer — show 更新. */
    UPDATE
}

object StoreInstallState {
    fun actionFor(plugin: StorePlugin): StoreInstallAction {
        val installedUi = UiPluginManager.listInstalled()
        val uiHit = installedUi.firstOrNull { it.storePluginId == plugin.id }
            ?: installedUi.firstOrNull {
                it.manifest.name.equals(plugin.name, ignoreCase = true)
            }
        if (uiHit != null) {
            val localVer = uiHit.storeVersion ?: uiHit.manifest.version
            val remoteVer = plugin.version.ifBlank { "1.0.0" }
            return if (UiPluginManager.compareVersions(remoteVer, localVer) > 0) {
                StoreInstallAction.UPDATE
            } else {
                StoreInstallAction.INSTALLED
            }
        }
        val type = plugin.type.lowercase()
        if (type == "renderer" || type == "driver") {
            val name = plugin.name.trim()
            if (name.isNotEmpty()) {
                val installed = PluginManager.all()
                    .any {
                        it.installed && it.name.equals(name, ignoreCase = true)
                    }
                if (installed) return StoreInstallAction.INSTALLED
            }
        }
        return StoreInstallAction.DOWNLOAD
    }
}
