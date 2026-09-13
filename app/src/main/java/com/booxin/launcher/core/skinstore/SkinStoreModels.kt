package com.booxin.launcher.core.skinstore

data class StoreSkin(
    val id: String,
    val name: String,
    val description: String,
    val textureUrl: String,
    val model: String = "classic",
    val tags: List<String> = emptyList(),
    val version: String = "1.0.0",
    val width: Int = 64,
    val height: Int = 64,
    val authorUserId: String,
    val authorUsername: String,
    val publishedAt: String,
    val updatedAt: String = "",
    val downloadCount: Int = 0,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val commentCount: Int = 0,
    /** original | reprint (empty if unknown / legacy). */
    val originType: String = "",
    val source: String = "",
    val allowReprint: Boolean? = null
) {
    fun isReprint(): Boolean =
        originType.equals("reprint", ignoreCase = true) ||
            tags.any { it.equals("转载", ignoreCase = true) }

    fun isOriginalMarked(): Boolean =
        originType.equals("original", ignoreCase = true) ||
            tags.any { it.equals("原创", ignoreCase = true) }

    fun allowReprintLabel(): String? = when (allowReprint) {
        true -> "允许转载"
        false -> "禁止转载"
        null -> when {
            tags.any { it.contains("允许转载") } -> "允许转载"
            tags.any { it.contains("禁止转载") } -> "禁止转载"
            else -> null
        }
    }

    /** Plain text badge (list/detail). Reprint itself is shown as a yellow circle separately. */
    fun originBadgeText(): String? {
        val allow = allowReprintLabel()
        return when {
            isReprint() -> allow
            isOriginalMarked() -> if (allow != null) "原创 · $allow" else "原创"
            else -> null
        }
    }
}

data class SkinComment(
    val id: String,
    val skinId: String,
    val userId: String,
    val username: String,
    val body: String,
    val createdAt: String,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false
)

data class SkinCommentReportResult(
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
        if (aiSummary.isNotBlank()) lines += "摘要：$aiSummary"
        if (actionTaken.isNotBlank()) lines += "处理结果：$actionTaken"
        return lines.joinToString("\n")
    }
}

data class SkinApplication(
    val id: String,
    val name: String,
    val description: String,
    val textureUrl: String,
    val model: String,
    val status: String,
    val rejectReason: String,
    val createdAt: String,
    val updatedAt: String
)

data class SkinRatingResult(
    val score: Int,
    val ratingAvg: Double,
    val ratingCount: Int
)
