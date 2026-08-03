package com.booxin.launcher.core.download.modloader

/**
 * Forge processor exit-code channel (UDP 127.0.0.1:29118).
 */
internal object ForgeInstallSocketServer {
    const val PORT = 29118
    const val TIMEOUT_MINUTES = 20L
}
