package com.booxin.launcher.core.skin

import android.util.Log
import com.booxin.launcher.AppContainer
import com.booxin.launcher.data.model.AccountType
import java.io.File

/**
 * Prepares vanilla offline skins via authlib-injector + local Yggdrasil API.
 */
object OfflineSkinLaunch {

    data class Prepared(
        /** Full JVM option, e.g. -javaagent:/path/authlib-injector.jar=http://127.0.0.1:port/ */
        val javaAgentArg: String,
        val apiRoot: String,
        /** HMCL/FCL: non-zero token so 1.20+ does not 401 against Mojang before injector hooks. */
        val accessToken: String,
        /** HMCL/FCL offline skin uses msa, not legacy. */
        val userType: String = "msa",
        /**
         * - [authlibinjector.side]=client
         * - [authlibinjector.profileKey]=enabled — counter-intuitive but required:
         *   when profileKey is NOT enabled, authlib-injector installs ProfileKeyFilter which
         *   returns dummy publicKeySignatureV2=`AA==`. Vanilla LAN hosts reject that signature
         *   with "无效的玩家档案公钥签名". Enabling the option skips the filter so certificates
         *   fall through to [OfflineYggdrasilServer] (404 / no key) and the client behaves like
         *   pure offline (skins still work via textures API).
         */
        val extraJvmArgs: List<String> = listOf(
            "-Dauthlibinjector.side=client",
            "-Dauthlibinjector.profileKey=enabled"
        )
    )

    @Volatile
    private var server: OfflineYggdrasilServer? = null

    /**
     * @param skinPathHint absolute PNG path from the main-process launch Intent (preferred).
     * @return null when not offline, no skin imported, or setup failed (caller may still launch).
     */
    suspend fun prepare(
        username: String,
        uuidNoDash: String?,
        accessToken: String?,
        userType: String?,
        skinPathHint: String? = null
    ): Prepared? {
        shutdown()
        val offline = accessToken.isNullOrBlank() ||
            accessToken == "0" ||
            userType.equals("legacy", ignoreCase = true)
        if (!offline) return null

        val skinFile = resolveSkinFile(username, skinPathHint) ?: return null
        val skinBytes = runCatching { skinFile.readBytes() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val jar = AuthlibInjectorInstaller.ensure().getOrElse {
            Log.w("OfflineSkinLaunch", "authlib-injector 不可用: ${it.message}")
            return null
        }
        val uuid = uuidNoDash?.replace("-", "")?.ifBlank { null }
            ?: com.booxin.launcher.core.launch.OfflineAuth.uuidNoDash(username)

        val ygg = OfflineYggdrasilServer(
            username = username.trim().ifBlank { "Player" },
            uuidNoDash = uuid.lowercase(),
            skinBytes = skinBytes
        )
        return try {
            val root = ygg.start()
            server = ygg
            Prepared(
                javaAgentArg = "-javaagent:${jar.absolutePath}=$root",
                apiRoot = root,
                accessToken = java.util.UUID.randomUUID().toString().replace("-", "")
            )
        } catch (t: Throwable) {
            Log.w("OfflineSkinLaunch", "启动本地皮肤服务失败", t)
            runCatching { ygg.stop() }
            null
        }
    }

    fun shutdown() {
        val s = server
        server = null
        runCatching { s?.stop() }
    }

    fun resolveSkinFile(username: String, skinPathHint: String? = null): File? {
        skinPathHint?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let { return it }
        val safe = username.trim()
        if (safe.isNotEmpty()) {
            File(OfflineSkinStore.skinsDir(), "by-name/$safe.png").takeIf { it.isFile }?.let { return it }
        }
        val accounts = runCatching { AppContainer.repository.accounts.value }.getOrNull().orEmpty()
        val account = accounts.firstOrNull {
            it.type == AccountType.OFFLINE && it.name.equals(username, ignoreCase = true)
        }
        if (account != null) {
            account.skinPath?.let(::File)?.takeIf { it.isFile }?.let { return it }
            OfflineSkinStore.skinFile(account.id).takeIf { it.isFile }?.let { return it }
        }
        return null
    }
}
