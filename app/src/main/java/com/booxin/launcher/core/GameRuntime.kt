package com.booxin.launcher.core

import com.booxin.launcher.core.java.JavaEnvironmentManager

/**
 * Future entry point for actually launching / installing Minecraft on Android.
 * Keep UI layers calling this facade so runtime engines can be swapped later.
 */
interface GameRuntime {
    suspend fun prepare(versionId: String): Result<Unit>
    suspend fun launch(versionId: String, username: String): Result<Unit>
}

class StubGameRuntime(
    private val javaEnvironment: JavaEnvironmentManager
) : GameRuntime {
    override suspend fun prepare(versionId: String): Result<Unit> {
        return javaEnvironment.ensureForMinecraft(versionId).map { Unit }
    }

    override suspend fun launch(versionId: String, username: String): Result<Unit> {
        val java = javaEnvironment.ensureForMinecraft(versionId).getOrElse {
            return Result.failure(it)
        }
        return Result.failure(
            UnsupportedOperationException(
                "Java 环境已就绪 (${java.homeDir.name})，游戏启动引擎尚未接入 ($versionId / $username)"
            )
        )
    }
}
