package com.booxin.launcher.core.skinstore

import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.auth.MicrosoftAuthService
import com.booxin.launcher.core.net.HttpClients
import com.booxin.launcher.core.skin.OfflineSkinStore
import com.booxin.launcher.data.model.AccountType
import com.booxin.launcher.data.model.LauncherAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object SkinApplyHelper {

    data class ApplyResult(
        val appliedOffline: Int,
        val appliedMicrosoft: Int,
        val failed: List<String> = emptyList()
    ) {
        val appliedCount: Int get() = appliedOffline + appliedMicrosoft
    }

    fun applyableAccounts(): List<LauncherAccount> =
        AppContainer.repository.accounts.value.filter {
            it.type == AccountType.OFFLINE || it.type == AccountType.MICROSOFT
        }

    suspend fun applyToAccounts(
        pngBytes: ByteArray,
        model: String,
        targets: List<LauncherAccount>
    ): ApplyResult = withContext(Dispatchers.IO) {
        val slim = model.equals("slim", ignoreCase = true)
        var offlineOk = 0
        var msOk = 0
        val failed = mutableListOf<String>()
        for (acc in targets) {
            when (acc.type) {
                AccountType.OFFLINE -> {
                    runCatching {
                        val file = OfflineSkinStore.saveBytes(acc.id, pngBytes)
                        AppContainer.repository.updateOfflineSkin(
                            accountId = acc.id,
                            skinPath = file.absolutePath,
                            skinMode = "custom",
                            skinModel = if (slim) "slim" else "classic"
                        )
                        offlineOk++
                    }.onFailure {
                        failed += "${acc.name}: ${it.message ?: "离线应用失败"}"
                    }
                }
                AccountType.MICROSOFT -> {
                    runCatching {
                        uploadMicrosoftSkin(acc, pngBytes, slim)
                        msOk++
                    }.onFailure {
                        failed += "${acc.name}: ${it.message ?: "正版上传失败"}"
                    }
                }
                AccountType.THIRD_PARTY -> {
                    failed += "${acc.name}: 第三方账号暂不支持改皮肤"
                }
            }
        }
        ApplyResult(offlineOk, msOk, failed)
    }

    private suspend fun uploadMicrosoftSkin(
        account: LauncherAccount,
        pngBytes: ByteArray,
        slim: Boolean
    ) {
        val fresh = MicrosoftAuthService.ensureSession(account, forceRefresh = false)
            .getOrElse { throw it }
        AppContainer.repository.upsertMicrosoftAccount(fresh)
        val token = fresh.accessToken?.trim().orEmpty()
        require(token.isNotEmpty()) { "微软账号令牌无效，请重新登录" }

        val variant = if (slim) "slim" else "classic"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("variant", variant)
            .addFormDataPart(
                "file",
                "skin.png",
                pngBytes.toRequestBody("image/png".toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url("https://api.minecraftservices.com/minecraft/profile/skins")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        HttpClients.shared.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(text).optString("errorMessage")
                        .ifBlank { JSONObject(text).optString("error") }
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: text.ifBlank { "HTTP ${resp.code}" }
                error(msg)
            }
        }
    }
}
