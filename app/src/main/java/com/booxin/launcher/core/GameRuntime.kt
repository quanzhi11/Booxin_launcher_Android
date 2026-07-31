package com.booxin.launcher.core

import android.content.Context
import android.content.Intent
import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.data.repository.LauncherRepository
import com.booxin.launcher.core.skin.OfflineSkinStore
import com.booxin.launcher.ui.launch.LaunchActivity
import java.io.File

/**
 * Prepares game files / Java, then opens [LaunchActivity] to run the client process.
 */
interface GameRuntime {
    suspend fun prepare(versionId: String): Result<Unit>
    suspend fun launch(
        context: Context,
        versionId: String,
        account: LauncherAccount,
        serverAddress: String? = null
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
        return repository.ensureVersionReady(versionId)
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
        account: LauncherAccount,
        serverAddress: String?
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
                if (account.type == AccountType.OFFLINE) {
                    val gameDir = File(LauncherPaths.versionsDir, versionId)
                    OfflineSkinStore.installForLaunch(
                        accountId = account.id,
                        username = account.name,
                        versionGameDir = gameDir
                    )
                    // Always emit offline auth (PC: AccessToken=0, legacy, OfflinePlayer UUID).
                    // Never leave these null — Intent omission used to skip defaults on some paths.
                    putExtra(
                        LaunchActivity.EXTRA_UUID,
                        account.uuid?.replace("-", "")?.ifBlank { null }
                            ?: com.booxin.launcher.core.launch.OfflineAuth.uuidNoDash(account.name)
                    )
                    putExtra(LaunchActivity.EXTRA_ACCESS_TOKEN, "0")
                    putExtra(LaunchActivity.EXTRA_USER_TYPE, "legacy")
                } else {
                    account.uuid?.let { putExtra(LaunchActivity.EXTRA_UUID, it) }
                    account.accessToken?.let { putExtra(LaunchActivity.EXTRA_ACCESS_TOKEN, it) }
                    putExtra(LaunchActivity.EXTRA_USER_TYPE, account.userType)
                }
                // Prefer explicit address (官服); else lobby tunnel. :game is a separate process.
                (
                    serverAddress?.takeIf { it.isNotBlank() }
                        ?: AppContainer.multiplayerAuth.directConnectAddress.value?.takeIf { it.isNotBlank() }
                    )?.let { putExtra(LaunchActivity.EXTRA_SERVER_ADDRESS, it) }
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
