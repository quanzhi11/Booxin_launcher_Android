package com.booxin.launcher.core.pluginstore

data class StorePlugin(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val type: String,
    val version: String = "1.0.0",
    val developerUserId: String,
    val developerUsername: String,
    val publishedAt: String,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val commentCount: Int = 0,
    /** Normalized: android / desktop. Empty or missing from API → both. */
    val platforms: List<String> = PluginStorePlatforms.ALL
)

data class PluginComment(
    val id: String,
    val pluginId: String,
    val userId: String,
    val username: String,
    val body: String,
    val createdAt: String,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false
)

/** Mirrors desktop DirectMessageReportResult. */
data class PluginCommentReportResult(
    val reportId: String,
    val aiReviewed: Boolean,
    val reportValid: Boolean?,
    val category: String,
    val actionLevel: Int,
    val actionLevelName: String,
    val aiSummary: String,
    val actionTaken: String,
    val message: String
) {
    fun dialogText(): String {
        if (message.isNotBlank()) return message
        val lines = mutableListOf("【审核结果】")
        if (!aiReviewed) {
            lines += "状态：已记录举报，等待人工复核。"
        } else {
            lines += when (reportValid) {
                true -> "举报是否成立：是"
                false -> "举报是否成立：否"
                null -> "举报是否成立：待确认"
            }
            if (reportValid == true && actionLevelName.isNotBlank()) {
                lines += "违规等级：$actionLevelName"
            }
        }
        if (category.isNotBlank() && category !in setOf("none", "manual", "pending")) {
            lines += "违规类型：$category"
        }
        if (aiSummary.isNotBlank()) lines += "摘要：$aiSummary"
        if (actionTaken.isNotBlank()) lines += "处理结果：$actionTaken"
        return lines.joinToString("\n")
    }
}

data class PluginApplication(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val type: String,
    val status: String,
    val rejectReason: String,
    val createdAt: String,
    val updatedAt: String,
    val reviewedAt: String?,
    /** Normalized: android / desktop. Empty or missing from API → both. */
    val platforms: List<String> = PluginStorePlatforms.ALL
)
