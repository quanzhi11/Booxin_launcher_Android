package com.booxin.launcher

import com.booxin.launcher.core.GameRuntime
import com.booxin.launcher.core.StubGameRuntime
import com.booxin.launcher.data.repository.LauncherRepository

/**
 * Simple process-wide service locator for the scaffold.
 * Replace with Hilt/Koin when the project grows.
 */
object AppContainer {
    val repository: LauncherRepository by lazy { LauncherRepository() }
    val gameRuntime: GameRuntime by lazy { StubGameRuntime() }
}
