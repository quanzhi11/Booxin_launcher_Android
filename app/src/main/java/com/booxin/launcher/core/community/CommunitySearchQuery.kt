package com.booxin.launcher.core.community

/**
 * Resolves community search boxes that may contain Chinese.
 *
 * Modrinth indexes English titles/tags, so Chinese queries are expanded via a
 * local glossary then (if needed) machine-translated to English.
 */
object CommunitySearchQuery {

    data class Resolved(
        /** Query sent to Modrinth. */
        val query: String,
        /** Raw text from the user. */
        val original: String,
        val usedChinese: Boolean,
        /** How the English query was produced. */
        val via: Via = Via.NONE
    ) {
        enum class Via { NONE, GLOSSARY, TRANSLATE, GLOSSARY_AND_TRANSLATE }
    }

    /** Longest-first Chinese → English Minecraft / Modrinth terms. */
    private val glossary: List<Pair<String, String>> = listOf(
        "农夫乐事" to "farmer's delight",
        "机械动力" to "create",
        "旅程地图" to "journeymap",
        "动态光线" to "dynamic lights",
        "动态光" to "dynamic lights",
        "高清修复" to "hd fix",
        "世界生成" to "worldgen",
        "生物群系" to "biome",
        "背包整理" to "inventory sorting",
        "物品管理" to "inventory",
        "资源包" to "resource pack",
        "材质包" to "resource pack",
        "整合包" to "modpack",
        "着色器" to "shader",
        "小地图" to "minimap",
        "钠优化" to "sodium",
        "星光" to "starlight",
        "暮色森林" to "twilight forest",
        "暮色" to "twilight",
        "语音聊天" to "voice chat",
        "语音" to "voice chat",
        "多人游戏" to "multiplayer",
        "联机" to "multiplayer",
        "服务器" to "server",
        "光影" to "shader",
        "材质" to "texture pack",
        "模组" to "mod",
        "插件" to "mod",
        "优化" to "performance optimization",
        "流畅" to "performance fps",
        "帧数" to "fps",
        "美化" to "cosmetic visual",
        "装饰" to "decoration",
        "建筑" to "building",
        "魔法" to "magic",
        "科技" to "tech technology",
        "冒险" to "adventure",
        "生存" to "survival",
        "硬核" to "hardcore",
        "武器" to "weapon",
        "盔甲" to "armor",
        "护甲" to "armor",
        "食物" to "food",
        "动物" to "animal",
        "载具" to "vehicle",
        "交通" to "transport",
        "地图" to "map",
        "皮肤" to "skin",
        "披风" to "cape",
        "钠" to "sodium",
        "锂" to "lithium",
        "磷" to "phosphor",
        "铱" to "iris",
        "万物" to "jei",
    ).sortedByDescending { it.first.length }

    fun containsChinese(text: String): Boolean =
        text.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }

    /**
     * Expand / translate [raw] into an English-oriented Modrinth query.
     * English-only input is returned unchanged.
     */
    suspend fun resolve(raw: String): Resolved {
        val original = raw.trim()
        if (original.isBlank() || !containsChinese(original)) {
            return Resolved(query = original, original = original, usedChinese = false)
        }

        val (glossaryQuery, replaced) = applyGlossary(original)
        val stillChinese = containsChinese(glossaryQuery)

        if (!stillChinese) {
            return Resolved(
                query = glossaryQuery.ifBlank { original },
                original = original,
                usedChinese = true,
                via = Resolved.Via.GLOSSARY
            )
        }

        val translated = CommunityDescriptionTranslator.translateToEnglish(
            if (replaced) glossaryQuery else original
        ).trim()

        val merged = mergeQueryParts(
            latinKeep = glossaryQuery,
            translated = translated,
            fallback = original
        )

        val via = when {
            replaced && translated.isNotBlank() && containsChinese(glossaryQuery) ->
                Resolved.Via.GLOSSARY_AND_TRANSLATE
            translated.isNotBlank() -> Resolved.Via.TRANSLATE
            replaced -> Resolved.Via.GLOSSARY
            else -> Resolved.Via.TRANSLATE
        }

        return Resolved(
            query = merged,
            original = original,
            usedChinese = true,
            via = via
        )
    }

    private fun applyGlossary(text: String): Pair<String, Boolean> {
        var out = text
        var replaced = false
        for ((zh, en) in glossary) {
            if (zh in out) {
                out = out.replace(zh, " $en ")
                replaced = true
            }
        }
        // Drop leftover CJK punctuation noise; keep Latin / digits / spaces.
        out = out
            .replace(Regex("[\\u3000\\s]+"), " ")
            .trim()
        return out to replaced
    }

    private fun mergeQueryParts(latinKeep: String, translated: String, fallback: String): String {
        val latinOnly = latinKeep
            .map { ch -> if (ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf') ' ' else ch }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()

        val en = translated
            .takeIf { it.isNotBlank() && !containsChinese(it) && !it.equals(fallback, true) }
            .orEmpty()

        val parts = linkedSetOf<String>()
        if (latinOnly.isNotBlank()) {
            latinOnly.split(Regex("\\s+")).filter { it.length >= 2 }.forEach { parts += it.lowercase() }
        }
        if (en.isNotBlank()) {
            en.split(Regex("\\s+")).filter { it.length >= 2 }.forEach { parts += it.lowercase() }
        }
        if (parts.isEmpty()) {
            return en.ifBlank { latinOnly.ifBlank { fallback } }
        }
        return parts.joinToString(" ")
    }
}
