package com.booxin.launcher.core

/**
 * Future entry point for actually launching / installing Minecraft on Android.
 * Keep UI layers calling this facade so runtime engines can be swapped later.
 */
interface GameRuntime {
    suspend fun prepare(versionId: String): Result<Unit>
    suspend fun launch(versionId: String, username: String): Result<Unit>
}

class StubGameRuntime : GameRuntime {
    override suspend fun prepare(versionId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("下载/安装引擎尚未接入 ($versionId)"))
    }

    override suspend fun launch(versionId: String, username: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("启动引擎尚未接入 ($versionId / $username)"))
    }
}
