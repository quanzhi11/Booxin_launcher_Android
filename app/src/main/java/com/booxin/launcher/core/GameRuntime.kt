package com.booxin.launcher.core

import android.content.Context
import android.content.Intent
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.data.repository.LauncherRepository
import com.booxin.launcher.ui.launch.LaunchActivity

/**
 * Prepares game files / Java, then opens [LaunchActivity] to run the client process.
 */
interface GameRuntime {
    suspend fun prepare(versionId: String): Result<Unit>
    suspend fun launch(context: Context, versionId: String, username: String): Result<Unit>
}

class BooxinGameRuntime(
    private val javaEnvironment: JavaEnvironmentManager,
    private val repository: LauncherRepository
) : GameRuntime {
    override suspend fun prepare(versionId: String): Result<Unit> {
        javaEnvironment.ensureForMinecraft(versionId).getOrElse {
            return Result.failure(it)
        }
        return repository.installVersion(versionId)
    }

    override suspend fun launch(context: Context, versionId: String, username: String): Result<Unit> {
        return runCatching {
            // Ensure local files exist; full download only if missing pieces.
            val installed = repository.installedVersions.value.any { it.id == versionId }
            if (!installed) {
                prepare(versionId).getOrThrow()
            } else {
                javaEnvironment.ensureForMinecraft(versionId).getOrThrow()
            }
            val intent = Intent(context, LaunchActivity::class.java).apply {
                putExtra(LaunchActivity.EXTRA_VERSION_ID, versionId)
                putExtra(LaunchActivity.EXTRA_USERNAME, username)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
