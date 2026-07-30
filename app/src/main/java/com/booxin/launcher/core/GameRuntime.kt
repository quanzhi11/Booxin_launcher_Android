package com.booxin.launcher.core

import android.content.Context
import android.content.Intent
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.data.repository.LauncherRepository
import com.booxin.launcher.ui.launch.LaunchActivity

/**
 * Prepares game files / Java, then opens [LaunchActivity] to run the client process.
 */
interface GameRuntime {
    suspend fun prepare(versionId: String): Result<Unit>
    suspend fun launch(
        context: Context,
        versionId: String,
        account: LauncherAccount
    ): Result<Unit>
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

    suspend fun prepareForge(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null
    ): Result<String> {
        val java = javaEnvironment.ensureForMinecraft(mcVersion).getOrElse {
            return Result.failure(it)
        }
        return repository.installForgeVersion(mcVersion, loaderVersion, versionJsonUrl, java)
    }

    override suspend fun launch(
        context: Context,
        versionId: String,
        account: LauncherAccount
    ): Result<Unit> {
        return runCatching {
            val installed = repository.installedVersions.value.any { it.id == versionId }
            if (!installed) {
                prepare(versionId).getOrThrow()
            } else {
                javaEnvironment.ensureForMinecraft(versionId).getOrThrow()
            }
            val intent = Intent(context, LaunchActivity::class.java).apply {
                putExtra(LaunchActivity.EXTRA_VERSION_ID, versionId)
                putExtra(LaunchActivity.EXTRA_USERNAME, account.name)
                account.uuid?.let { putExtra(LaunchActivity.EXTRA_UUID, it) }
                account.accessToken?.let { putExtra(LaunchActivity.EXTRA_ACCESS_TOKEN, it) }
                putExtra(LaunchActivity.EXTRA_USER_TYPE, account.userType)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
