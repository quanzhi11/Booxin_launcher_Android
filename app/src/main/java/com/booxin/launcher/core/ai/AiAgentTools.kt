package com.booxin.launcher.core.ai

import com.booxin.launcher.AppContainer
import com.booxin.launcher.core.LauncherPrefs
import com.booxin.launcher.core.version.VersionModsManager
import com.booxin.launcher.core.version.VersionResourcePacksManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiAgentTools {
    const val MAX_ITERATIONS = 12
    const val MAX_TOOL_CALLS = 24

    fun buildToolDefinitions(enableWebSearch: Boolean): JSONArray {
        val tools = JSONArray()
        fun add(name: String, desc: String, props: JSONObject = JSONObject(), required: JSONArray = JSONArray()) {
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("description", desc)
                            .put(
                                "parameters",
                                JSONObject()
                                    .put("type", "object")
                                    .put("properties", props)
                                    .put("required", required)
                            )
                    )
            )
        }
        add("get_launcher_context", "获取启动器当前选中版本、账号、内存与渲染器概况")
        add("list_installed_versions", "列出本机已安装的 Minecraft 版本")
        add(
            "get_version_info",
            "获取指定版本详情",
            JSONObject().put("versionId", JSONObject().put("type", "string")),
            JSONArray().put("versionId")
        )
        add(
            "list_mods",
            "列出版本已安装模组",
            JSONObject().put("versionId", JSONObject().put("type", "string")),
            JSONArray().put("versionId")
        )
        add(
            "list_resource_packs",
            "列出版本资源包",
            JSONObject().put("versionId", JSONObject().put("type", "string")),
            JSONArray().put("versionId")
        )
        add(
            "toggle_mod",
            "启用或禁用模组（按文件名）",
            JSONObject()
                .put("versionId", JSONObject().put("type", "string"))
                .put("modName", JSONObject().put("type", "string")),
            JSONArray().put("versionId").put("modName")
        )
        add("get_current_time", "获取当前本地时间")
        if (enableWebSearch) {
            add(
                "web_search",
                "联网搜索 Minecraft 相关信息",
                JSONObject().put("query", JSONObject().put("type", "string")),
                JSONArray().put("query")
            )
        }
        return tools
    }

    fun buildAgentSystemPrompt(): String = """
        你是 Booxin 手机启动器的 AI Agent。可用工具查询版本/模组/资源包并切换模组状态。
        回答用简体中文，简洁准确。需要动手时先调用工具再总结。
        不要编造未安装的版本或模组。无法完成时如实说明。
    """.trimIndent()

    suspend fun execute(name: String, argsJson: String, webSearch: AiWebSearchService): String {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        return when (name) {
            "get_launcher_context" -> launcherContext()
            "list_installed_versions" -> {
                val list = AppContainer.repository.installedVersions.value
                if (list.isEmpty()) "暂无已安装版本"
                else list.joinToString("\n") { "- ${it.id} (installed=${it.installed})" }
            }
            "get_version_info" -> {
                val id = args.optString("versionId")
                val v = AppContainer.repository.installedVersions.value.firstOrNull { it.id == id }
                v?.let { "versionId=${it.id}, installed=${it.installed}" } ?: "未找到版本: $id"
            }
            "list_mods" -> {
                val id = args.optString("versionId").ifBlank {
                    AppContainer.repository.session.value.selectedVersionId.orEmpty()
                }
                val mods = VersionModsManager.list(id)
                if (mods.isEmpty()) "版本 $id 没有模组"
                else mods.joinToString("\n") { "- ${it.displayName} (${if (it.enabled) "启用" else "禁用"})" }
            }
            "list_resource_packs" -> {
                val id = args.optString("versionId").ifBlank {
                    AppContainer.repository.session.value.selectedVersionId.orEmpty()
                }
                val packs = VersionResourcePacksManager.list(id)
                if (packs.isEmpty()) "版本 $id 没有资源包"
                else packs.joinToString("\n") { "- ${it.displayName} (${if (it.enabled) "启用" else "禁用"})" }
            }
            "toggle_mod" -> {
                val id = args.optString("versionId").ifBlank {
                    AppContainer.repository.session.value.selectedVersionId.orEmpty()
                }
                val modName = args.optString("modName")
                val mod = VersionModsManager.list(id).firstOrNull {
                    it.displayName.equals(modName, true) || it.file.name.equals(modName, true)
                } ?: return "未找到模组: $modName"
                VersionModsManager.toggle(mod).fold(
                    onSuccess = { "已${if (mod.enabled) "禁用" else "启用"}: ${mod.displayName}" },
                    onFailure = { "切换失败: ${it.message}" }
                )
            }
            "get_current_time" -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            "web_search" -> {
                val q = args.optString("query")
                val result = webSearch.search(q)
                result.ifBlank { "未找到相关结果" }
            }
            else -> "未知工具: $name"
        }
    }

    private fun launcherContext(): String {
        val session = AppContainer.repository.session.value
        val account = AppContainer.repository.selectedAccount()
        return buildString {
            appendLine("selectedVersion=${session.selectedVersionId ?: "无"}")
            appendLine("account=${account?.name ?: "无"} (${account?.type ?: "-"})")
            appendLine("memoryMb=${LauncherPrefs.maxMemoryMb()}")
            appendLine("renderer=${LauncherPrefs.rendererPreference()}")
            appendLine("installedCount=${AppContainer.repository.installedVersions.value.size}")
        }.trim()
    }
}
