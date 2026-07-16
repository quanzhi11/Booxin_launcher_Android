package com.booxin.launcher

import com.booxin.launcher.core.GameRuntime
import com.booxin.launcher.core.StubGameRuntime
import com.booxin.launcher.core.download.game.VanillaGameInstaller
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.data.repository.LauncherRepository

/**
 * Simple process-wide service locator for the scaffold.
 * Replace with Hilt/Koin when the project grows.
 */
object AppContainer {
    val javaEnvironment: JavaEnvironmentManager by lazy { JavaEnvironmentManager() }
    val gameInstaller: VanillaGameInstaller by lazy { VanillaGameInstaller() }
    val repository: LauncherRepository by lazy {
        LauncherRepository(gameInstaller = gameInstaller)
    }
    val gameRuntime: GameRuntime by lazy {
        StubGameRuntime(javaEnvironment, repository)
    }
}
