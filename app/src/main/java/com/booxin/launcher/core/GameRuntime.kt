package com.booxin.launcher.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import com.booxin.launcher.R
import com.booxin.launcher.core.java.JavaEnvironmentManager
import com.booxin.launcher.core.uiplugin.UiPluginManager
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import com.booxin.launcher.data.repository.LauncherRepository
import com.booxin.launcher.core.skin.OfflineSkinStore
import com.booxin.launcher.core.skin.OfflineSkinService
import com.booxin.launcher.core.skin.OfflineSkinMode
import com.booxin.launcher.core.skin.AuthlibInjectorInstaller
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

    suspend fun prepareNeoForge(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null
    ): Result<String> {
        val java = javaEnvironment.ensureForMinecraft(mcVersion).getOrElse {
            return Result.failure(it)
        }
        return repository.installNeoForgeVersion(mcVersion, loaderVersion, versionJsonUrl, java)
    }

    suspend fun prepareFabric(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null
    ): Result<String> {
        javaEnvironment.ensureForMinecraft(mcVersion).getOrElse {
            return Result.failure(it)
        }
        return repository.installFabricVersion(mcVersion, loaderVersion, versionJsonUrl)
    }

    suspend fun prepareQuilt(
        mcVersion: String,
        loaderVersion: String,
        versionJsonUrl: String? = null
    ): Result<String> {
        javaEnvironment.ensureForMinecraft(mcVersion).getOrElse {
            return Result.failure(it)
        }
        return repository.installQuiltVersion(mcVersion, loaderVersion, versionJsonUrl)
    }

    suspend fun prepareOptiFine(
        mcVersion: String,
        type: String,
        patch: String,
        versionJsonUrl: String? = null
    ): Result<String> {
        val java = javaEnvironment.ensureForMinecraft(mcVersion).getOrElse {
            return Result.failure(it)
        }
        return repository.installOptiFineVersion(mcVersion, type, patch, versionJsonUrl, java)
    }

    override suspend fun launch(
        context: Context,
        versionId: String,
        account: LauncherAccount,
        serverAddress: String?
    ): Result<Unit> {
        val proceed = com.booxin.launcher.core.launch.LegacyGl4esLaunchGate
            .confirmIfNeeded(context, versionId)
        if (!proceed) {
            return Result.failure(kotlinx.coroutines.CancellationException("legacy renderer cancelled"))
        }
        return runCatching {
            // Fast path: only ensure Java here. Asset repair runs inside :game
            // (GameLaunchService) so the UI is not blocked / cancelled.
            javaEnvironment.ensureForMinecraft(versionId).getOrThrow()
            val intent = Intent(context, LaunchActivity::class.java).apply {
                putExtra(LaunchActivity.EXTRA_VERSION_ID, versionId)
                putExtra(LaunchActivity.EXTRA_USERNAME, account.name)
                if (account.type == AccountType.OFFLINE) {
                    val gameDir = File(LauncherPaths.versionsDir, versionId)
                    val mcVersion = OfflineSkinService.resolveMinecraftVersion(versionId)
                    val launchUuid = OfflineSkinService.resolveLaunchUuid(account, mcVersion)
                    val mode = OfflineSkinService.effectiveMode(account)
                    val skinFile = OfflineSkinService.selectedSkinPath(account)?.let(::File)
                    if (skinFile != null && skinFile.isFile &&
                        mode in listOf(OfflineSkinMode.PLAYER, OfflineSkinMode.CUSTOM)
                    ) {
                        OfflineSkinStore.installForLaunch(
                            accountId = account.id,
                            username = account.name,
                            versionGameDir = gameDir
                        )
                        AuthlibInjectorInstaller.ensure().onFailure {
                            android.util.Log.w("GameRuntime", "authlib-injector prefetch: ${it.message}")
                        }
                        putExtra(LaunchActivity.EXTRA_OFFLINE_SKIN_PATH, skinFile.absolutePath)
                    }
                    val pack = OfflineSkinService.prepareForLaunch(gameDir, mcVersion, account)
                    android.util.Log.i("GameRuntime", "offline skin: ${pack.message}")
                    putExtra(LaunchActivity.EXTRA_UUID, launchUuid)
                    putExtra(LaunchActivity.EXTRA_ACCESS_TOKEN, "0")
                    putExtra(LaunchActivity.EXTRA_USER_TYPE, "legacy")
                } else {
                    account.uuid?.let { putExtra(LaunchActivity.EXTRA_UUID, it) }
                    account.accessToken?.let { putExtra(LaunchActivity.EXTRA_ACCESS_TOKEN, it) }
                    putExtra(LaunchActivity.EXTRA_USER_TYPE, account.userType)
                    if (account.type == AccountType.THIRD_PARTY) {
                        account.thirdPartyServerUrl?.takeIf { it.isNotBlank() }?.let {
                            putExtra(LaunchActivity.EXTRA_AUTH_SERVER_URL, it)
                        }
                    }
                }
                // Guests enter via Multiplayer → LAN (MOTD). Do NOT auto --server:
                // that path causes「无效的会话」for offline / third-party accounts.
                // Explicit serverAddress only: 官服 / 「直连备用启动」.
                serverAddress?.takeIf { it.isNotBlank() }?.let {
                    putExtra(LaunchActivity.EXTRA_SERVER_ADDRESS, it)
                }
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            if (UiPluginManager.isFeatureEnabled("pageSlideTransitions")) {
                val opts = ActivityOptions.makeCustomAnimation(
                    context,
                    R.anim.slide_in_right,
                    R.anim.slide_out_left
                )
                context.startActivity(intent, opts.toBundle())
            } else {
                context.startActivity(intent)
            }
        }
    }
}
