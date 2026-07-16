package com.booxin.launcher.core

import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.data.repository.LauncherRepository

/**
 * Future entry point for actually launching / installing Minecraft on Android.
 * Keep UI layers calling this facade so runtime engines can be swapped later.
 */
interface GameRuntime {
    suspend fun prepare(versionId: String): Result<Unit>
    suspend fun launch(versionId: String, username: String): Result<Unit>
}

class StubGameRuntime(
    private val javaEnvironment: JavaEnvironmentManager,
    private val repository: LauncherRepository
) : GameRuntime {
    override suspend fun prepare(versionId: String): Result<Unit> {
        javaEnvironment.ensureForMinecraft(versionId).getOrElse {
            return Result.failure(it)
        }
        return repository.installVersion(versionId)
    }

    override suspend fun launch(versionId: String, username: String): Result<Unit> {
        prepare(versionId).getOrElse { return Result.failure(it) }
        val java = javaEnvironment.ensureForMinecraft(versionId).getOrElse {
            return Result.failure(it)
        }
        return Result.failure(
            UnsupportedOperationException(
                "游戏与 Java 已就绪 (${java.homeDir.name})，启动引擎尚未接入 ($versionId / $username)"
            )
        )
    }
}
