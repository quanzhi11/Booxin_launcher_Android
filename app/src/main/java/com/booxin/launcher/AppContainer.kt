package com.booxin.launcher

import com.booxin.launcher.core.BooxinGameRuntime
import com.booxin.launcher.core.GameRuntime
import com.booxin.launcher.core.ai.AiBackendClient
import com.booxin.launcher.core.ai.AiChatService
import com.booxin.launcher.core.ai.AiModelSettings
import com.booxin.launcher.core.ai.AiWebSearchService
import com.booxin.launcher.data.repository.CommunityRepository
import com.booxin.launcher.core.download.game.VanillaGameInstaller
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.core.multiplayer.BooxinMultiplayerApi
import com.booxin.launcher.core.multiplayer.MultiplayerAuthManager
import com.booxin.launcher.core.multiplayer.MultiplayerSessionStore
import com.booxin.launcher.data.repository.LauncherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程内服务定位。
 * Replace with Hilt/Koin when the project grows.
 */
object AppContainer {
    /** Survives Fragment navigation — install / asset jobs must not cancel when leaving Download. */
    val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val javaEnvironment: JavaEnvironmentManager by lazy { JavaEnvironmentManager() }
    val gameInstaller: VanillaGameInstaller by lazy { VanillaGameInstaller() }
    val repository: LauncherRepository by lazy {
        LauncherRepository(
            appContext = BooxinApp.getAppContext(),
            gameInstaller = gameInstaller
        )
    }
    val gameRuntime: GameRuntime by lazy {
        BooxinGameRuntime(javaEnvironment, repository)
    }
    val communityRepository: CommunityRepository by lazy {
        CommunityRepository(
            launcherRepository = repository,
            javaEnvironment = javaEnvironment
        )
    }
    val multiplayerAuth: MultiplayerAuthManager by lazy {
        MultiplayerAuthManager(
            store = MultiplayerSessionStore(BooxinApp.getAppContext()),
            api = BooxinMultiplayerApi()
        )
    }

    val aiModelSettings: AiModelSettings by lazy { AiModelSettings() }
    val aiBackend: AiBackendClient by lazy { AiBackendClient(aiModelSettings) }
    val aiWebSearch: AiWebSearchService by lazy { AiWebSearchService() }
    val aiChat: AiChatService by lazy {
        AiChatService(
            appContext = BooxinApp.getAppContext(),
            modelSettings = aiModelSettings,
            backend = aiBackend,
            webSearch = aiWebSearch
        ).also { it.loadHistory() }
    }
}
